package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.Review;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public List<Review> findBySeller(Connection connection, String seller) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REVIEWS
                + "WHERE d.seller_username = ? ORDER BY r.created_at DESC, r.id")) {
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
