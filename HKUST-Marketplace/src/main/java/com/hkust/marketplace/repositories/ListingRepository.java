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

    public List<Listing> findBySeller(String sellerUsername) throws SQLException {
        List<Listing> listings = new ArrayList<>();
        //read the seller's current name from users instead of storing a second copy
        String sql = """
                SELECT l.*, u.full_name AS seller_full_name
                FROM listings l
                JOIN users u ON u.username = l.seller_username
                WHERE l.seller_username = ?
                ORDER BY l.posted_at DESC, l.id DESC
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
             stmt.setString(1, sellerUsername);
             //allow SQL to identify the ? as java object
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Listing listing = mapRow(rs);
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

    //turn a database row into a Listing, like the user repository
    private Listing mapRow(ResultSet rs) throws SQLException {
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
