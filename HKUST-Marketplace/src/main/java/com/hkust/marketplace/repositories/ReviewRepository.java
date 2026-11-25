package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ReviewRepository {
    private static final String SELECT_REVIEWS = """
            SELECT r.id, d.seller_username, d.buyer_username, l.itemName, r.rating,
                   r.comment, r.seller_reply, r.flagged, r.hidden, r.created_at
            FROM reviews r
            JOIN deals d ON d.id = r.deal_id
            JOIN listings l ON l.id = d.listing_id
            """;
    private static final String NEWEST_FIRST = " ORDER BY r.created_at DESC, r.id";

    /** All reviews, newest first (includes hidden/flagged rows for the admin moderation screen). */
    public List<Review> findAll() throws SQLException {
        return query("", List.of());
    }

    /** Reviews with exactly the supplied rating; ratings are constrained to 1..5. */
    public List<Review> findByRating(int rating) throws SQLException {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5.");
        }
        return query("WHERE r.rating = ?" + NEWEST_FIRST, List.of(rating));
    }

    /**
     * Admin search: literal keyword in the seller username (case follows the database
     * collation) plus an inclusive created-date range. Null keyword/dates are ignored.
     */
    public List<Review> searchReviews(String keyword, LocalDate from, LocalDate to) throws SQLException {
        return searchReviews(keyword, null, from, to);
    }

    /** Same search with an optional exact rating (1..5) filter, for the ratings screen. */
    public List<Review> searchReviews(String keyword, Integer rating, LocalDate from, LocalDate to)
            throws SQLException {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("Rating must be between 1 and 5.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Start date must not be after end date.");
        }
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND LOCATE(?, d.seller_username) > 0");
            parameters.add(keyword.strip());
        }
        if (rating != null) {
            where.append(" AND r.rating = ?");
            parameters.add(rating);
        }
        if (from != null) {
            where.append(" AND r.created_at >= ?");
            parameters.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            where.append(" AND DATE(r.created_at) <= ?");
            parameters.add(java.sql.Date.valueOf(to));
        }
        where.append(NEWEST_FIRST);
        return query(where.toString(), parameters);
    }

    private List<Review> query(String suffix, List<?> parameters) throws SQLException {
        List<Review> reviews = new ArrayList<>();
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_REVIEWS + suffix)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) reviews.add(map(rows));
            }
        }
        return reviews;
    }

    /** Seller-facing list excludes reviews hidden by an administrator. */
    public List<Review> findBySeller(Connection connection, String seller) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REVIEWS
                + "WHERE d.seller_username = ? AND r.hidden = FALSE ORDER BY r.created_at DESC, r.id")) {
            statement.setString(1, seller);
            try (ResultSet rows = statement.executeQuery()) {
                List<Review> reviews = new ArrayList<>();
                while (rows.next()) reviews.add(map(rows));
                return reviews;
            }
        }
    }

    public Review findBySellerForUpdate(Connection connection, String seller, String reviewId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REVIEWS
                + "WHERE d.seller_username = ? AND r.id = ? FOR UPDATE")) {
            statement.setString(1, seller);
            statement.setString(2, reviewId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public boolean existsForDealForUpdate(Connection connection, String dealId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM reviews WHERE deal_id = ? FOR UPDATE")) {
            statement.setString(1, dealId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public void create(Connection connection, String id, String dealId, int rating, String comment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO reviews (id, deal_id, rating, comment) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, dealId);
            statement.setInt(3, rating);
            statement.setString(4, comment);
            statement.executeUpdate();
        }
    }

    public void saveReply(Connection connection, String reviewId, String reply) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reviews SET seller_reply = ? WHERE id = ?")) {
            statement.setString(1, reply);
            statement.setString(2, reviewId);
            statement.executeUpdate();
        }
    }

    public void flag(Connection connection, String reviewId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE reviews SET flagged = TRUE WHERE id = ?")) {
            statement.setString(1, reviewId);
            statement.executeUpdate();
        }
    }

    public Review findForModeration(Connection connection, String reviewId) throws SQLException {
        if (reviewId == null || reviewId.isBlank()) {
            throw new IllegalArgumentException("A review ID is required.");
        }
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REVIEWS + "WHERE r.id = ? FOR UPDATE")) {
            statement.setString(1, reviewId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    /** Updates the visibility opposite to the supplied current hidden state. */
    public boolean hide(String reviewId, Boolean hidden) throws SQLException {
        if (reviewId == null || reviewId.isBlank()) {
            throw new IllegalArgumentException("A review ID is required.");
        }
        try (Connection connection = DatabaseManager.getConnection()) {
            return setHidden(connection, reviewId, !hidden);
        }
    }

    public boolean setHidden(Connection connection, String reviewId, boolean hidden) throws SQLException {
        String sql = "UPDATE reviews SET hidden = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, hidden);
            statement.setString(2, reviewId);
            return statement.executeUpdate() > 0;
        }
    }

    private static Review map(ResultSet rows) throws SQLException {
        Review review = new Review();
        review.setId(rows.getString("id"));
        review.setSellerUsername(rows.getString("seller_username"));
        review.setBuyerUsername(rows.getString("buyer_username"));
        review.setListingTitle(rows.getString("itemName"));
        review.setRating(rows.getInt("rating"));
        review.setComment(rows.getString("comment"));
        review.setSellerReply(rows.getString("seller_reply"));
        review.setFlagged(rows.getBoolean("flagged"));
        review.setHidden(rows.getBoolean("hidden"));
        review.setCreatedAt(rows.getTimestamp("created_at").toLocalDateTime());
        return review;
    }
}
