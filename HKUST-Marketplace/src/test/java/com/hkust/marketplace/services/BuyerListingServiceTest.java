package com.hkust.marketplace.services;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.BuyerListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.hkust.marketplace.services.BuyerListingService.SortOrder.*;
import static org.junit.jupiter.api.Assertions.*;

class BuyerListingServiceTest {
    private final User session = new User("buyer", "Buyer", "unused", UserRole.BUYER);
    private final FakeUsers users = new FakeUsers();
    private final FakeListings listings = new FakeListings();
    private final BuyerListingService service = new BuyerListingService(users, listings, new NotificationRepository());
    private List<Listing> source;

    @BeforeEach void prepare() {
        users.stored = new User("buyer", "Buyer", "unused", UserRole.BUYER);
        source = new ArrayList<>(List.of(
                listing("cheap", ListingType.SELL, 10.25, 1),
                listing("high", ListingType.SELL, 90.50, 3),
                listing("loan", ListingType.LEND, null, 4),
                listing("gift", ListingType.GIVEAWAY, null, 2)));
        listings.live = source;
        listings.detail = source.getFirst();
    }

    @Test void newestIsDefaultAndDoesNotReorderSource() {
        assertEquals(List.of("loan", "high", "gift", "cheap"), ids(filter(null, null, null, null, null, null, null)));
        assertEquals(List.of("cheap", "high", "loan", "gift"), ids(source));
    }

    @Test void priceSortTreatsFreeItemsAsZeroAndOrdersPaidItemsBothWays() {
        assertEquals(List.of("loan", "gift", "cheap", "high"), ids(filter(null, null, null, null, null, null, PRICE_ASC)));
        assertEquals(List.of("high", "cheap", "loan", "gift"), ids(filter(null, null, null, null, null, null, PRICE_DESC)));
    }

    @Test void sortTiesUseNewestThenStableId() {
        source.forEach(l -> { l.setPrice(10.0); l.setType(ListingType.SELL); l.setPostedAt(LocalDateTime.of(2026, 1, 1, 12, 0)); });
        assertEquals(List.of("cheap", "gift", "high", "loan"), ids(filter(null, null, null, null, null, null, PRICE_ASC)));
    }

    @Test void searchMatchesTitleAndDescriptionWithoutCaseSensitivity() {
        source.get(0).setTitle("Calculus Textbook");
        source.get(1).setDescription("Includes CALCULUS notes");
        assertEquals(List.of("high", "cheap"), ids(filter(" calculus ", null, null, null, null, null, NEWEST)));
    }

    @Test void searchTreatsSqlWildcardsLiterally() {
        source.getFirst().setDescription("100%_discount");
        assertEquals(List.of("cheap"), ids(filter("%_", null, null, null, null, null, NEWEST)));
    }

    @Test void categoryTypeConditionAndSearchCombine() {
        source.getFirst().setCategory("Books");
        source.getFirst().setCondition("Like New");
        assertEquals(List.of("cheap"), ids(filter("cheap", "books", ListingType.SELL, "like new", null, null, NEWEST)));
        assertTrue(filter("cheap", "books", ListingType.LEND, "like new", null, null, NEWEST).isEmpty());
    }

    @Test void priceRangeIsInclusiveAndOnlyIncludesSaleListings() {
        assertEquals(List.of("cheap"), ids(filter(null, null, null, null, money("10.25"), money("10.25"), NEWEST)));
        assertEquals(List.of("high", "cheap"), ids(filter(null, null, null, null, money("0"), null, NEWEST)));
        assertTrue(filter(null, null, ListingType.LEND, null, money("0"), null, NEWEST).isEmpty());
    }

    @Test void upperBoundAloneWorks() {
        assertEquals(List.of("cheap"), ids(filter(null, null, null, null, null, money("20"), NEWEST)));
    }

    @Test void invalidPriceBoundsFailWithoutSilentlyClamping() {
        for (String value : List.of("-0.01", "100000000", "1.001")) {
            assertThrows(IllegalArgumentException.class, () -> filter(null, null, null, null, money(value), null, NEWEST));
            assertThrows(IllegalArgumentException.class, () -> filter(null, null, null, null, null, money(value), NEWEST));
        }
        assertThrows(IllegalArgumentException.class, () -> filter(null, null, null, null, money("20"), money("10"), NEWEST));
    }

    @Test void blankFiltersBehaveAsNoFilters() {
        assertEquals(4, filter("  ", " ", null, " ", null, null, NEWEST).size());
    }

    @Test void browseLoadsFromRepository() throws Exception {
        assertEquals(ids(source), ids(service.getLiveListings(session)));
        assertEquals(1, listings.browseCalls);
    }

    @Test void storedAccountRoleOverridesStaleSession() {
        users.stored.setRole(UserRole.SELLER);
        assertThrows(IllegalArgumentException.class, () -> service.getLiveListings(session));
        assertThrows(IllegalArgumentException.class, () -> service.getListing(session, "cheap"));
        assertThrows(IllegalArgumentException.class, () -> service.reportListing(session, "cheap", "Scam"));
        assertEquals(0, listings.browseCalls);
        assertEquals(0, listings.detailCalls);
        assertEquals(0, listings.reportCalls);
    }

    @Test void inactiveDeletedOrMissingBuyerCannotReadOrReport() {
        users.stored.setActive(false);
        assertThrows(IllegalArgumentException.class, () -> service.getLiveListings(session));
        users.stored = null;
        assertThrows(IllegalArgumentException.class, () -> service.getListing(session, "cheap"));
        assertThrows(IllegalArgumentException.class, () -> service.getLiveListings(null));
        assertThrows(IllegalArgumentException.class, () -> service.reportListing(null, "cheap", "Scam"));
    }

    @Test void detailsAreReloadedAndRemovedListingIsUnavailable() throws Exception {
        assertSame(listings.detail, service.getListing(session, "cheap"));
        Listing changed = listing("cheap", ListingType.SELL, 12.0, 1);
        changed.setStatus(ListingStatus.SOLD);
        listings.detail = changed;
        assertSame(changed, service.getListing(session, "cheap"));
        listings.detail = null;
        assertThrows(IllegalArgumentException.class, () -> service.getListing(session, "cheap"));
        assertEquals(3, listings.detailCalls);
    }

    @Test void detailRejectsMissingIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> service.getListing(session, null));
        assertThrows(IllegalArgumentException.class, () -> service.getListing(session, " "));
    }

    @Test void reportingUsesStoredBuyerIdentityAndTrimsReason() throws Exception {
        users.stored.setUsername("canonicalBuyer");
        service.reportListing(session, "cheap", "  Misleading description  ");
        assertEquals("canonicalBuyer", listings.reportBuyer);
        assertEquals("cheap", listings.reportId);
        assertEquals("Misleading description", listings.reportReason);
        assertEquals(1, listings.reportCalls);
    }

    @Test void reportRejectsBlankOverlongOrMissingData() {
        for (String reason : List.of("", " \n ", "a".repeat(2001))) {
            assertThrows(IllegalArgumentException.class, () -> service.reportListing(session, "cheap", reason));
        }
        assertThrows(IllegalArgumentException.class, () -> service.reportListing(session, "cheap", null));
        assertThrows(IllegalArgumentException.class, () -> service.reportListing(session, " ", "Scam"));
        assertEquals(0, listings.reportCalls);
    }

    @Test void reportAcceptsUnicodeLimitByCodePoint() throws Exception {
        service.reportListing(session, "cheap", "\uD83D\uDE00".repeat(2000));
        assertEquals(2000, listings.reportReason.codePointCount(0, listings.reportReason.length()));
    }

    @Test void databaseErrorsPropagateInsteadOfAppearingAsEmptyOrSuccess() {
        listings.failure = new SQLException("offline");
        assertThrows(SQLException.class, () -> service.getLiveListings(session));
        assertThrows(SQLException.class, () -> service.getListing(session, "cheap"));
        assertThrows(SQLException.class, () -> service.reportListing(session, "cheap", "Scam"));
    }

    private List<Listing> filter(String keyword, String category, ListingType type, String condition,
                                 BigDecimal min, BigDecimal max, BuyerListingService.SortOrder sort) {
        return service.filterListings(source, keyword, category, type, condition, min, max, sort);
    }
    private static BigDecimal money(String value) { return new BigDecimal(value); }
    private static List<String> ids(List<Listing> data) { return data.stream().map(Listing::getId).toList(); }
    private static Listing listing(String id, ListingType type, Double price, int day) {
        Listing result = new Listing();
        result.setId(id); result.setTitle(id); result.setDescription("An item");
        result.setCategory("Electronics"); result.setCondition("Used"); result.setType(type);
        result.setPrice(price); result.setStatus(ListingStatus.LIVE);
        result.setPostedAt(LocalDateTime.of(2026, 1, day, 12, 0));
        return result;
    }
    private static class FakeUsers extends UserRepository {
        User stored;
        @Override public List<User> findAll() { return List.of(stored); }
        @Override public User findByUsername(String name) { return stored; }
    }
    private static class FakeListings extends BuyerListingRepository {
        List<Listing> live = List.of();
        Listing detail;
        SQLException failure;
        int browseCalls, detailCalls, reportCalls;
        String reportBuyer, reportId, reportReason;
        @Override public List<Listing> findLiveListings() throws SQLException {
            browseCalls++; if (failure != null) throw failure; return live;
        }
        @Override public Listing findVisibleById(String id, String viewer) throws SQLException {
            detailCalls++; if (failure != null) throw failure; return detail;
        }
        @Override public String reportListing(String buyer, String id, String reason) throws SQLException {
            if (failure != null) throw failure;
            reportCalls++; reportBuyer = buyer; reportId = id; reportReason = reason;
            return id;
        }
    }
}
