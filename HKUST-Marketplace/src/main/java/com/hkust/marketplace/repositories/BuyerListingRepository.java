package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.LendingUnit;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.utils.DatabaseManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Buyer-only queries; none of these methods edit a listing or its photos. */
public class BuyerListingRepository {
    /**
     * Supplies connections for buyer listing queries and reports.
     *
     * <p>Each call must return a usable MySQL connection; the caller owns its lifecycle.
     */
    @FunctionalInterface
    public interface ConnectionProvider {
        /**
         * Returns a connection for one buyer-listing operation.
         *
         * @return an open connection that the caller closes
         * @throws SQLException when a connection cannot be obtained
         */
        Connection getConnection() throws SQLException;
    }

    private static final String VISIBLE_STATUSES = "('LIVE', 'RESERVED', 'SOLD', 'LENT', 'GIVEN')";
    private final ConnectionProvider connections;

    /**
     * Creates a repository backed by {@link DatabaseManager#getConnection()}.
     */
    public BuyerListingRepository() { this(DatabaseManager::getConnection); }

    /**
     * Creates a repository using the supplied connection provider.
     *
     * @param connections the provider used for every buyer query; must not be {@code null}
     * @throws NullPointerException when {@code connections} is {@code null}
     */
    public BuyerListingRepository(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /**
     * Returns every live listing card, newest first.
     *
     * <p>Only {@code LIVE} listings owned by an active {@code SELLER} are included. Each result
     * carries just the first saved photo as a thumbnail, not the full gallery. Uses one
     * connection from the provider.
     *
     * @return the live listing cards; never {@code null}
     * @throws SQLException when the query fails
     */
    public List<Listing> findLiveListings() throws SQLException {
        // A card needs only the first saved photo; do not load all six images per listing.
        String sql = """
                SELECT l.*, u.full_name AS seller_full_name, p.photo_data AS thumbnail
                FROM listings l
                JOIN users u ON u.username = l.seller_username
                LEFT JOIN listing_photos p ON p.listing_id = l.id AND p.photo_position = (
                    SELECT MIN(first_photo.photo_position) FROM listing_photos first_photo
                    WHERE first_photo.listing_id = l.id)
                WHERE l.listingStatus = 'LIVE' AND u.active = TRUE AND u.role = 'SELLER'
                ORDER BY l.posted_at DESC, l.id ASC
                """;
        List<Listing> result = new ArrayList<>();
        try (Connection connection = connections.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Listing listing = mapRow(rows);
                byte[] thumbnail = rows.getBytes("thumbnail");
                if (thumbnail != null) listing.getPhotos().add(thumbnail);
                result.add(listing);
            }
        }
        return result;
    }

    /**
     * Returns a visible listing without a viewer and counts one view.
     *
     * <p>Equivalent to {@code findVisibleById(id, null)}.
     *
     * @param id the listing ID
     * @return the listing with its photos, or {@code null} when it is not visible
     * @throws SQLException when any statement fails; the transaction is rolled back
     * @see #findVisibleById(String, String)
     */
    public Listing findVisibleById(String id) throws SQLException {
        return findVisibleById(id, null);
    }

    /**
     * Returns a buyer-visible listing, counts one view atomically, and loads up to six photos.
     *
     * <p>Visible statuses are {@code LIVE}, {@code RESERVED}, {@code SOLD}, {@code LENT} and
     * {@code GIVEN}, and the seller must be an active {@code SELLER}. A guarded view-count update
     * runs first, so a missing or no-longer-visible listing returns {@code null} without loading
     * photos. The work runs in a repeatable-read transaction owned and committed by this method.
     * {@code row_version} is deliberately not changed, so views never invalidate a seller's
     * optimistic-lock check. Agreed deal terms are attached only when {@code viewer} is the
     * buyer on the accepted/completed deal.
     *
     * @param id the listing ID
     * @param viewer the buyer username used to attach agreed terms, or {@code null} for none
     * @return the listing with its photos, or {@code null} when it is not visible
     * @throws SQLException when any statement fails; the transaction is rolled back
     */
    public Listing findVisibleById(String id, String viewer) throws SQLException {
        // The guarded update counts every successful buyer detail opening, including
        // repeated openings by the same buyer. It does not bump row_version: views
        // must not cause a seller's content edit to fail its optimistic-lock check.
        String countView = "UPDATE listings l JOIN users u ON u.username = l.seller_username "
                + "SET l.view_count = l.view_count + 1 "
                + "WHERE l.id = ? AND l.listingStatus IN " + VISIBLE_STATUSES
                + " AND u.active = TRUE AND u.role = 'SELLER'";
        String sql = ListingRepository.LISTINGS_WITH_DEAL + " AND d.buyer_username = ? "
                + "WHERE l.id = ? AND l.listingStatus IN " + VISIBLE_STATUSES
                + " AND u.active = TRUE AND u.role = 'SELLER'";
        try (Connection connection = connections.getConnection()) {
            // Keep fields/status and the gallery from the same database snapshot.
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(countView)) {
                    statement.setString(1, id);
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return null;
                    }
                }
                Listing listing;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, viewer);
                    statement.setString(2, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return null;
                        }
                        listing = ListingRepository.mapSellerListing(rows);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT photo_data FROM listing_photos WHERE listing_id = ? ORDER BY photo_position LIMIT 6")) {
                    statement.setString(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) listing.getPhotos().add(rows.getBytes("photo_data"));
                    }
                }
                connection.commit();
                return listing;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    /**
     * Records a buyer's report against a visible listing and returns the reported title.
     *
     * <p>Uses its own transaction. The buyer row is checked with {@code FOR SHARE} and must be
     * an active {@code BUYER}; the visible listing row is then locked with {@code FOR SHARE} and
     * must have an active {@code SELLER}. A report row is inserted and committed. No duplicate
     * check is performed, so the same buyer may report a listing more than once, and no extra
     * view is counted.
     *
     * @param buyer the reporting buyer's username
     * @param listingId the listing being reported
     * @param reason the free-text reason stored with the report as supplied
     * @return the listing title captured at report time
     * @throws IllegalArgumentException when the buyer is not an active buyer or the listing is
     *         not visible to buyers
     * @throws SQLException when any statement fails; the transaction is rolled back
     */
    public String reportListing(String buyer, String listingId, String reason) throws SQLException {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT username FROM users WHERE username = ? AND role = 'BUYER' AND active = TRUE FOR SHARE")) {
                    statement.setString(1, buyer);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("An active buyer account is required to report a listing.");
                    }
                }
                String title;
                String seller;
                String sql = "SELECT l.itemName, l.seller_username FROM listings l "
                        + "JOIN users u ON u.username = l.seller_username "
                        + "WHERE l.id = ? AND l.listingStatus IN " + VISIBLE_STATUSES
                        + " AND u.active = TRUE AND u.role = 'SELLER' FOR SHARE";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, listingId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("This listing is no longer available to report.");
                        title = rows.getString("itemName");
                        seller = rows.getString("seller_username");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO listing_reports (id, listing_id, listing_title, seller_username, buyer_username, reason)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, listingId);
                    statement.setString(3, title);
                    statement.setString(4, seller);
                    statement.setString(5, buyer);
                    statement.setString(6, reason);
                    statement.executeUpdate();
                }
                connection.commit();
                return title;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private static void rollback(Connection connection, Exception failure) {
        try { connection.rollback(); }
        catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }

    // Follow the existing ListingRepository mapping without changing seller/admin queries.
    private Listing mapRow(ResultSet rows) throws SQLException {
        Listing listing = new Listing();
        listing.setId(rows.getString("id"));
        listing.setSellerUsername(rows.getString("seller_username"));
        listing.setSellerFullName(rows.getString("seller_full_name"));
        listing.setTitle(rows.getString("itemName"));
        listing.setCategory(rows.getString("category"));
        listing.setType(ListingType.valueOf(rows.getString("listingType")));
        listing.setStatus(ListingStatus.valueOf(rows.getString("listingStatus")));
        BigDecimal price = rows.getBigDecimal("price");
        listing.setPrice(price == null ? null : price.doubleValue());
        listing.setDescription(rows.getString("description"));
        listing.setCondition(switch (rows.getString("item_condition")) {
            case "NEW" -> "New";
            case "LIKE_NEW" -> "Like New";
            case "USED" -> "Used";
            case "FOR_PARTS" -> "For Parts";
            default -> throw new SQLException("Unknown listing condition.");
        });
        int duration = rows.getInt("lending_duration");
        listing.setLendingDuration(rows.wasNull() ? null : duration);
        String unit = rows.getString("lending_unit");
        listing.setLendingUnit(unit == null ? null : LendingUnit.valueOf(unit));
        listing.setMeetupLocation(rows.getString("meetup_location"));
        Timestamp posted = rows.getTimestamp("posted_at");
        listing.setPostedAt(posted == null ? null : posted.toLocalDateTime());
        Timestamp reviewed = rows.getTimestamp("reviewed_at");
        listing.setReviewedAt(reviewed == null ? null : reviewed.toLocalDateTime());
        listing.setViewCount(rows.getInt("view_count"));
        listing.setRowVersion(rows.getLong("row_version"));
        return listing;
    }
}
