package com.hkust.marketplace.services;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.BuyerListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;


import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Buyer reads and reports are separate from seller/admin listing management. */
public class BuyerListingService {
    /**
     * Supported buyer catalogue orderings.
     *
     * <p>{@link #NEWEST} orders by posting time; the price orders compare practical sale
     * prices with missing prices sorted last.
     */
    public enum SortOrder {
        NEWEST("Newest"), PRICE_ASC("Price: low to high"), PRICE_DESC("Price: high to low");

        private final String label;
        SortOrder(String label) { this.label = label; }

        /**
         * Returns the display label used by the sort control.
         *
         * @return the human-readable label
         */
        @Override public String toString() { return label; }
    }

    private final UserRepository users;
    private final BuyerListingRepository listings;
    private final NotificationRepository notificationRepository;

    /** Creates a service backed by the default repositories. */
    public BuyerListingService() {
        this(new UserRepository(), new BuyerListingRepository(), new NotificationRepository());
    }

    /**
     * Creates a service with explicit repositories.
     *
     * @param users the account repository used for active-buyer checks
     * @param listings the buyer listing repository
     * @param notificationRepository the repository used for report notifications
     * @throws NullPointerException if any argument is {@code null}
     */
    public BuyerListingService(UserRepository users, BuyerListingRepository listings, NotificationRepository notificationRepository) {
        this.users = Objects.requireNonNull(users);
        this.listings = Objects.requireNonNull(listings);
        this.notificationRepository = Objects.requireNonNull(notificationRepository);
    }

    /**
     * Returns the catalogue of live listings from active sellers.
     *
     * <p>Only the first saved photo is loaded for each card.
     *
     * @param user the signed-in account; it must be an active buyer
     * @return the live listings, newest first
     * @throws IllegalArgumentException if the stored account is missing, inactive, or not
     *     a buyer
     * @throws SQLException if the account or listing query fails
     */
    public List<Listing> getLiveListings(User user) throws SQLException {
        requireActiveBuyer(user);
        return listings.findLiveListings();
    }

    /**
     * Reads a fresh listing for the buyer detail view and records a view.
     *
     * <p>Listings are available while their status is Live, Reserved, Sold, Lent, or Given
     * and the seller is still an active seller, so approved listings that have become
     * unavailable remain viewable. The agreed deal terms are attached only when the
     * requesting buyer is the buyer of that deal. The view counter is incremented without
     * changing the row version.
     *
     * @param user the signed-in buyer
     * @param id the listing identifier
     * @return the visible listing including its photos
     * @throws IllegalArgumentException if the account is not an active buyer, the id is
     *     blank, or the listing is not visible to this buyer
     * @throws SQLException if the account or listing query fails
     */
    public Listing getListing(User user, String id) throws SQLException {
        User buyer = requireActiveBuyer(user);
        requireListingId(id);
        Listing listing = listings.findVisibleById(id, buyer.getUsername());
        if (listing == null) {
            throw new IllegalArgumentException("This listing is no longer available to view.");
        }
        return listing;
    }

    /**
     * Reports a visible listing to the administrators.
     *
     * <p>The reason is stripped and must contain 1 to 2,000 characters. The repository
     * re-checks the active buyer and the visible listing under database locks and stores
     * the report. Every active admin then receives a "Listing Reported" notification.
     *
     * @param user the signed-in buyer
     * @param id the listing identifier
     * @param reason the report reason
     * @throws IllegalArgumentException if the account is not an active buyer, the id is
     *     blank, the reason length is invalid, or the listing is no longer available to
     *     report
     * @throws SQLException if the report insert or the admin notification fails
     */
    public void reportListing(User user, String id, String reason) throws SQLException {
        User buyer = requireActiveBuyer(user);
        requireListingId(id);
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 2000) {
            throw new IllegalArgumentException("Please enter a report reason of 1 to 2,000 characters.");
        }
        // The repository rechecks the buyer and listing while holding database locks.
        String listingTitle = listings.reportListing(buyer.getUsername(), id, trimmed);
        notifyAdminsOfReportListing(listingTitle, buyer.getUsername(), buyer.getUsername(),
                "Listing Reported", trimmed);
    }

    /**
     * Filters and sorts a loaded listing list without mutating its objects.
     *
     * <p>Only live listings are kept. Keyword matching is case-insensitive against the
     * title and description; category and condition are exact case-insensitive matches;
     * type and price bounds are optional. Non-sale listings contribute a price of zero for
     * price sorting and are excluded when a price bound is supplied. Newest-first sorting
     * uses the posting time and then the id.
     *
     * @param source the listings to filter; must not be {@code null}
     * @param keyword optional title/description keyword
     * @param category optional exact category
     * @param type optional listing type
     * @param condition optional exact condition
     * @param min optional inclusive minimum sale price
     * @param max optional inclusive maximum sale price
     * @param sort the ordering; {@code null} means newest first
     * @return the matching listings in the requested order
     * @throws IllegalArgumentException if a bound is negative, above
     *     HK$99,999,999.99, has more than two decimal places, or {@code min} exceeds
     *     {@code max}
     * @throws NullPointerException if {@code source} is {@code null}
     */
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
                : Comparator.comparing(BuyerListingService::sortPrice,
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

    //listings without a sale price sort as zero, including lending and giveaways
    private static BigDecimal sortPrice(Listing listing) {
        BigDecimal price = salePrice(listing);
        return price == null ? BigDecimal.ZERO : price;
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

    private void notifyAdminsOfReportListing(String listingTitle, String buyerUsername,
                                              String actorUsername, String notificationTitle, String reason)
            throws SQLException {
        String message = "Listing '" + listingTitle + "' is reported by @" + buyerUsername + System.lineSeparator() +
                "Reason:" + reason;

        for (User admin : users.findAll()) {
            if (admin.getRole() != UserRole.ADMIN || !admin.isActive()) continue;
            if (actorUsername != null && admin.getUsername().equalsIgnoreCase(actorUsername)) continue;
            notificationRepository.create(new Notification(
                    admin.getUsername(), notificationTitle, message, "Listing Report"));
        }
    }
}
