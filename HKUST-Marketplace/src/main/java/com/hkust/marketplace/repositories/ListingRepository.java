package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.LendingUnit;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ListingRepository {
    // Acceptance copies the agreed counter into offeredPrice / borrowStartDate / borrowDueDate.
    static final String LISTINGS_WITH_DEAL = """
            SELECT l.*, u.full_name AS seller_full_name, d.id AS agreed_deal,
                   d.offeredPrice, d.borrowStartDate, d.borrowDueDate
            FROM listings l JOIN users u ON u.username = l.seller_username
            LEFT JOIN deals d ON d.id = (
                SELECT a.id FROM deals a WHERE a.listing_id = l.id
                  AND ((l.listingStatus IN ('RESERVED','LENT') AND a.status = 'ACCEPTED')
                    OR (l.listingStatus IN ('SOLD','GIVEN') AND a.status = 'COMPLETED'))
                ORDER BY a.completedDate DESC, a.createdDate DESC, a.id LIMIT 1
            )
            """;

    static Listing mapSellerListing(ResultSet rows) throws SQLException {
        Listing listing = mapRow(rows);
        if (rows.getString("agreed_deal") != null) {
            listing.setAgreedTerms(new Listing.AgreedTerms(rows.getBigDecimal("offeredPrice"),
                    rows.getObject("borrowStartDate", LocalDateTime.class),
                    rows.getObject("borrowDueDate", LocalDateTime.class)));
        }
        return listing;
    }

    /** Reserve a LIVE listing as part of the same transaction that accepts its deal. */
    public boolean reserveForDeal(Connection connection, String listingId, String sellerUsername) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw new IllegalArgumentException("Reserving a listing requires a transaction.");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE listings SET listingStatus = 'RESERVED', row_version = row_version + 1
                WHERE id = ? AND seller_username = ? AND listingStatus = 'LIVE'
                """)) {
            statement.setString(1, listingId);
            statement.setString(2, sellerUsername);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Save a new listing and its 0-6 images together.
     * The service supplies the ID, seller, validated fields, and Pending Review status.
     * Both tables are rolled back if saving fails.
     */
    public void create(Listing listing) throws SQLException {
        //check that the service has assigned an ID before saving
        if (listing == null || listing.getId() == null || listing.getId().isBlank()) {
            throw new IllegalArgumentException("A listing ID is required before saving.");
        }
        //new listings must wait for admin review
        if (listing.getType() == null || listing.getStatus() != ListingStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("A new listing needs a type and Pending Review status.");
        }
        //convert the form's condition text to the enum value stored in SQL
        String condition = switch (listing.getCondition() == null ? "" : listing.getCondition()) {
            case "New", "NEW" -> "NEW";
            case "Like New", "LIKE_NEW" -> "LIKE_NEW";
            case "Used", "USED" -> "USED";
            case "For Parts", "FOR_PARTS" -> "FOR_PARTS";
            default -> throw new IllegalArgumentException("Choose a valid item condition.");
        };
        //copy the photo list; no selected photos means an empty list
        List<byte[]> photos = listing.getPhotos() == null
                ? new ArrayList<>() : new ArrayList<>(listing.getPhotos());
        if (photos.size() > 6) {
            throw new IllegalArgumentException("A listing can have at most six photos.");
        }
        //check that each image has data and fits in the MEDIUMBLOB column
        for (byte[] photo : photos) {
            if (photo == null || photo.length == 0 || photo.length > Listing.MAX_PHOTO_BYTES) {
                throw new IllegalArgumentException("Each photo must contain image data smaller than 16 MiB.");
            }
        }
        //only Sell listings need a price; use BigDecimal for SQL DECIMAL
        BigDecimal price = null;
        if (listing.getType() == ListingType.SELL) {
            if (listing.getPrice() == null || !Double.isFinite(listing.getPrice()) || listing.getPrice() < 0) {
                throw new IllegalArgumentException("A Sell listing needs a valid nonnegative price.");
            }
            price = BigDecimal.valueOf(listing.getPrice());
        }
        //Lend listings need both a positive duration and its unit
        if (listing.getType() == ListingType.LEND
                && (listing.getLendingDuration() == null || listing.getLendingDuration() <= 0
                    || listing.getLendingUnit() == null)) {
            throw new IllegalArgumentException("A Lend listing needs a positive duration and a unit.");
        }

        String sql = """
                INSERT INTO listings
                    (id, seller_username, itemName, category, listingType, listingStatus,
                     price, description, item_condition, lending_duration, lending_unit, meetup_location,
                     posted_at, reviewed_at, rejection_reason, view_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = DatabaseManager.getConnection()) {
            //save the listing and photos together, rather than committing each insert
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    //fill the query placeholders with the listing's values
                    stmt.setString(1, listing.getId());
                    stmt.setString(2, listing.getSellerUsername());
                    stmt.setString(3, listing.getTitle());
                    stmt.setString(4, listing.getCategory());
                    stmt.setString(5, listing.getType().name());
                    stmt.setString(6, listing.getStatus().name());
                    //store SQL NULL for the price when the listing is not for sale
                    if (price == null) stmt.setNull(7, Types.DECIMAL);
                    else stmt.setBigDecimal(7, price);
                    stmt.setString(8, listing.getDescription());
                    stmt.setString(9, condition);
                    //only save a lending duration and unit for Lend listings
                    if (listing.getType() == ListingType.LEND) {
                        stmt.setInt(10, listing.getLendingDuration());
                        stmt.setString(11, listing.getLendingUnit().name());
                    } else {
                        stmt.setNull(10, Types.INTEGER);
                        stmt.setNull(11, Types.VARCHAR);
                    }
                    stmt.setString(12, listing.getMeetupLocation());
                    //keep the original posting time when this listing is loaded again
                    LocalDateTime postedAt = listing.getPostedAt() == null
                            ? LocalDateTime.now() : listing.getPostedAt();
                    stmt.setTimestamp(13, Timestamp.valueOf(postedAt));
                    //review details can stay empty until admin reviews the listing
                    if (listing.getReviewedAt() == null) stmt.setNull(14, Types.TIMESTAMP);
                    else stmt.setTimestamp(14, Timestamp.valueOf(listing.getReviewedAt()));
                    stmt.setString(15, listing.getRejectionReason());
                    stmt.setInt(16, listing.getViewCount());
                    stmt.executeUpdate();
                }
                //use the same listing ID and connection for its photo rows
                insertPhotos(conn, listing.getId(), photos);
                //confirm both tables only after every insert succeeds
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                //undo this transaction if the listing or any photo fails to save
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    //keep the original error and attach the rollback error as well
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }
    public List<Listing> findAllListing() throws SQLException {
        List<Listing> listings = new ArrayList<>();
        //read the seller's current name from users and listing
        String sql = """
                SELECT l.*, u.full_name AS seller_full_name
                FROM listings l
                JOIN users u ON u.username = l.seller_username
                WHERE l.listingStatus <> 'REMOVED'
                ORDER BY l.posted_at ASC, l.id ASC
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    listings.add(mapRow(rs));
                }
            }
            //load each listing's photos using the same connection
            for (Listing listing : listings) {
                listing.setPhotos(findPhotos(conn, listing.getId()));
            }
        }
        return listings;
    }
    public List<Listing> findBySeller(String sellerUsername) throws SQLException {
        List<Listing> listings = new ArrayList<>();
        //read the seller's current name from users instead of storing a second copy
        String sql = LISTINGS_WITH_DEAL + """
                WHERE l.seller_username = ?
                  AND l.listingStatus <> 'REMOVED'
                ORDER BY l.posted_at DESC, l.id DESC
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
             stmt.setString(1, sellerUsername);
             //allow SQL to identify the ? as java object
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Listing listing = mapSellerListing(rs);
                    listings.add(listing);
                }
            }
            //load photos after reading the listing rows, using the same connection
            for (Listing listing : listings) {
                listing.setPhotos(findPhotos(conn, listing.getId()));
            }
        }
        return listings;
    }

    /**
     * Returns every listing with the given status, oldest first.
     * Used by the admin review screen with {@link ListingStatus#PENDING_REVIEW},
     * and reusable for other status filters (e.g. an "all live listings" view).
     * Removed listings are never returned, even when asking for
     * {@link ListingStatus#REMOVED}.
     */
    public List<Listing> findByStatus(ListingStatus status) throws SQLException {
        if (status == null) {
            throw new IllegalArgumentException("A listing status is required.");
        }
        List<Listing> listings = new ArrayList<>();
        //read the seller's current name from users instead of storing a second copy
        String sql = """
                SELECT l.*, u.full_name AS seller_full_name
                FROM listings l
                JOIN users u ON u.username = l.seller_username
                WHERE l.listingStatus = ?
                  AND l.listingStatus <> 'REMOVED'
                ORDER BY l.posted_at ASC, l.id ASC
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
             stmt.setString(1, status.name());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    listings.add(mapRow(rs));
                }
            }
            //load each listing's photos using the same connection
            for (Listing listing : listings) {
                listing.setPhotos(findPhotos(conn, listing.getId()));
            }
        }
        return listings;
    }

    public Listing findByIdAndSeller(String id, String sellerUsername) throws SQLException {
        String sql = LISTINGS_WITH_DEAL + "WHERE l.id = ? AND l.seller_username = ?"
                + " AND l.listingStatus <> 'REMOVED'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, sellerUsername);
            Listing listing;
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                listing = mapSellerListing(rs);
            }
            listing.setPhotos(findPhotos(conn, id));
            return listing;
        }
    }

    /** Lock an owned row for a caller-owned seller change transaction. */
    public Listing findByIdAndSellerForUpdate(Connection conn, String id, String seller) throws SQLException {
        if (conn == null || conn.getAutoCommit()) throw new IllegalArgumentException("A transaction is required.");
        String sql = "SELECT l.*, u.full_name AS seller_full_name FROM listings l "
                + "JOIN users u ON u.username = l.seller_username "
                + "WHERE l.id = ? AND l.seller_username = ? AND l.listingStatus <> 'REMOVED' FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, seller);
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next() ? mapRow(rows) : null;
            }
        }
    }

    /**
     * Records an admin review decision. Only listingStatus, reviewed_at and
     * rejection_reason are written; every other column (content, seller, photos) is untouched.
     * Approving with {@link ListingStatus#LIVE} clears any old rejection reason; rejecting
     * requires a non-blank reason (the table CHECK enforces the same rule).
     *
     * The update only succeeds while the listing is still PENDING_REVIEW and its
     * row_version still equals the version the admin reviewed, so a seller edit or a
     * second admin decision between loading the queue and deciding is detected instead
     * of approving content nobody reviewed.
     *
     * @param expectedRowVersion row_version the admin saw when the listing was loaded
     * @return true when the listing was still pending with that version and was updated
     */
    public boolean updateReview(String listingId, ListingStatus status, String rejectionReason,
                                long expectedRowVersion) throws SQLException {
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("A listing ID is required.");
        }
        if (status == null) {
            throw new IllegalArgumentException("A review status is required.");
        }
        String reason = rejectionReason == null ? null : rejectionReason.trim();
        if (status == ListingStatus.REJECTED) {
            //fail early with a readable message instead of hitting the CHECK constraint
            if (reason == null || reason.isEmpty()) {
                throw new IllegalArgumentException("A rejection reason is required.");
            }
        } else {
            //approval (and any other decision) must not keep a stale rejection reason
            reason = null;
        }

        String sql = """
                UPDATE listings
                SET listingStatus = ?, reviewed_at = ?, rejection_reason = ?,
                    row_version = row_version + 1
                WHERE id = ? AND listingStatus = 'PENDING_REVIEW' AND row_version = ?
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            if (reason == null) stmt.setNull(3, Types.VARCHAR);
            else stmt.setString(3, reason);
            stmt.setString(4, listingId);
            stmt.setLong(5, expectedRowVersion);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Soft delete: marks one owned listing as REMOVED, keeping its row, photos and history.
     * Removed listings are excluded from every find method in this repository.
     *
     * @return true when the listing existed, was not already removed and was updated
     */
    public boolean removeListing(String listingId, String username, String reason) throws SQLException {
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("A listing ID is required.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A seller username is required.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required.");
        }
        String sql = """
                UPDATE listings
                SET listingStatus = 'REMOVED', rejection_reason = ?,
                    row_version = row_version + 1
                WHERE id = ? AND seller_username = ? AND listingStatus <> 'REMOVED'
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1,reason);
            stmt.setString(2, listingId);
            stmt.setString(3, username.trim());
            return stmt.executeUpdate() > 0;
        }
    }


    //lock the owned row so a concurrent status change cannot slip past the check
    private void lockChangeableListing(Connection conn, String id, String seller) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT listingStatus FROM listings WHERE id = ? AND seller_username = ? FOR UPDATE")) {
            stmt.setString(1, id);
            stmt.setString(2, seller);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Listing not found or no longer available to your account.");
                String status = rs.getString(1);
                if (!List.of("PENDING_REVIEW", "LIVE", "REJECTED").contains(status)) {
                    throw new IllegalArgumentException("This listing's status no longer allows editing or deletion.");
                }
            }
        }
    }

    public void updateOwned(String id, String seller, Listing listing) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                updateOwned(conn, id, seller, listing);
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    /** Content and photo replacement without committing the caller's transaction. */
    public void updateOwned(Connection conn, String id, String seller, Listing listing) throws SQLException {
        if (conn == null || conn.getAutoCommit()) throw new IllegalArgumentException("A transaction is required.");
        String sql = """
                UPDATE listings SET itemName = ?, category = ?, listingType = ?, price = ?,
                    description = ?, item_condition = ?, lending_duration = ?, lending_unit = ?,
                    meetup_location = ?, listingStatus = 'PENDING_REVIEW', reviewed_at = NULL,
                    rejection_reason = NULL, row_version = row_version + 1
                WHERE id = ? AND seller_username = ?
                """;
        lockChangeableListing(conn, id, seller);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, listing.getTitle());
            stmt.setString(2, listing.getCategory());
            stmt.setString(3, listing.getType().name());
            if (listing.getPrice() == null) stmt.setNull(4, Types.DECIMAL);
            else stmt.setBigDecimal(4, BigDecimal.valueOf(listing.getPrice()));
            stmt.setString(5, listing.getDescription());
            stmt.setString(6, listing.getCondition().toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
            if (listing.getLendingDuration() == null) stmt.setNull(7, Types.INTEGER);
            else stmt.setInt(7, listing.getLendingDuration());
            stmt.setString(8, listing.getLendingUnit() == null ? null : listing.getLendingUnit().name());
            stmt.setString(9, listing.getMeetupLocation());
            stmt.setString(10, id);
            stmt.setString(11, seller);
            if (stmt.executeUpdate() != 1) throw new SQLException("The listing could not be updated.");
        }
        // Replace photos in the caller's transaction, including when all were removed.
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM listing_photos WHERE listing_id = ?")) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        }
        insertPhotos(conn, id, listing.getPhotos() == null ? List.of() : listing.getPhotos());
    }

    /**
     * Admin content edit: updates every listing field and replaces the photo set.
     * Unlike the seller path it accepts any current status, and the listing is sent
     * back to Pending Review with its previous review result cleared.
     *
     * @return false when no listing has that id
     */
    public boolean updateAsAdmin(String id, Listing listing) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!updateAsAdmin(conn, id, listing)) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    /**
     * The same admin content update using the caller's transaction, so related writes
     * (notifications, deal changes) commit or roll back together. The caller owns
     * commit/rollback. Returns false without writing when no listing has that id.
     */
    public boolean updateAsAdmin(Connection conn, String id, Listing listing) throws SQLException {
        String sql = """
                UPDATE listings SET itemName = ?, category = ?, listingType = ?, price = ?,
                    description = ?, item_condition = ?, lending_duration = ?, lending_unit = ?,
                    meetup_location = ?, listingStatus = 'PENDING_REVIEW',
                    reviewed_at = NULL, rejection_reason = NULL, row_version = row_version + 1
                WHERE id = ?
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, listing.getTitle());
            stmt.setString(2, listing.getCategory());
            stmt.setString(3, listing.getType().name());
            if (listing.getPrice() == null) stmt.setNull(4, Types.DECIMAL);
            else stmt.setBigDecimal(4, BigDecimal.valueOf(listing.getPrice()));
            stmt.setString(5, listing.getDescription());
            stmt.setString(6, listing.getCondition().toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
            if (listing.getLendingDuration() == null) stmt.setNull(7, Types.INTEGER);
            else stmt.setInt(7, listing.getLendingDuration());
            stmt.setString(8, listing.getLendingUnit() == null ? null : listing.getLendingUnit().name());
            stmt.setString(9, listing.getMeetupLocation());
            stmt.setString(10, id);
            if (stmt.executeUpdate() != 1) {
                return false;
            }
        }
        //replace the photo set in the same transaction, including when all were removed
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM listing_photos WHERE listing_id = ?")) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        }
        insertPhotos(conn, id, listing.getPhotos() == null ? List.of() : listing.getPhotos());
        return true;
    }

    public void deleteOwned(String id, String seller) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                deleteOwned(conn, id, seller);
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    /** Remove from marketplace while retaining deal history and its referenced listing. */
    public void deleteOwned(Connection conn, String id, String seller) throws SQLException {
        if (conn == null || conn.getAutoCommit()) throw new IllegalArgumentException("A transaction is required.");
        lockChangeableListing(conn, id, seller);
        try (PreparedStatement stmt = conn.prepareStatement("""
                UPDATE listings SET listingStatus = 'REMOVED', row_version = row_version + 1
                WHERE id = ? AND seller_username = ?
                """)) {
            stmt.setString(1, id);
            stmt.setString(2, seller);
            if (stmt.executeUpdate() != 1) throw new SQLException("The listing could not be removed.");
        }
    }

    //turn a database row into a Listing, like the user repository
    private static Listing mapRow(ResultSet rs) throws SQLException {
        Listing listing = new Listing();
        listing.setId(rs.getString("id"));
        listing.setSellerUsername(rs.getString("seller_username"));
        listing.setSellerFullName(rs.getString("seller_full_name"));
        listing.setTitle(rs.getString("itemName"));
        listing.setCategory(rs.getString("category"));
        listing.setType(ListingType.valueOf(rs.getString("listingType")));
        listing.setStatus(ListingStatus.valueOf(rs.getString("listingStatus")));
        BigDecimal price = rs.getBigDecimal("price");
        listing.setPrice(price == null ? null : price.doubleValue());
        listing.setDescription(rs.getString("description"));
        listing.setCondition(switch (rs.getString("item_condition")) {
            case "NEW" -> "New";
            case "LIKE_NEW" -> "Like New";
            case "USED" -> "Used";
            case "FOR_PARTS" -> "For Parts";
            default -> throw new SQLException("Unknown listing condition.");
        });
        int duration = rs.getInt("lending_duration");
        listing.setLendingDuration(rs.wasNull() ? null : duration);
        String unit = rs.getString("lending_unit");
        listing.setLendingUnit(unit == null ? null : LendingUnit.valueOf(unit));
        listing.setMeetupLocation(rs.getString("meetup_location"));
        Timestamp postedAt = rs.getTimestamp("posted_at");
        listing.setPostedAt(postedAt == null ? null : postedAt.toLocalDateTime());
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        listing.setReviewedAt(reviewedAt == null ? null : reviewedAt.toLocalDateTime());
        listing.setRejectionReason(rs.getString("rejection_reason"));
        listing.setViewCount(rs.getInt("view_count"));
        listing.setRowVersion(rs.getLong("row_version"));
        return listing;
    }

    //save the image bytes in order, using the listing's transaction
    private void insertPhotos(Connection conn, String listingId, List<byte[]> photos)
            throws SQLException {
        if (photos.isEmpty()) return;
        String sql = """
                INSERT INTO listing_photos (listing_id, photo_position, photo_data)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < photos.size(); i++) {
                stmt.setString(1, listingId);
                stmt.setInt(2, i + 1);
                stmt.setBytes(3, photos.get(i));
                stmt.executeUpdate(); //send one image at a time instead of batching six large images
            }
        }
    }

    //get this listing's photos in the order they should be displayed
    private List<byte[]> findPhotos(Connection conn, String listingId) throws SQLException {
        List<byte[]> photos = new ArrayList<>();
        String sql = "SELECT photo_data FROM listing_photos WHERE listing_id = ? ORDER BY photo_position";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, listingId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    photos.add(rs.getBytes("photo_data"));
                }
            }
        }
        return photos; //an empty list means this listing has no photos
    }
}
