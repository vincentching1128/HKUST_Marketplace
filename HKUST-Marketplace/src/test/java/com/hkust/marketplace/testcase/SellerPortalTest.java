package com.hkust.marketplace.testcase;

import com.hkust.marketplace.MySqlFixture;
import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.ListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.*;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.hkust.marketplace.services.AuthService.LoginStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 2 service and database contracts, using disposable MySQL databases. */
@DisplayName("Task 2: Seller Portal")
public class SellerPortalTest extends SellerTestFixture {
    @Nested
    class Task21_SellerRegistrationTests {
        @Test
        void normalizesUsernameAndStoresOptionalFieldsAndArgon2idHash() throws Exception {
            User draft = new User(" " + prefix.toUpperCase(Locale.ROOT) + "NEW ", "New Seller", null, UserRole.SELLER);
            draft.setBio("Campus books");
            draft.setPreferredMeetup("Library");
            auth.register(draft, PASSWORD);

            User saved = users.findByUsername(prefix + "new");
            assertEquals(prefix + "new", saved.getUsername());
            assertEquals(UserRole.SELLER, saved.getRole());
            assertEquals("Campus books", saved.getBio());
            assertEquals("Library", saved.getPreferredMeetup());
            assertTrue(saved.getPasswordHash().startsWith("$argon2id$"));
            assertNotEquals(PASSWORD, saved.getPasswordHash());
            assertTrue(PasswordHasher.verify(PASSWORD, saved.getPasswordHash()));
        }

        @Test
        void rejectsDuplicateNamesAcrossRolesAndWeakPasswords() throws Exception {
            for (User existing : List.of(seller, buyer, admin)) {
                User duplicate = new User(existing.getUsername().toUpperCase(Locale.ROOT), "Duplicate", null, UserRole.SELLER);
                assertThrows(IllegalArgumentException.class, () -> auth.register(duplicate, PASSWORD));
                assertEquals(existing.getRole(), users.findByUsername(existing.getUsername()).getRole());
            }
            User draft = new User(prefix + "weak", "Weak Seller", null, UserRole.SELLER);
            assertThrows(IllegalArgumentException.class, () -> auth.register(draft, "short"));
            assertNull(users.findByUsername(draft.getUsername()));
        }
    }

    @Nested
    class Task22_SellerLoginTests {
        @Test
        void distinguishesValidWrongUnknownInactiveAndWrongRoleCredentials() throws Exception {
            assertEquals(SUCCESS, auth.login(seller.getUsername().toUpperCase(Locale.ROOT), PASSWORD, UserRole.SELLER).status());
            assertEquals(INCORRECT_PASSWORD, auth.login(seller.getUsername(), "Wrong123", UserRole.SELLER).status());
            assertEquals(USER_NOT_FOUND, auth.login(prefix + "missing", PASSWORD, UserRole.SELLER).status());
            assertEquals(WRONG_ROLE, auth.login(buyer.getUsername(), PASSWORD, UserRole.SELLER).status());
            assertEquals(WRONG_ROLE, auth.login(admin.getUsername(), PASSWORD, UserRole.SELLER).status());

            User inactive = createUser("inactive", UserRole.SELLER, false);
            assertEquals(ACCOUNT_DEACTIVATED, auth.login(inactive.getUsername(), PASSWORD, UserRole.SELLER).status());
        }
    }

    @Nested
    class Task23_CreateNewListingTests {
        @Test
        void persistsConditionalFieldsAndPendingStatusForAllThreeTypes() throws Exception {
            Listing sale = createListing(seller, ListingType.SELL, "sale");
            Listing loan = createListing(seller, ListingType.LEND, "loan");
            Listing gift = createListing(seller, ListingType.GIVEAWAY, "gift");

            assertEquals(42.50, sale.getPrice());
            assertNull(sale.getLendingDuration());
            assertNull(loan.getPrice());
            assertEquals(5, loan.getLendingDuration());
            assertEquals(LendingUnit.SECONDS, loan.getLendingUnit());
            assertNull(gift.getPrice());
            assertNull(gift.getLendingDuration());
            for (Listing item : List.of(sale, loan, gift)) {
                assertEquals(ListingStatus.PENDING_REVIEW, item.getStatus());
                assertEquals("Library", item.getMeetupLocation());
                assertEquals("Like New", item.getCondition());
                assertEquals("Textbooks", item.getCategory());
            }
        }

        @Test
        void rejectsInvalidPricesDurationsAndRequiredListingFields() {
            for (Double price : java.util.Arrays.asList(null, -1.0, 0.0, Double.NaN, 1.001, 100_000_000.0)) {
                rejectListing(ListingType.SELL, item -> item.setPrice(price));
            }
            rejectListing(ListingType.LEND, item -> item.setLendingDuration(0));
            rejectListing(ListingType.LEND, item -> item.setLendingDuration(null));
            rejectListing(ListingType.LEND, item -> item.setLendingUnit(null));
            rejectListing(null, item -> {});
            rejectListing(ListingType.GIVEAWAY, item -> item.setTitle(" "));
            rejectListing(ListingType.GIVEAWAY, item -> item.setTitle("a".repeat(256)));
            rejectListing(ListingType.GIVEAWAY, item -> item.setCategory(" "));
            rejectListing(ListingType.GIVEAWAY, item -> item.setCondition("Invalid"));
            rejectListing(ListingType.GIVEAWAY, item -> item.setDescription(""));
            rejectListing(ListingType.GIVEAWAY, item -> item.setDescription("圖".repeat(22_000)));
            rejectListing(ListingType.GIVEAWAY, item -> item.setMeetupLocation(null));
        }

        @Test
        void roundTripsSixOrderedPhotosAndRejectsCountAndSizeOverflows() throws Exception {
            Listing item = draft(ListingType.SELL, "photo");
            List<byte[]> photos = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(index -> new byte[]{(byte) index}).toList();
            item.setPhotos(photos);
            listings.createListing(seller, item);
            var saved = listings.getListing(seller, item.getId()).getPhotos();
            assertEquals(6, saved.size());
            for (int index = 0; index < 6; index++) assertArrayEquals(photos.get(index), saved.get(index));
            rejectListing(ListingType.SELL, listing -> listing.setPhotos(java.util.Collections.nCopies(7, new byte[]{1})));
            rejectListing(ListingType.SELL, listing -> listing.setPhotos(List.of(new byte[0])));
            rejectListing(ListingType.SELL, listing -> listing.setPhotos(List.of(new byte[Listing.MAX_PHOTO_BYTES + 1])));
        }

        private void rejectListing(ListingType type, Consumer<Listing> change) {
            Listing item = draft(type, "invalid");
            change.accept(item);
            assertThrows(IllegalArgumentException.class, () -> listings.createListing(seller, item));
        }
    }

    @Nested
    class Task24_MyListingsTests {
        @Test
        void scopesListingsAndPersistsEditCancellationAndDeletionNotifications() throws Exception {
            Listing item = live(ListingType.SELL, "edit");
            Listing archive = live(ListingType.SELL, "archive");
            createListing(otherSeller, ListingType.GIVEAWAY, "another-seller");
            assertEquals(2, listings.getMyListings(seller).size());

            Deal pending = propose(buyer, item, ListingType.SELL);
            Deal countered = propose(otherBuyer, item, ListingType.SELL);
            offers.counterOffer(seller, countered.getId(), countered.getRowVersion(), 32.0, null, null);
            var preview = listings.previewListingChange(seller, item.getId());
            assertEquals(2, preview.affectedVersions().size());
            item.setPrice(39.0);
            listings.updateListing(seller, item.getId(), item, preview);
            assertEquals(ListingStatus.PENDING_REVIEW, listings.getListing(seller, item.getId()).getStatus());
            assertEquals(DealStatus.CANCELLED, buys.getMyDeal(buyer, pending.getId()).getStatus());
            assertEquals(DealStatus.CANCELLED, buys.getMyDeal(otherBuyer, countered.getId()).getStatus());
            assertTrue(inbox(buyer).stream().anyMatch(n -> n.getMessage().contains(item.getTitle())));
            assertTrue(inbox(otherBuyer).stream().anyMatch(n -> n.getMessage().contains(item.getTitle())
                    && "Listing Changed".equals(n.getTitle())));

            Deal open = propose(buyer, archive, ListingType.SELL);
            var confirmed = listings.previewListingChange(seller, archive.getId());
            assertTrue(confirmed.affectedVersions().containsKey(open.getId()));
            listings.deleteListing(seller, archive.getId(), confirmed);
            assertNull(listingRows.findByIdAndSeller(archive.getId(), seller.getUsername()));
            try (var connection = DatabaseManager.getConnection();
                 var query = connection.prepareStatement("SELECT listingStatus FROM listings WHERE id = ?")) {
                query.setString(1, archive.getId());
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REMOVED", result.getString(1));
                }
            }
            assertEquals(1, listings.getMyListings(seller).size());
            assertEquals(DealStatus.CANCELLED, buys.getMyDeal(buyer, open.getId()).getStatus());
            assertNotice(buyer, "Listing Removed", archive.getTitle());
        }

        @Test
        void blocksChangesToReservedAndFulfilledListings() throws Exception {
            for (ListingType type : ListingType.values()) {
                Listing item = live(type, "locked-" + type);
                Deal deal = propose(buyer, item, type);
                offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
                assertEquals(ListingStatus.RESERVED, listings.getListing(seller, item.getId()).getStatus());
                assertBlocked(item);
                setStatus(item, ListingStatus.LIVE);
                assertBlocked(item); // An accepted deal should protect even a stale listing status.
                setStatus(item, ListingStatus.RESERVED);
                if (type == ListingType.LEND) {
                    setStatus(item, ListingStatus.LENT);
                } else {
                    buys.complete(buyer, deal.getId(), buys.getMyDeal(buyer, deal.getId()).getRowVersion());
                }
                ListingStatus expected = switch (type) {
                    case SELL -> ListingStatus.SOLD;
                    case LEND -> ListingStatus.LENT;
                    case GIVEAWAY -> ListingStatus.GIVEN;
                };
                assertEquals(expected, listings.getListing(seller, item.getId()).getStatus());
                assertBlocked(item);
            }
        }

        private void assertBlocked(Listing item) {
            assertThrows(IllegalArgumentException.class, () -> listings.updateListing(seller, item.getId(), item));
            assertThrows(IllegalArgumentException.class, () -> listings.deleteListing(seller, item.getId()));
        }
    }

    @Nested
    class Task25_IncomingOffersTests {
        @Test
        void expiredBorrowProposalCannotReserveTheListing() throws Exception {
            Listing item = live(ListingType.LEND, "expired-borrow");
            Deal proposal = propose(buyer, item, ListingType.LEND);
            ZoneId zone = ZoneId.systemDefault();
            Clock afterExpiry = Clock.fixed(proposal.getBorrowEnd().plusSeconds(1).atZone(zone).toInstant(), zone);
            var sellerDeals = new DealService(users, new DealRepository(), listingRows, notificationRows,
                    DatabaseManager::getConnection, afterExpiry);

            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> sellerDeals.acceptOffer(seller, proposal.getId(), proposal.getRowVersion()));
            assertTrue(rejected.getMessage().contains("borrowing period has expired"));
            assertEquals(DealStatus.PENDING, offers.getSellerDeal(seller, proposal.getId()).getStatus());
            assertEquals(ListingStatus.LIVE, listings.getListing(seller, item.getId()).getStatus());
        }

        @Test
        void staleAcceptancePreservesTheNewerCounteroffer() throws Exception {
            Listing item = live(ListingType.SELL, "stale-offer");
            Deal original = propose(buyer, item, ListingType.SELL);
            offers.counterOffer(seller, original.getId(), original.getRowVersion(), 38.0, null, null);

            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> offers.acceptOffer(seller, original.getId(), original.getRowVersion()));
            assertTrue(rejected.getMessage().contains("proposal changed"));
            Deal current = buys.getMyDeal(buyer, original.getId());
            assertEquals(DealStatus.COUNTERED, current.getStatus());
            assertEquals(38.0, current.getCounterOfferPrice());
            assertEquals(ListingStatus.LIVE, listings.getListing(seller, item.getId()).getStatus());
        }

        @Test
        void simultaneousGiveawayAcceptancesHaveExactlyOneWinner() throws Exception {
            Listing item = live(ListingType.GIVEAWAY, "giveaway-race");
            Deal first = propose(buyer, item, ListingType.GIVEAWAY);
            Deal second = propose(otherBuyer, item, ListingType.GIVEAWAY);
            var start = new CyclicBarrier(2);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var firstResult = workers.submit(() -> tryAcceptClaim(first, start));
                var secondResult = workers.submit(() -> tryAcceptClaim(second, start));
                boolean firstWon = firstResult.get(15, TimeUnit.SECONDS);
                assertNotEquals(firstWon, secondResult.get(15, TimeUnit.SECONDS));
                assertEquals(firstWon ? DealStatus.ACCEPTED : DealStatus.CANCELLED,
                        buys.getMyDeal(buyer, first.getId()).getStatus());
                assertEquals(firstWon ? DealStatus.CANCELLED : DealStatus.ACCEPTED,
                        buys.getMyDeal(otherBuyer, second.getId()).getStatus());
                assertEquals(ListingStatus.RESERVED, listings.getListing(seller, item.getId()).getStatus());
            } finally {
                workers.shutdownNow();
            }
        }

        private boolean tryAcceptClaim(Deal claim, CyclicBarrier start) throws Exception {
            start.await(5, TimeUnit.SECONDS);
            try {
                offers.acceptOffer(seller, claim.getId(), claim.getRowVersion());
                return true;
            } catch (IllegalArgumentException rejected) {
                assertTrue(rejected.getMessage().contains("proposal changed"), rejected::getMessage);
                return false;
            }
        }

        @Test
        void acceptsOneOfferReservesListingCancelsCompetitorAndNotifiesBothBuyers() throws Exception {
            Listing item = live(ListingType.GIVEAWAY, "competing");
            Deal winner = propose(buyer, item, ListingType.GIVEAWAY);
            Deal loser = propose(otherBuyer, item, ListingType.GIVEAWAY);
            assertEquals(2, offers.getIncomingOffers(seller).size());
            offers.acceptOffer(seller, winner.getId(), winner.getRowVersion());
            assertEquals(ListingStatus.RESERVED, listings.getListing(seller, item.getId()).getStatus());
            assertEquals(DealStatus.ACCEPTED, buys.getMyDeal(buyer, winner.getId()).getStatus());
            assertEquals(DealStatus.CANCELLED, buys.getMyDeal(otherBuyer, loser.getId()).getStatus());
            assertTrue(offers.getIncomingOffers(seller).isEmpty());
            assertNotice(buyer, "Offer Accepted", item.getTitle());
            assertNotice(otherBuyer, "Proposal Cancelled", item.getTitle());
        }

        @Test
        void countersSalesAndLoansRejectsGiveawayCountersAndDeclinesOpenOffers() throws Exception {
            Listing sale = live(ListingType.SELL, "sale");
            Listing loan = live(ListingType.LEND, "loan");
            Listing gift = live(ListingType.GIVEAWAY, "gift");
            Deal saleOffer = propose(buyer, sale, ListingType.SELL);
            Deal loanOffer = propose(buyer, loan, ListingType.LEND);
            Deal claim = propose(buyer, gift, ListingType.GIVEAWAY);
            LocalDateTime start = loanOffer.getBorrowStart().plusMinutes(1);
            LocalDateTime due = start.plusSeconds(5);

            offers.counterOffer(seller, saleOffer.getId(), saleOffer.getRowVersion(), 37.50, null, null);
            offers.counterOffer(seller, loanOffer.getId(), loanOffer.getRowVersion(), null, start, due);
            assertThrows(IllegalArgumentException.class,
                    () -> offers.counterOffer(seller, claim.getId(), claim.getRowVersion(), 1.0, null, null));
            assertEquals(3, offers.getIncomingOffers(seller).size());
            assertTrue(offers.getIncomingOffers(seller).stream().anyMatch(d -> d.getStatus() == DealStatus.PENDING));
            Deal saved = buys.getMyDeal(buyer, saleOffer.getId());
            assertEquals(DealStatus.COUNTERED, saved.getStatus());
            assertEquals(37.50, saved.getCounterOfferPrice());
            Deal loanCounter = buys.getMyDeal(buyer, loanOffer.getId());
            assertEquals(start, loanCounter.getCounterBorrowStart());
            assertEquals(due, loanCounter.getCounterBorrowEnd());
            assertNotice(buyer, "Offer Countered", sale.getTitle());
            assertNotice(buyer, "Offer Countered", loan.getTitle());
            offers.declineOffer(seller, claim.getId(), claim.getRowVersion());
            assertEquals(DealStatus.DECLINED, buys.getMyDeal(buyer, claim.getId()).getStatus());
            assertNotice(buyer, "Offer Declined", gift.getTitle());
        }

        @Test
        void opensLinkedPrivateChatAndSearchesPersistedMessages() throws Exception {
            Deal deal = propose(buyer, live(ListingType.SELL, "chat"), ListingType.SELL);
            var thread = chat.openForDeal(seller, deal.getId());
            assertEquals(thread.id(), chat.openForDeal(buyer, deal.getId()).id());
            var sent = chat.sendMessage(seller, thread.id(), prefix + "Library pickup");
            chat.sendMessage(buyer, thread.id(), prefix + "Agreed");
            var found = chat.getConversation(buyer, thread.id(), "Library").messages();
            assertEquals(List.of(sent.id()), found.stream().map(ChatMessage::id).toList());
            assertThrows(IllegalArgumentException.class, () -> chat.getConversation(otherSeller, thread.id(), null));
        }
    }

    @Nested
    class Task26_ManageProfileTests {
        @Test
        void savesProfileWithOrWithoutPasswordAndRejectsWeakPassword() throws Exception {
            User profile = users.findByUsername(seller.getUsername());
            String original = profile.getPasswordHash();
            profile.setFullName("Updated Seller");
            profile.setBio("Campus stationery");
            profile.setPreferredMeetup("Library");
            assertTrue(auth.updateProfile(profile, ""));
            User saved = users.findByUsername(seller.getUsername());
            assertEquals("Updated Seller", saved.getFullName());
            assertEquals("Campus stationery", saved.getBio());
            assertEquals("Library", saved.getPreferredMeetup());
            assertEquals(original, saved.getPasswordHash());

            profile.setFullName("Must not persist");
            assertThrows(IllegalArgumentException.class, () -> auth.updateProfile(profile, "weak"));
            assertEquals("Updated Seller", users.findByUsername(seller.getUsername()).getFullName());
            profile.setFullName("Updated Seller");
            assertTrue(auth.updateProfile(profile, "NewSeller42"));
            assertEquals(SUCCESS, auth.login(seller.getUsername(), "NewSeller42", UserRole.SELLER).status());
            assertEquals(INCORRECT_PASSWORD, auth.login(seller.getUsername(), PASSWORD, UserRole.SELLER).status());
        }
    }

    @Nested
    class Task27_NotificationBoardTests {
        @Test
        void recordsOfferBorrowClaimCompletionAndReviewAndSupportsReadHistory() throws Exception {
            Listing sale = live(ListingType.SELL, "sale");
            Deal saleOffer = propose(buyer, sale, ListingType.SELL);
            Listing loan = live(ListingType.LEND, "loan");
            propose(buyer, loan, ListingType.LEND);
            Listing gift = live(ListingType.GIVEAWAY, "gift");
            propose(buyer, gift, ListingType.GIVEAWAY);
            complete(saleOffer);
            reviews.submitReview(buyer, saleOffer.getId(), 4, prefix + "Review");
            for (Listing item : List.of(sale, loan, gift)) assertNotice(seller, "New Buyer Proposal", item.getTitle());
            assertNotice(seller, "Deal Completed", sale.getTitle());
            assertNotice(seller, "New Seller Review", sale.getTitle());
            NotificationService board = new NotificationService(users, notificationRows, UserRole.SELLER);
            var unread = board.getNotifications(seller, false);
            assertTrue(unread.stream().allMatch(n -> n.getTimestamp() != null && !n.getCategory().isBlank() && !n.isRead()));
            assertThrows(IllegalArgumentException.class, () -> board.getNotifications(null, false));
            assertThrows(IllegalArgumentException.class, () -> board.getNotifications(buyer, false));
            assertFalse(board.markAsRead(otherSeller, unread.getFirst().getId()));
            assertTrue(board.markAsRead(seller, unread.getFirst().getId()));
            assertEquals(unread.size() - 1, board.getNotifications(seller, false).size());
            assertEquals(unread.size() - 1, board.markAllAsRead(seller));
            assertTrue(board.getNotifications(seller, false).isEmpty());
            assertEquals(unread.size(), board.getNotifications(seller, true).size());
        }
    }

    @Nested
    class Task28_SellerStatsTests {
        @Test
        void aggregatesViewsOffersCompletionVisibleRatingsAndChartBuckets() throws Exception {
            Listing first = live(ListingType.SELL, "first");
            Listing second = live(ListingType.GIVEAWAY, "second");
            Listing third = draft(ListingType.LEND, "third");
            third.setCategory("Electronics");
            listings.createListing(seller, third);
            browse.getListing(buyer, first.getId());
            browse.getListing(otherBuyer, first.getId());
            Deal firstOffer = propose(buyer, first, ListingType.SELL);
            propose(otherBuyer, first, ListingType.SELL);
            complete(firstOffer);
            reviews.submitReview(buyer, firstOffer.getId(), 5, prefix + "Great");
            Deal secondOffer = propose(buyer, second, ListingType.GIVEAWAY);
            complete(secondOffer);
            reviews.submitReview(buyer, secondOffer.getId(), 1, prefix + "Poor");
            var hidden = reviews.getSellerReviews(seller).stream().filter(r -> r.getRating() == 1).findFirst().orElseThrow();
            reviews.setHidden(admin, hidden.getId(), true);

            var stats = listings.getSellerStats(seller);
            assertEquals(2, stats.views());
            assertEquals(3, stats.offersReceived());
            assertEquals(2, stats.completedDeals());
            assertEquals(5.0, stats.averageRating());
            assertEquals(Map.of("Textbooks", 2L, "Electronics", 1L), stats.listingsByCategory());
            assertEquals(Map.of(ListingStatus.SOLD, 1L, ListingStatus.GIVEN, 1L, ListingStatus.PENDING_REVIEW, 1L),
                    stats.listingsByStatus());
        }
    }

    @Nested
    class Task29_ReviewAndFeedbackTests {
        @Test
        void repliesOnceFlagsOnceAndFiltersModeratedReviews() throws Exception {
            Review review = reviewedSale("feedback");
            assertThrows(IllegalArgumentException.class, () -> reviews.reply(seller, review.getId(), " "));
            assertThrows(IllegalArgumentException.class, () -> reviews.reply(seller, review.getId(), "a".repeat(2001)));
            reviews.reply(seller, review.getId(), prefix + "Thank you");
            assertEquals(prefix + "Thank you", reviews.getSellerReviews(seller).getFirst().getSellerReply());
            assertThrows(IllegalArgumentException.class, () -> reviews.reply(seller, review.getId(), "Duplicate"));
            assertNotice(buyer, "Seller Replied to Your Review", review.getListingTitle());
            reviews.flag(seller, review.getId());
            reviews.flag(seller, review.getId());
            assertTrue(reviews.getSellerReviews(seller).getFirst().isFlagged());
            assertNotice(admin, "Seller Flagged a Review", review.getListingTitle());
            assertEquals(1, inbox(admin).stream().filter(n -> "Seller Flagged a Review".equals(n.getTitle())).count());
            reviews.setHidden(admin, review.getId(), true);
            assertTrue(reviews.getSellerReviews(seller).isEmpty());
            assertTrue(reviews.getPublicProfile(seller.getUsername()).reviews().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> reviews.flag(seller, review.getId()));
            assertThrows(IllegalArgumentException.class, () -> reviews.reply(seller, review.getId(), "Hidden"));
        }
    }

    @Nested
    class SellerRbacAndEdgeCaseTests {
        @Test
        void sellerReadsRequireAnActiveSellerAndScopeResultsToTheirOwner() throws Exception {
            Listing item = live(ListingType.SELL, "private-listing");
            Review review = reviewedSale("private-review");
            User inactive = createUser("read-inactive", UserRole.SELLER, false);
            for (User actor : java.util.Arrays.asList(null, buyer, inactive)) {
                assertThrows(IllegalArgumentException.class, () -> listings.getMyListings(actor));
                assertThrows(IllegalArgumentException.class, () -> listings.getListing(actor, item.getId()));
                assertThrows(IllegalArgumentException.class, () -> listings.getSellerStats(actor));
                assertThrows(IllegalArgumentException.class, () -> reviews.getSellerReviews(actor));
            }

            Listing own = createListing(otherSeller, ListingType.GIVEAWAY, "other-seller-listing");
            assertThrows(IllegalArgumentException.class, () -> listings.getListing(otherSeller, item.getId()));
            assertEquals(List.of(own.getId()), listings.getMyListings(otherSeller).stream().map(Listing::getId).toList());
            var otherStats = listings.getSellerStats(otherSeller);
            assertEquals(Map.of(ListingStatus.PENDING_REVIEW, 1L), otherStats.listingsByStatus());
            assertEquals(0, otherStats.completedDeals());
            assertNull(otherStats.averageRating());
            assertTrue(reviews.getSellerReviews(otherSeller).isEmpty());
            assertEquals(1, listings.getSellerStats(seller).completedDeals());
            assertEquals(review.getId(), reviews.getSellerReviews(seller).getFirst().getId());
        }

        @Test
        void deniesUnauthenticatedBuyersAndUnrelatedSellersAccessToSellerResources() throws Exception {
            Listing item = live(ListingType.SELL, "protected");
            Deal deal = propose(buyer, item, ListingType.SELL);
            Review review = reviewedSale("protected-review");
            for (User actor : java.util.Arrays.asList(null, buyer, otherSeller)) {
                assertThrows(IllegalArgumentException.class, () -> listings.updateListing(actor, item.getId(), item));
                assertThrows(IllegalArgumentException.class, () -> listings.deleteListing(actor, item.getId()));
                assertThrows(IllegalArgumentException.class, () -> offers.getSellerDeal(actor, deal.getId()));
                assertThrows(IllegalArgumentException.class, () -> offers.acceptOffer(actor, deal.getId(), deal.getRowVersion()));
                assertThrows(IllegalArgumentException.class, () -> offers.declineOffer(actor, deal.getId(), deal.getRowVersion()));
                assertThrows(IllegalArgumentException.class,
                        () -> offers.counterOffer(actor, deal.getId(), deal.getRowVersion(), 30.0, null, null));
                assertThrows(IllegalArgumentException.class, () -> reviews.reply(actor, review.getId(), "Unauthorized"));
                assertThrows(IllegalArgumentException.class, () -> reviews.flag(actor, review.getId()));
            }
            assertThrows(IllegalArgumentException.class, () -> offers.getIncomingOffers(null));
            assertThrows(IllegalArgumentException.class, () -> offers.getIncomingOffers(buyer));
            assertTrue(offers.getIncomingOffers(otherSeller).isEmpty());
            assertEquals(DealStatus.PENDING, offers.getSellerDeal(seller, deal.getId()).getStatus());
            assertEquals(ListingStatus.LIVE, listings.getListing(seller, item.getId()).getStatus());
            Review unchanged = reviews.getSellerReviews(seller).getFirst();
            assertNull(unchanged.getSellerReply());
            assertFalse(unchanged.isFlagged());
        }
    }
}

/** Shared Seller fixtures create actors bearing the test prefix in a fresh schema per test. */
abstract class SellerTestFixture extends MySqlFixture {
    static final String PASSWORD = "SellerPass42";
    final AuthService auth = new AuthService();
    final UserRepository users = new UserRepository();
    final ListingRepository listingRows = new ListingRepository();
    final ListingService listings = new ListingService();
    final BuyerListingService browse = new BuyerListingService();
    final BuyerDealService buys = new BuyerDealService();
    final DealService offers = new DealService();
    final ChatService chat = new ChatService();
    final ReviewService reviews = new ReviewService();
    final NotificationRepository notificationRows = new NotificationRepository();
    String prefix;
    User seller, otherSeller, buyer, otherBuyer, admin;

    @BeforeEach
    void createSellerActors() throws SQLException {
        prefix = "s" + UUID.randomUUID().toString().substring(0, 8) + "_";
        seller = createUser("seller", UserRole.SELLER, true);
        otherSeller = createUser("other", UserRole.SELLER, true);
        buyer = createUser("buyer", UserRole.BUYER, true);
        otherBuyer = createUser("buyer2", UserRole.BUYER, true);
        admin = createUser("admin", UserRole.ADMIN, true);
    }

    @AfterEach
    void cleanSellerFixtures() throws SQLException {
        if (prefix == null) return;
        SessionManager.logout();
        try (var connection = DatabaseManager.getConnection()) {
            // The fixture schema is disposable and seeds only users. This also removes
            // fan-out notifications sent to seeded admins without touching their accounts.
            String database = connection.getCatalog();
            assertTrue(database != null && database.matches("test_[a-f0-9]{32}"), "Cleanup requires an isolated test schema");
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement();
                 var actors = connection.prepareStatement("DELETE FROM users WHERE LEFT(username, ?) = ?")) {
                var tables = List.of("chat_messages", "chat_threads", "reviews", "listing_reports",
                        "deals", "listing_photos", "listings", "notifications");
                for (String table : tables) {
                    sql.executeUpdate("DELETE FROM " + table);
                }
                actors.setInt(1, prefix.length());
                actors.setString(2, prefix);
                actors.executeUpdate();
                connection.commit();
                for (String table : tables) {
                    try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1), () -> "Test rows remain in " + table);
                    }
                }
                try (var check = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE LEFT(username, ?) = ?")) {
                    check.setInt(1, prefix.length());
                    check.setString(2, prefix);
                    try (var rows = check.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1), "Test accounts remain");
                    }
                }
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    User createUser(String suffix, UserRole role, boolean active) throws SQLException {
        User actor = new User(prefix + suffix, "Test " + suffix, null, role);
        actor.setActive(active);
        auth.register(actor, PASSWORD);
        return users.findByUsername(actor.getUsername());
    }

    Listing draft(ListingType type, String label) {
        Listing item = new Listing();
        item.setTitle(prefix + label);
        item.setDescription(prefix + "Listing description");
        item.setCategory("Textbooks");
        item.setCondition("Like New");
        item.setMeetupLocation("Library");
        item.setType(type);
        if (type == ListingType.SELL) item.setPrice(42.50);
        if (type == ListingType.LEND) {
            item.setLendingDuration(5);
            item.setLendingUnit(LendingUnit.SECONDS);
        }
        return item;
    }

    Listing createListing(User owner, ListingType type, String label) throws SQLException {
        Listing item = draft(type, label);
        listings.createListing(owner, item);
        return listings.getListing(owner, item.getId());
    }

    Listing live(ListingType type, String label) throws SQLException {
        Listing item = createListing(seller, type, label);
        assertTrue(listingRows.updateReview(item.getId(), ListingStatus.LIVE, null, item.getRowVersion()));
        return listings.getListing(seller, item.getId());
    }

    void setStatus(Listing item, ListingStatus status) throws SQLException {
        try (var connection = DatabaseManager.getConnection();
             var update = connection.prepareStatement("UPDATE listings SET listingStatus = ? WHERE id = ?")) {
            update.setString(1, status.name());
            update.setString(2, item.getId());
            assertEquals(1, update.executeUpdate());
        }
    }

    Deal propose(User actor, Listing item, ListingType type) throws SQLException {
        LocalDateTime start = LocalDateTime.now().plusMinutes(5).withNano(0);
        return buys.submit(actor, item.getId(), type, type == ListingType.SELL ? 35.0 : null,
                type == ListingType.LEND ? start : null, type == ListingType.LEND ? start.plusSeconds(5) : null);
    }

    void complete(Deal deal) throws SQLException {
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        buys.complete(buyer, deal.getId(), buys.getMyDeal(buyer, deal.getId()).getRowVersion());
    }

    Review reviewedSale(String label) throws SQLException {
        Deal deal = propose(buyer, live(ListingType.SELL, label), ListingType.SELL);
        complete(deal);
        reviews.submitReview(buyer, deal.getId(), 4, prefix + "Review for " + label);
        return reviews.getSellerReviews(seller).stream()
                .filter(review -> review.getListingTitle().equals(prefix + label)).findFirst().orElseThrow();
    }

    List<Notification> inbox(User actor) throws SQLException {
        return new NotificationService(users, notificationRows, actor.getRole()).getNotifications(actor, true);
    }

    void assertNotice(User actor, String title, String listingTitle) throws SQLException {
        assertTrue(inbox(actor).stream().anyMatch(n -> title.equals(n.getTitle()) && n.getMessage().contains(listingTitle)),
                () -> "Missing notification: " + title + " for " + listingTitle);
    }
}
