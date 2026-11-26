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

/**
 * MySQL persistence and read queries for seller reviews in the {@code reviews} table.
 *
 * <p>Supports admin moderation and search, seller-facing review history, replies, flags,
 * and hiding/unhiding. Review rows are joined to deals and listings for usernames and
 * listing titles.
 */
public class ReviewRepository {
    private static final String SELECT_REVIEWS = """
            SELECT r.id, d.seller_username, d.buyer_username, l.itemName, r.rating,
                   r.comment, r.seller_reply, r.flagged, r.hidden, r.created_at
            FROM reviews r
            JOIN deals d ON d.id = r.deal_id
            JOIN listings l ON l.id = d.listing_id
            """;
    private static final String NEWEST_FIRST = " ORDER BY r.created_at DESC, r.id";

    /**
     * All reviews, newest first (includes hidden/flagged rows for the admin moderation screen).
     *
     * <p>Uses a new connection.
     *
     * @return every review, newest first; never {@code null}
     * @throws SQLException when the query fails
     */
    public List<Review> findAll() throws SQLException {
        return query("", List.of());
    }

    /**
     * Reviews with exactly the supplied rating; ratings are constrained to 1..5.
     *
     * <p>Uses a new connection and includes hidden/flagged rows.
     *
     * @param rating the exact rating to match, 1 through 5
     * @return matching reviews, newest first; never {@code null}
     * @throws IllegalArgumentException when {@code rating} is outside 1..5
     * @throws SQLException when the query fails
     */
    public List<Review> findByRating(int rating) throws SQLException {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5.");
        }
        return query("WHERE r.rating = ?" + NEWEST_FIRST, List.of(rating));
    }

    /**
     * Admin search: literal keyword in the seller username (case follows the database
     * collation) plus an inclusive created-date range. Null keyword/dates are ignored.
     *
     * <p>Uses a new connection and includes hidden/flagged rows.
     *
     * @param keyword seller-username substring to find, or {@code null}/blank for no keyword
     * @param from inclusive earliest created date, or {@code null} for unbounded
     * @param to inclusive latest created date, or {@code null} for unbounded
     * @return matching reviews, newest first; never {@code null}
     * @throws IllegalArgumentException when {@code from} is after {@code to}
     * @throws SQLException when the query fails
     * @see #searchReviews(String, Integer, LocalDate, LocalDate)
     */
    public List<Review> searchReviews(String keyword, LocalDate from, LocalDate to) throws SQLException {
        return searchReviews(keyword, null, from, to);
    }

    /**
     * Same search with an optional exact rating (1..5) filter, for the ratings screen.
     *
     * <p>Uses a new connection and includes hidden/flagged rows.
     *
     * @param keyword seller-username substring to find, or {@code null}/blank for no keyword
     * @param rating exact rating filter, or {@code null} for every rating
     * @param from inclusive earliest created date, or {@code null} for unbounded
     * @param to inclusive latest created date, or {@code null} for unbounded
     * @return matching reviews, newest first; never {@code null}
     * @throws IllegalArgumentException when {@code rating} is outside 1..5 or {@code from} is
     *         after {@code to}
     * @throws SQLException when the query fails
     */
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

    /**
     * Seller-facing list excludes reviews hidden by an administrator.
     *
     * <p>Caller-owned connection: this method never commits, rolls back or closes it. Reviews
     * are matched through the deal's seller username and only rows with {@code hidden = FALSE}
     * are returned.
     *
     * @param connection an open connection; the caller owns its lifecycle
     * @param seller the seller username
     * @return the seller's visible reviews, newest first; never {@code null}
     * @throws SQLException when the query fails
     */
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

    /**
     * Returns one review belonging to the seller while locking it with {@code FOR UPDATE}.
     *
     * <p>Caller-owned connection: this method never commits or closes it. Hidden reviews are
     * not filtered out here.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param seller the seller username that must own the review
     * @param reviewId the review ID
     * @return the locked review, or {@code null} when it does not exist or belongs to another
     *         seller
     * @throws SQLException when the query fails
     */
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

    /**
     * Checks whether a review exists for a deal while locking any matching row with
     * {@code FOR UPDATE}.
     *
     * <p>Caller-owned connection: this method never commits or closes it.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param dealId the deal ID
     * @return true when a review row for the deal exists
     * @throws SQLException when the query fails
     */
    public boolean existsForDealForUpdate(Connection connection, String dealId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM reviews WHERE deal_id = ? FOR UPDATE")) {
            statement.setString(1, dealId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Inserts one review row.
     *
     * <p>Caller-owned connection: this method never commits or closes it. No rating or comment
     * validation and no deal/order check is performed here; those belong to the service.
     *
     * @param connection an open connection; the caller owns its lifecycle
     * @param id the review ID to store
     * @param dealId the reviewed deal
     * @param rating the rating value to store
     * @param comment the review text, possibly {@code null}
     * @throws SQLException when the insert fails
     */
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

    /**
     * Stores the seller's reply on a review.
     *
     * <p>Caller-owned connection: this method never commits or closes it. A missing review ID
     * is not an error; the update simply affects no rows.
     *
     * @param connection an open connection; the caller owns its lifecycle
     * @param reviewId the review whose reply is written
     * @param reply the reply text to store
     * @throws SQLException when the update fails
     */
    public void saveReply(Connection connection, String reviewId, String reply) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reviews SET seller_reply = ? WHERE id = ?")) {
            statement.setString(1, reply);
            statement.setString(2, reviewId);
            statement.executeUpdate();
        }
    }

    /**
     * Marks a review as flagged for admin attention.
     *
     * <p>Caller-owned connection: this method never commits or closes it. A missing review ID
     * is not an error; the update simply affects no rows.
     *
     * @param connection an open connection; the caller owns its lifecycle
     * @param reviewId the review to flag
     * @throws SQLException when the update fails
     */
    public void flag(Connection connection, String reviewId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE reviews SET flagged = TRUE WHERE id = ?")) {
            statement.setString(1, reviewId);
            statement.executeUpdate();
        }
    }

    /**
     * Locks and returns a review for an admin moderation decision.
     *
     * <p>Caller-owned connection: this method never commits or closes it. The row is read with
     * {@code FOR UPDATE}, and hidden/flagged state is included.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param reviewId the review ID
     * @return the locked review, or {@code null} when it does not exist
     * @throws IllegalArgumentException when {@code reviewId} is blank
     * @throws SQLException when the query fails
     */
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

    /**
     * Toggles a review's visibility: stores the opposite of the supplied current hidden state.
     *
     * <p>Callers pass the review's current {@code hidden} value, so {@code false} hides the
     * review and {@code true} unhides it. Uses a new connection and commits immediately; the
     * row is updated by ID even when it is already in the target state.
     *
     * @param reviewId the review whose visibility is toggled
     * @param hidden the review's current hidden state; the stored value becomes
     *        {@code !hidden}
     * @return true when a review row was updated, false when the ID does not exist
     * @throws IllegalArgumentException when {@code reviewId} is blank
     * @throws SQLException when the update fails
     */
    public boolean hide(String reviewId, Boolean hidden) throws SQLException {
        if (reviewId == null || reviewId.isBlank()) {
            throw new IllegalArgumentException("A review ID is required.");
        }
        try (Connection connection = DatabaseManager.getConnection()) {
            return setHidden(connection, reviewId, !hidden);
        }
    }

    /**
     * Sets a review's hidden flag to the supplied absolute value.
     *
     * <p>Caller-owned connection: this method never commits or closes it. Unlike
     * {@link #hide(String, Boolean)} the value is not inverted.
     *
     * @param connection an open connection; the caller owns its lifecycle
     * @param reviewId the review to update
     * @param hidden the value to store
     * @return true when a review row was updated, false when the ID does not exist
     * @throws SQLException when the update fails
     */
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
