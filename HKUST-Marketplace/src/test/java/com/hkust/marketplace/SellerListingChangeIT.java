package com.hkust.marketplace;

import com.hkust.marketplace.models.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SellerListingChangeIT extends MySqlFixture {
    @Test
    void completedDealAndConversationRemainAccessibleAfterAdminTakedown() throws Exception {
        String listingId = live(ListingType.SELL).getId();
        Deal deal = buys.submit(buyer, listingId, ListingType.SELL, 35.0, null, null);
        var thread = chat.openForDeal(buyer, deal.getId());
        var message = chat.sendMessage(buyer, thread.id(), "Meet at the library");
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        buys.complete(buyer, deal.getId(), buys.getMyDeal(buyer, deal.getId()).getRowVersion());

        assertTrue(listings.removeListingAsAdmin(admin, listingId, seller.getUsername(), "Test takedown"));
        assertThrows(IllegalArgumentException.class, () -> browse.getListing(buyer, listingId));
        assertTrue(buys.getHistory(buyer).stream().anyMatch(entry -> entry.dealId().equals(deal.getId())));
        assertEquals(DealStatus.COMPLETED, offers.getSellerDeal(seller, deal.getId()).getStatus());
        for (User participant : java.util.List.of(buyer, seller)) {
            assertTrue(chat.getInbox(participant).stream().anyMatch(entry -> entry.id().equals(thread.id())));
            assertEquals(thread.id(), chat.openForDeal(participant, deal.getId()).id());
            assertTrue(chat.getConversation(participant, thread.id(), "library").messages().stream()
                    .anyMatch(entry -> entry.id() == message.id()));
        }
    }

    @Test
    void acceptedCounterIsShownOnCardAndDetailsThroughCompletion() throws Exception {
        Listing item = draft(ListingType.SELL);
        item.setPrice(100.0);
        listings.createListing(seller, item);
        String id = item.getId();
        listingRows.updateReview(id, ListingStatus.LIVE, null, item.getRowVersion());
        Deal offer = buys.submit(buyer, id, ListingType.SELL, 70.0, null, null);
        offers.counterOffer(seller, offer.getId(), offer.getRowVersion(), 80.0, null, null);
        assertNull(listings.getListing(seller, id).getAgreedTerms());
        buys.acceptCounter(buyer, offer.getId(), buys.getMyDeal(buyer, offer.getId()).getRowVersion());
        for (ListingStatus expected : new ListingStatus[]{ListingStatus.RESERVED, ListingStatus.SOLD}) {
            for (Listing view : java.util.List.of(listings.getMyListings(seller).getFirst(), listings.getListing(seller, id),
                    browse.getListing(buyer, id))) {
                assertEquals(expected, view.getStatus());
                assertEquals(100.0, view.getPrice());
                assertEquals(new java.math.BigDecimal("80.00"), view.getAgreedTerms().price());
                assertEquals("Agreed price: HK$ 80.00", view.termsDisplay());
            }
            assertEquals("Asking price: HK$ 100.00", browse.getListing(otherBuyer, id).termsDisplay());
            assertEquals("Agreed price: HK$ 80.00", buys.getMyDeal(buyer, offer.getId()).agreedTermsDisplay());
            var adminRows = new com.hkust.marketplace.repositories.DealRepository().searchDeals(null, null, null, null, null);
            assertEquals("Agreed price: HK$ 80.00", adminRows.getFirst().agreedTermsDisplay());
            if (expected == ListingStatus.RESERVED) {
                buys.complete(buyer, offer.getId(), buys.getMyDeal(buyer, offer.getId()).getRowVersion());
            }
        }
        assertEquals("Agreed price: HK$ 80.00", buys.getHistory(buyer).getFirst().agreedTerms().display());
    }

    @Test
    void reservedLoanShowsAcceptedDates() throws Exception {
        String id = live(ListingType.LEND).getId();
        var start = java.time.LocalDateTime.now().plusHours(1).withNano(0);
        Deal offer = buys.submit(buyer, id, ListingType.LEND, null, start, start.plusHours(2));
        var revisedStart = start.plusDays(1);
        var revisedDue = revisedStart.plusSeconds(15);
        offers.counterOffer(seller, offer.getId(), offer.getRowVersion(), null, revisedStart, revisedDue);
        buys.acceptCounter(buyer, offer.getId(), buys.getMyDeal(buyer, offer.getId()).getRowVersion());
        var expected = new Listing.AgreedTerms(null, revisedStart, revisedDue);
        assertEquals(expected, listings.getMyListings(seller).getFirst().getAgreedTerms());
        assertEquals(expected, listings.getListing(seller, id).getAgreedTerms());
        assertEquals(expected, browse.getListing(buyer, id).getAgreedTerms());
        assertEquals(expected.display(), buys.getMyDeal(buyer, offer.getId()).agreedTermsDisplay());
        assertNull(browse.getListing(otherBuyer, id).getAgreedTerms());
        var afterDue = java.time.Clock.fixed(revisedDue.atZone(java.time.ZoneId.systemDefault()).toInstant(),
                java.time.ZoneId.systemDefault());
        var scheduler = new com.hkust.marketplace.services.BuyerDealService(
                new com.hkust.marketplace.repositories.DealRepository(), users, notificationRows,
                com.hkust.marketplace.utils.DatabaseManager::getConnection, afterDue);
        assertEquals(1, scheduler.returnDueBorrowings());
        assertEquals(expected, buys.getHistory(buyer).getFirst().agreedTerms());
        assertNull(browse.getListing(buyer, id).getAgreedTerms());
    }

    @Test
    void sellerCanEditAndRemoveAPendingListingWithoutProposals() throws Exception {
        Listing item = draft(ListingType.SELL);
        listings.createListing(seller, item);
        String id = item.getId();
        var beforeEdit = listings.previewListingChange(seller, id);
        assertTrue(beforeEdit.affectedVersions().isEmpty());
        item.setPrice(30.0);
        listings.updateListing(seller, id, item, beforeEdit);
        Listing saved = listings.getListing(seller, id);
        assertEquals(30.0, saved.getPrice());
        assertEquals(ListingStatus.PENDING_REVIEW, saved.getStatus());
        var beforeDelete = listings.previewListingChange(seller, id);
        assertTrue(beforeDelete.listingVersion() > beforeEdit.listingVersion());
        listings.deleteListing(seller, id, beforeDelete);
        assertTrue(listings.getMyListings(seller).isEmpty());
    }

    @Test
    void newOfferRequiresFreshConfirmationBeforeRemoval() throws Exception {
        String id = live(ListingType.SELL).getId();
        var stale = listings.previewListingChange(seller, id);
        Deal proposal = buys.submit(buyer, id, ListingType.SELL, 20.0, null, null);
        assertThrows(IllegalArgumentException.class, () -> listings.deleteListing(seller, id, stale));
        assertEquals(DealStatus.PENDING, buys.getMyDeal(buyer, proposal.getId()).getStatus());
        var current = listings.previewListingChange(seller, id);
        assertEquals(proposal.getRowVersion(), current.affectedVersions().get(proposal.getId()));
        listings.deleteListing(seller, id, current);
        assertEquals(DealStatus.CANCELLED, buys.getMyDeal(buyer, proposal.getId()).getStatus());
        assertTrue(inbox(buyer).stream().anyMatch(n -> "Listing Removed".equals(n.getTitle())));
    }
}
