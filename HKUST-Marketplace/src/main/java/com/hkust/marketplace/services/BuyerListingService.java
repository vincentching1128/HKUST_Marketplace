package com.hkust.marketplace.services;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.BuyerListingRepository;
import com.hkust.marketplace.repositories.UserRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Buyer reads and reports are separate from seller/admin listing management. */
public class BuyerListingService {
    public enum SortOrder {
        NEWEST("Newest"), PRICE_ASC("Price: low to high"), PRICE_DESC("Price: high to low");

        private final String label;
        SortOrder(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final UserRepository users;
    private final BuyerListingRepository listings;

    public BuyerListingService() {
        this(new UserRepository(), new BuyerListingRepository());
    }

    public BuyerListingService(UserRepository users, BuyerListingRepository listings) {
        this.users = Objects.requireNonNull(users);
        this.listings = Objects.requireNonNull(listings);
    }

    public List<Listing> getLiveListings(User user) throws SQLException {
        requireActiveBuyer(user);
        return listings.findLiveListings();
    }

    /** Read a fresh listing, including approved listings that have become unavailable. */
    public Listing getListing(User user, String id) throws SQLException {
        requireActiveBuyer(user);
        requireListingId(id);
        Listing listing = listings.findVisibleById(id);
        if (listing == null) {
            throw new IllegalArgumentException("This listing is no longer available to view.");
        }
        return listing;
    }

    public void reportListing(User user, String id, String reason) throws SQLException {
        User buyer = requireActiveBuyer(user);
        requireListingId(id);
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 2000) {
            throw new IllegalArgumentException("Please enter a report reason of 1 to 2,000 characters.");
        }
        // The repository rechecks the buyer and listing while holding database locks.
        listings.reportListing(buyer.getUsername(), id, trimmed);
    }

    /** Apply combined controls without changing the loaded list or its objects. */
    public List<Listing> filterListings(List<Listing> source, String keyword, String category,
                                       ListingType type, String condition, BigDecimal min,
                                       BigDecimal max, SortOrder sort) {
        validateBound(min);
        validateBound(max);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("Minimum price must not exceed maximum price.");
        }
        String query = normalized(keyword);
        String selectedCategory = normalized(category);
        String selectedCondition = normalized(condition);
        Comparator<Listing> newest = Comparator.comparing(Listing::getPostedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Listing::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<BigDecimal> prices = sort == SortOrder.PRICE_DESC
                ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<Listing> order = sort == null || sort == SortOrder.NEWEST ? newest
                : Comparator.comparing(BuyerListingService::salePrice,
                        Comparator.nullsLast(prices)).thenComparing(newest);
        return Objects.requireNonNull(source).stream()
                .filter(listing -> listing.getStatus() == ListingStatus.LIVE)
                .filter(listing -> query.isEmpty() || normalized(listing.getTitle()).contains(query)
                        || normalized(listing.getDescription()).contains(query))
                .filter(listing -> selectedCategory.isEmpty()
                        || normalized(listing.getCategory()).equals(selectedCategory))
                .filter(listing -> type == null || listing.getType() == type)
                .filter(listing -> selectedCondition.isEmpty()
                        || normalized(listing.getCondition()).equals(selectedCondition))
                .filter(listing -> inPriceRange(listing, min, max))
                .sorted(order).toList();
    }

    private User requireActiveBuyer(User user) throws SQLException {
        User current = user == null || user.getUsername() == null || user.getUsername().isBlank()
                ? null : users.findByUsername(user.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.BUYER) {
            throw new IllegalArgumentException("An active buyer account is required to browse listings.");
        }
        return current;
    }

    private static void requireListingId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("A listing ID is required.");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal salePrice(Listing listing) {
        Double price = listing.getPrice();
        return listing.getType() == ListingType.SELL && price != null && Double.isFinite(price)
                ? BigDecimal.valueOf(price) : null;
    }

    private static boolean inPriceRange(Listing listing, BigDecimal min, BigDecimal max) {
        if (min == null && max == null) return true;
        BigDecimal price = salePrice(listing);
        return price != null && (min == null || price.compareTo(min) >= 0)
                && (max == null || price.compareTo(max) <= 0);
    }

    private static void validateBound(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0
                || value.stripTrailingZeros().scale() > 2)) {
            throw new IllegalArgumentException("Price bounds must be between HK$0 and HK$99,999,999.99 with at most two decimal places.");
        }
    }
}
