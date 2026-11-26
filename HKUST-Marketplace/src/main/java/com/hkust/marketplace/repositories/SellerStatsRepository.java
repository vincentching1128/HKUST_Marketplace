package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only seller statistics repository.
 *
 * <p>Aggregates listing views/status/category counts and deal/review metrics from MySQL;
 * hidden reviews are excluded from the average rating.
 */
public class SellerStatsRepository {
    /**
     * Aggregated seller metrics calculated from MySQL.
     *
     * @param views total views across non-removed listings
     * @param offersReceived total deals received by the seller
     * @param completedDeals completed or returned deals
     * @param averageRating average visible review rating, or {@code null} when unreviewed
     * @param listingsByCategory listing counts keyed by category
     * @param listingsByStatus listing counts keyed by current status
     */
    public record Stats(long views, long offersReceived, long completedDeals, Double averageRating,
                        Map<String, Long> listingsByCategory, Map<ListingStatus, Long> listingsByStatus) {}

    public Stats findBySeller(String username) throws SQLException {
        String listingsSql = """
                SELECT category, listingStatus, COUNT(*) AS listing_count, SUM(view_count) AS views
                FROM listings
                WHERE seller_username = ? AND listingStatus <> 'REMOVED'
                GROUP BY category, listingStatus
                ORDER BY category, listingStatus
                """;
        String dealsSql = """
                SELECT COUNT(*) AS offers_received,
                       COUNT(CASE WHEN d.status IN ('COMPLETED', 'RETURNED') THEN 1 END) AS completed_deals,
                       AVG(r.rating) AS average_rating
                FROM deals d
                LEFT JOIN reviews r ON r.deal_id = d.id AND r.hidden = FALSE
                WHERE d.seller_username = ?
                """;
        Map<String, Long> categories = new LinkedHashMap<>();
        Map<ListingStatus, Long> statuses = new EnumMap<>(ListingStatus.class);
        long views = 0;
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement listings = connection.prepareStatement(listingsSql);
             PreparedStatement deals = connection.prepareStatement(dealsSql)) {
            listings.setString(1, username);
            try (ResultSet rows = listings.executeQuery()) {
                while (rows.next()) {
                    long count = rows.getLong("listing_count");
                    categories.merge(rows.getString("category"), count, Long::sum);
                    statuses.merge(ListingStatus.valueOf(rows.getString("listingStatus")), count, Long::sum);
                    views += rows.getLong("views");
                }
            }
            deals.setString(1, username);
            try (ResultSet row = deals.executeQuery()) {
                row.next();
                double average = row.getDouble("average_rating");
                Double rating = row.wasNull() ? null : average;
                return new Stats(views, row.getLong("offers_received"), row.getLong("completed_deals"),
                        rating, Collections.unmodifiableMap(categories), Collections.unmodifiableMap(statuses));
            }
        }
    }
}
