package com.hkust.marketplace;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.BuyerDealService;
import com.hkust.marketplace.services.NotificationService;
import com.hkust.marketplace.utils.DatabaseManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuyerTransactionsTest extends MySqlFixture {
    @ParameterizedTest
    @EnumSource(ListingType.class)
    void proposalReservesOnlyAfterAcceptanceAndFinalStatusIsVisibleToSeller(ListingType type) throws Exception {
        Listing listing = live(type);
        LocalDateTime start = LocalDateTime.now().plusMinutes(1).withNano(0);
        boolean loan = type == ListingType.LEND;
        Double price = type == ListingType.SELL ? 30.5 : null;
        var end = start.plusSeconds(5);
        Deal request = buys.submit(buyer, listing.getId(), type, price, loan ? start : null, loan ? end : null);
        assertEquals(DealStatus.PENDING, buys.getMyDeal(buyer, request.getId()).getStatus());
        assertEquals(ListingStatus.LIVE, listings.getListing(seller, listing.getId()).getStatus());
        assertTrue(buys.getMyDeals(otherBuyer).isEmpty());
        assertTrue(buys.getHistory(buyer).isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                buys.submit(buyer, listing.getId(), type, price, loan ? start : null, loan ? end : null));
        var thread = chat.openForListing(buyer, listing.getId());
        var message = chat.sendProposal(buyer, thread.id(), request.getId());
        assertEquals(type, message.proposalType());
        assertEquals(price, message.proposalPrice());
        assertEquals(loan ? start : null, message.borrowStart());
        assertEquals(loan ? end : null, message.borrowEnd());
        assertEquals(message.id(), chat.sendProposal(buyer, thread.id(), request.getId()).id());

        offers.acceptOffer(seller, request.getId(), request.getRowVersion());
        var accepted = buys.getMyDeal(buyer, request.getId());
        assertEquals(ListingStatus.RESERVED, listings.getMyListings(seller).getFirst().getStatus());
        assertThrows(IllegalArgumentException.class, () ->
                buys.submit(otherBuyer, listing.getId(), type, price, loan ? start : null, loan ? end : null));
        if (loan) {
            assertThrows(IllegalArgumentException.class, () -> buys.complete(buyer, accepted.getId(), accepted.getRowVersion()));
            clockAt(start.plusSeconds(1)).returnBorrowed(buyer, accepted.getId(), accepted.getRowVersion());
        } else {
            assertThrows(IllegalArgumentException.class, () -> buys.returnBorrowed(buyer, accepted.getId(), accepted.getRowVersion()));
            buys.complete(buyer, accepted.getId(), accepted.getRowVersion());
        }
        var history = buys.getHistory(buyer).getFirst();
        assertEquals(request.getId(), history.dealId());
        assertEquals(listing.getTitle(), history.title());
        assertEquals(seller.getUsername(), history.sellerUsername());
        assertNotNull(history.closedAt());
        assertEquals(switch (type) { case SELL -> "BOUGHT"; case LEND -> "BORROWED"; case GIVEAWAY -> "RECEIVED"; }, history.type());
        assertEquals(loan ? DealStatus.RETURNED : DealStatus.COMPLETED, history.outcome());
        assertEquals(switch (type) { case SELL -> ListingStatus.SOLD; case LEND -> ListingStatus.LIVE; case GIVEAWAY -> ListingStatus.GIVEN; },
                listings.getMyListings(seller).getFirst().getStatus());
        assertTrue(history.canReview());
    }

    @Test void reminderAndAutoReturnRunOnceAcrossRepeatedSchedulerTicks() throws Exception {
        var listing = live(ListingType.LEND);
        var start = LocalDateTime.now().plusHours(6).withNano(0);
        var end = start.plusHours(2);
        var deal = buys.submit(buyer, listing.getId(), ListingType.LEND, null, start, end);
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        clockAt(end.minusDays(2)).remindDueBorrowings();
        assertEquals(0, noticeCount(buyer, "Borrowing Due Soon"));
        clockAt(end.minusMinutes(1)).remindDueBorrowings();
        clockAt(end.minusSeconds(1)).remindDueBorrowings();
        assertEquals(1, noticeCount(buyer, "Borrowing Due Soon"));
        assertEquals(0, noticeCount(otherBuyer, "Borrowing Due Soon"));
        assertEquals(0, clockAt(end.minusSeconds(1)).returnDueBorrowings());
        assertEquals(1, clockAt(end).returnDueBorrowings());
        assertEquals(0, clockAt(end.plusSeconds(1)).returnDueBorrowings());
        assertEquals(1, noticeCount(buyer, "Borrowing Returned"));
        assertEquals(ListingStatus.LIVE, listings.getListing(seller, listing.getId()).getStatus());
        assertEquals(DealStatus.RETURNED, buys.getHistory(buyer).getFirst().outcome());
    }

    @Test void counterCanOnlyBeDeclinedByItsBuyerAtTheCurrentVersion() throws Exception {
        var listing = live(ListingType.SELL);
        var deal = buys.submit(buyer, listing.getId(), ListingType.SELL, 20.0, null, null);
        offers.counterOffer(seller, deal.getId(), deal.getRowVersion(), 25.0, null, null);
        var version = buys.getMyDeal(buyer, deal.getId()).getRowVersion();
        assertThrows(IllegalArgumentException.class, () -> buys.declineCounter(otherBuyer, deal.getId(), version));
        assertThrows(IllegalArgumentException.class, () -> buys.acceptCounter(buyer, deal.getId(), deal.getRowVersion()));
        buys.declineCounter(buyer, deal.getId(), version);
        assertEquals(DealStatus.DECLINED, buys.getMyDeal(buyer, deal.getId()).getStatus());
        assertEquals(ListingStatus.LIVE, listings.getListing(seller, listing.getId()).getStatus());
        assertEquals(1, noticeCount(buyer, "Offer Countered"));
        assertEquals(1, noticeCount(seller, "Counteroffer Declined"));
    }

    @Test void bothParticipantsShareSearchableHistoryAndAnotherBuyerCannotReadOrPost() throws Exception {
        var listing = live(ListingType.SELL);
        var thread = chat.openForListing(buyer, listing.getId());
        var deal = buys.submit(buyer, listing.getId(), ListingType.SELL, 20.0, null, null);
        assertEquals(thread.id(), chat.openForDeal(seller, deal.getId()).id());
        var buyerText = chat.sendMessage(buyer, thread.id(), "Can we meet at the library?");
        var sellerText = chat.sendMessage(seller, thread.id(), "Yes, after class.");
        for (User reader : List.of(buyer, seller)) {
            var saved = chat.getConversation(reader, thread.id(), "").messages();
            assertEquals(List.of(buyerText.id(), sellerText.id()), saved.stream().map(ChatMessage::id).toList());
            assertEquals(List.of(buyerText), chat.getConversation(reader, thread.id(), "library").messages());
            assertTrue(chat.getConversation(reader, thread.id(), "absent").messages().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> chat.getConversation(otherBuyer, thread.id(), ""));
        assertThrows(IllegalArgumentException.class, () -> chat.sendMessage(otherBuyer, thread.id(), "hello"));
        assertThrows(IllegalArgumentException.class, () -> chat.sendMessage(buyer, thread.id(), " "));
        assertThrows(IllegalArgumentException.class, () -> chat.sendMessage(buyer, thread.id(), "x".repeat(4001)));
        assertTrue(inbox(buyer).stream().anyMatch(n -> "Chat".equals(n.getCategory()) && n.getMessage().contains("after class")));
    }

    @Test void reviewsBelongToOneFinishedDealAndPublicAverageUsesVisibleReviews() throws Exception {
        var product = live(ListingType.SELL);
        var sale = buys.submit(buyer, product.getId(), ListingType.SELL, 30.0, null, null);
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, sale.getId(), 4, "Early"));
        offers.acceptOffer(seller, sale.getId(), sale.getRowVersion());
        buys.complete(buyer, sale.getId(), buys.getMyDeal(buyer, sale.getId()).getRowVersion());
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(otherBuyer, sale.getId(), 4, "Wrong buyer"));
        for (int rating : List.of(0, 6)) {
            assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, sale.getId(), rating, "Outside range"));
        }
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, sale.getId(), 5, " "));
        reviews.submitReview(buyer, sale.getId(), 5, "Accurate description");
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, sale.getId(), 4, "Duplicate"));
        var gift = live(ListingType.GIVEAWAY);
        var claim = buys.submit(otherBuyer, gift.getId(), ListingType.GIVEAWAY, null, null, null);
        offers.acceptOffer(seller, claim.getId(), claim.getRowVersion());
        buys.complete(otherBuyer, claim.getId(), buys.getMyDeal(otherBuyer, claim.getId()).getRowVersion());
        reviews.submitReview(otherBuyer, claim.getId(), 1, "Late collection");
        var profile = reviews.getPublicProfile(seller.getUsername());
        assertEquals(3.0, profile.average().orElseThrow());
        assertEquals(2, profile.reviews().size());
        var review = profile.reviews().stream().filter(r -> r.getRating() == 5).findFirst().orElseThrow();
        reviews.reply(seller, review.getId(), "Thank you");
        assertEquals(1, noticeCount(buyer, "Seller Replied to Your Review"));
        reviews.setHidden(admin, review.getId(), true);
        var history = buys.getHistory(buyer).getFirst();
        assertTrue(history.hidden());
        assertEquals(5, history.rating());
        assertEquals("Accurate description", history.comment());
        assertEquals("Thank you", history.reply());
        assertFalse(history.canReview());
        assertEquals(1.0, reviews.getPublicProfile(seller.getUsername()).average().orElseThrow());
    }

    @Test void removalAndOtherNoticesAreScopedToRecipientAndListing() throws Exception {
        var target = live(ListingType.SELL);
        var unrelated = live(ListingType.SELL);
        buys.submit(buyer, target.getId(), ListingType.SELL, 10.0, null, null);
        var declined = buys.submit(otherBuyer, target.getId(), ListingType.SELL, 11.0, null, null);
        offers.declineOffer(seller, declined.getId(), declined.getRowVersion());
        buys.submit(otherBuyer, unrelated.getId(), ListingType.SELL, 12.0, null, null);
        listings.removeListingAsAdmin(admin, target.getId(), seller.getUsername(), "Recalled item");
        assertEquals(1, noticeCount(buyer, "Listing Removed"));
        assertEquals(0, noticeCount(otherBuyer, "Listing Removed"));
        assertEquals(1, noticeCount(otherBuyer, "Offer Declined"));
        notificationRows.create(new Notification(buyer.getUsername(), "Campus closure", "Pickup postponed", "Announcement"));
        var board = new NotificationService(users, notificationRows, UserRole.BUYER);
        for (var notice : board.getNotifications(buyer, false)) {
            assertNotNull(notice.getTimestamp());
            assertFalse(notice.getCategory().isBlank());
            assertFalse(notice.getMessage().isBlank());
        }
        assertEquals(2, board.markAllAsRead(buyer));
        assertTrue(board.getNotifications(buyer, false).isEmpty());
        assertEquals(2, board.getNotifications(buyer, true).size());
        assertFalse(board.getNotifications(otherBuyer, false).isEmpty());
    }

    @Test void invalidPricesTypesAndInactiveAccountsNeverCreateADeal() throws Exception {
        var listing = live(ListingType.SELL);
        for (Double invalid : Arrays.asList(null, -0.01, 1.001, 100_000_000.0, Double.NaN)) {
            assertThrows(IllegalArgumentException.class, () ->
                    buys.submit(buyer, listing.getId(), ListingType.SELL, invalid, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, listing.getId(), ListingType.GIVEAWAY, null, null, null));
        for (User actor : Arrays.asList(null, seller, admin)) {
            assertThrows(IllegalArgumentException.class, () -> buys.submit(actor, listing.getId(), ListingType.SELL, 5.0, null, null));
        }
        users.setActive(buyer.getUsername(), false);
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, listing.getId(), ListingType.SELL, 5.0, null, null));
        users.setActive(buyer.getUsername(), true);
        assertTrue(buys.getMyDeals(buyer).isEmpty());
        assertEquals(0, noticeCount(seller, "New Buyer Proposal"));
    }

    @Test void acceptingACounterCancelsCompetingBuyerAndPreservesBothChatTerms() throws Exception {
        var item = live(ListingType.SELL);
        var proposal = buys.submit(buyer, item.getId(), ListingType.SELL, 18.0, null, null);
        var competitor = buys.submit(otherBuyer, item.getId(), ListingType.SELL, 19.0, null, null);
        var thread = chat.openForDeal(buyer, proposal.getId());
        chat.sendProposal(buyer, thread.id(), proposal.getId());
        offers.counterOffer(seller, proposal.getId(), proposal.getRowVersion(), 21.0, null, null);
        var counter = chat.sendProposal(seller, thread.id(), proposal.getId());
        assertEquals(ChatMessage.Kind.COUNTER, counter.kind());
        assertEquals(21.0, counter.proposalPrice());
        buys.acceptCounter(buyer, proposal.getId(), buys.getMyDeal(buyer, proposal.getId()).getRowVersion());
        assertEquals(DealStatus.CANCELLED, buys.getMyDeal(otherBuyer, competitor.getId()).getStatus());
        assertEquals(1, noticeCount(otherBuyer, "Proposal Cancelled"));
        assertEquals(List.of(18.0, 21.0), chat.getConversation(buyer, thread.id(), "").messages()
                .stream().map(ChatMessage::proposalPrice).toList());
    }

    @Test void chatBecomesReadOnlyWhenCounterpartyIsDeactivated() throws Exception {
        var listing = live(ListingType.LEND);
        String thread = chat.openForListing(buyer, listing.getId()).id();
        chat.sendMessage(seller, thread, "Pickup instructions");
        users.setActive(seller.getUsername(), false);
        assertFalse(chat.getConversation(buyer, thread, "").canSend());
        assertEquals("Pickup instructions", chat.getConversation(buyer, thread, "pickup").messages().getFirst().body());
        assertThrows(IllegalArgumentException.class, () -> chat.sendMessage(buyer, thread, "Still available?"));
        assertThrows(IllegalArgumentException.class, () -> chat.openForListing(buyer, listing.getId()));
    }

    @Test void staleBorrowCounterAndPrematureReturnLeaveTheReservationUnchanged() throws Exception {
        var item = live(ListingType.LEND);
        var start = LocalDateTime.now().plusDays(1).withNano(0);
        var end = start.plusSeconds(10);
        var proposal = buys.submit(buyer, item.getId(), ListingType.LEND, null, start, end);
        offers.counterOffer(seller, proposal.getId(), proposal.getRowVersion(), null, start, end);
        var counter = buys.getMyDeal(buyer, proposal.getId());
        assertThrows(IllegalArgumentException.class, () -> clockAt(end).acceptCounter(buyer, counter.getId(), counter.getRowVersion()));
        assertEquals(DealStatus.COUNTERED, buys.getMyDeal(buyer, counter.getId()).getStatus());
        buys.acceptCounter(buyer, counter.getId(), counter.getRowVersion());
        var accepted = buys.getMyDeal(buyer, counter.getId());
        assertThrows(IllegalArgumentException.class, () -> buys.returnBorrowed(buyer, accepted.getId(), accepted.getRowVersion()));
        assertEquals(ListingStatus.RESERVED, listings.getListing(seller, item.getId()).getStatus());
        assertTrue(buys.getHistory(buyer).isEmpty());
    }

    @Test void rejectedOrRemovedListingsAndExpiredSessionsCannotStartNewConversations() throws Exception {
        var pending = draft(ListingType.SELL);
        listings.createListing(seller, pending);
        assertThrows(IllegalArgumentException.class, () -> chat.openForListing(buyer, pending.getId()));
        var visible = live(ListingType.SELL);
        var thread = chat.openForListing(buyer, visible.getId());
        assertThrows(IllegalArgumentException.class, () -> chat.openForListing(seller, visible.getId()));
        assertThrows(IllegalArgumentException.class, () -> chat.openForListing(buyer, "missing"));
        assertThrows(IllegalArgumentException.class, () -> chat.getConversation(buyer, thread.id(), "s".repeat(4001)));
        assertThrows(IllegalArgumentException.class, () -> chat.getConversation(buyer, "", null));
        for (User actor : Arrays.asList(null, new User(), admin)) {
            assertThrows(IllegalArgumentException.class, () -> chat.getConversation(actor, thread.id(), null));
        }
        users.setActive(buyer.getUsername(), false);
        assertThrows(IllegalArgumentException.class, () -> chat.getInbox(buyer));
        assertThrows(IllegalArgumentException.class, () -> chat.sendMessage(buyer, thread.id(), "Unavailable"));
        assertTrue(chat.getConversation(seller, thread.id(), null).messages().isEmpty());
    }

    @Test void anUnavailableSellerRemainsInHistoryButCannotBeGivenANewReview() throws Exception {
        var listing = live(ListingType.SELL);
        var proposal = buys.submit(buyer, listing.getId(), ListingType.SELL, 30.0, null, null);
        offers.acceptOffer(seller, proposal.getId(), proposal.getRowVersion());
        buys.complete(buyer, proposal.getId(), buys.getMyDeal(buyer, proposal.getId()).getRowVersion());
        new AuthService(users).deleteUserAsAdmin(admin, seller.getUsername());
        var saved = buys.getHistory(buyer).getFirst();
        assertEquals(proposal.getId(), saved.dealId());
        assertEquals("Deleted seller", saved.sellerDisplay());
        assertFalse(saved.canReview());
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, proposal.getId(), 3, "Seller left"));
        assertThrows(IllegalArgumentException.class, () -> reviews.getPublicProfile(seller.getUsername()));
    }

    @Test void unsuccessfulBuyerActionsLeaveTheDealAndItsNotificationsUntouched() throws Exception {
        var listing = live(ListingType.GIVEAWAY);
        var request = buys.submit(buyer, listing.getId(), ListingType.GIVEAWAY, null, null, null);
        var id = request.getId();
        assertThrows(IllegalArgumentException.class, () -> buys.complete(buyer, id, 0));
        assertThrows(IllegalArgumentException.class, () -> buys.complete(buyer, "missing", 1));
        assertThrows(IllegalArgumentException.class, () -> buys.complete(buyer, id, request.getRowVersion()));
        assertThrows(IllegalArgumentException.class, () -> buys.acceptCounter(buyer, id, request.getRowVersion()));
        assertThrows(IllegalArgumentException.class, () -> buys.declineCounter(buyer, id, request.getRowVersion()));
        assertThrows(IllegalArgumentException.class, () -> buys.returnBorrowed(buyer, id, request.getRowVersion()));
        assertThrows(IllegalArgumentException.class, () -> buys.getMyDeal(otherBuyer, id));
        assertThrows(IllegalArgumentException.class, () -> buys.getMyDeal(buyer, "missing"));
        assertEquals(DealStatus.PENDING, buys.getMyDeal(buyer, id).getStatus());
        assertEquals(ListingStatus.LIVE, listings.getListing(seller, listing.getId()).getStatus());
        assertTrue(inbox(buyer).isEmpty());
    }

    @Test void malformedProposalTermsAndMissingDatesDoNotWritePartialRequests() throws Exception {
        var sale = live(ListingType.SELL);
        var gift = live(ListingType.GIVEAWAY);
        var loan = live(ListingType.LEND);
        var start = LocalDateTime.now().plusDays(1);
        var end = start.plusHours(1);
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, gift.getId(), ListingType.GIVEAWAY, 1.0, null, null));
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, sale.getId(), ListingType.SELL, 1.0, start, null));
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, sale.getId(), ListingType.SELL, 1.0, null, end));
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, loan.getId(), ListingType.LEND, 1.0, start, end));
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, loan.getId(), ListingType.LEND, null, null, end));
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, loan.getId(), ListingType.LEND, null, start, null));
        assertThrows(IllegalArgumentException.class, () -> buys.submit(buyer, sale.getId(), null, null, null, null));
        assertTrue(buys.getMyDeals(buyer).isEmpty());
        assertTrue(inbox(seller).isEmpty());
    }

    @Test void buyerCanReceiveAndReadOnlyTheirOwnNotification() throws Exception {
        var first = new Notification(buyer.getUsername(), "Announcement", "Pickup time changed", "Announcement");
        notificationRows.create(first);
        var service = new NotificationService(users, notificationRows, UserRole.BUYER);
        assertFalse(service.markAsRead(otherBuyer, first.getId()));
        assertTrue(service.markAsRead(buyer, first.getId()));
        assertFalse(service.markAsRead(buyer, first.getId()));
        for (User actor : Arrays.asList(null, new User(), admin, seller)) {
            assertThrows(IllegalArgumentException.class, () -> service.getNotifications(actor, true));
        }
        users.setActive(buyer.getUsername(), false);
        assertThrows(IllegalArgumentException.class, () -> service.markAllAsRead(buyer));
        assertTrue(inbox(otherBuyer).isEmpty());
    }

    @Test void formalChatMessagesMustBelongToTheListingAndAnOpenProposal() throws Exception {
        var item = live(ListingType.SELL);
        var unrelated = live(ListingType.SELL);
        var thread = chat.openForListing(buyer, item.getId());
        var wrongThread = chat.openForListing(buyer, unrelated.getId());
        var deal = buys.submit(buyer, item.getId(), ListingType.SELL, 35.0, null, null);
        assertThrows(IllegalArgumentException.class, () -> chat.openForDeal(otherBuyer, deal.getId()));
        assertThrows(IllegalArgumentException.class, () -> chat.sendProposal(buyer, wrongThread.id(), deal.getId()));
        assertThrows(IllegalArgumentException.class, () -> chat.sendProposal(seller, thread.id(), deal.getId()));
        offers.declineOffer(seller, deal.getId(), deal.getRowVersion());
        assertThrows(IllegalArgumentException.class, () -> chat.sendProposal(buyer, thread.id(), deal.getId()));
        assertTrue(chat.getConversation(buyer, thread.id(), "").messages().isEmpty());
    }

    @Test void reviewsRequireAnActiveBuyerAndAValidWrittenComment() throws Exception {
        var gift = live(ListingType.GIVEAWAY);
        var deal = buys.submit(buyer, gift.getId(), ListingType.GIVEAWAY, null, null, null);
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        buys.complete(buyer, deal.getId(), buys.getMyDeal(buyer, deal.getId()).getRowVersion());
        for (var actor : Arrays.asList(null, new User(), seller, admin)) {
            assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(actor, deal.getId(), 5, "Good"));
        }
        users.setActive(buyer.getUsername(), false);
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, deal.getId(), 5, "Good"));
        users.setActive(buyer.getUsername(), true);
        for (String text : Arrays.asList(null, "  ", "x".repeat(2001))) {
            assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, deal.getId(), 5, text));
        }
        assertThrows(IllegalArgumentException.class, () -> reviews.submitReview(buyer, "missing", 5, "Good"));
        assertTrue(reviews.getPublicProfile(seller.getUsername()).reviews().isEmpty());
        assertTrue(buys.getHistory(buyer).getFirst().canReview());
    }

    private BuyerDealService clockAt(LocalDateTime time) {
        var zone = ZoneId.systemDefault();
        return new BuyerDealService(new DealRepository(), users, notificationRows, DatabaseManager::getConnection,
                Clock.fixed(time.atZone(zone).toInstant(), zone));
    }

    private long noticeCount(User recipient, String title) throws Exception {
        return inbox(recipient).stream().filter(n -> title.equals(n.getTitle())).count();
    }
}
