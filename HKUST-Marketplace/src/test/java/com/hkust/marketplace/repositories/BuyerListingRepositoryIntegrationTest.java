package com.hkust.marketplace.repositories;

import com.hkust.marketplace.MySqlFixture;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.utils.DatabaseManager;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuyerListingRepositoryIntegrationTest extends MySqlFixture {
    @Test
    void isolatedMysqlQueriesAndReports() throws Exception {
        BuyerListingRepository.ConnectionProvider provider = DatabaseManager::getConnection;
        try (Connection setup = provider.getConnection()) {
            try (Statement statement = setup.createStatement()) {
                statement.executeUpdate("INSERT INTO users (username,full_name,password_hash,role,active) VALUES "
                        + "('inactive_seller','Inactive Seller','unused','SELLER',FALSE),"
                        + "('inactive_buyer','Inactive Buyer','unused','BUYER',FALSE)");
            }
            insertListing(setup, "live", "charlie", "LIVE");
            insertListing(setup, "no_photos", "charlie", "LIVE");
            insertListing(setup, "inactive", "inactive_seller", "LIVE");
            for (String state : List.of("PENDING_REVIEW", "REJECTED", "RESERVED", "SOLD", "LENT", "GIVEN", "REMOVED")) {
                insertListing(setup, state, "charlie", state);
            }
            for (int i = 1; i <= 6; i++) {
                try (PreparedStatement insert = setup.prepareStatement(
                        "INSERT INTO listing_photos(listing_id,photo_position,photo_data) VALUES ('live',?,?)")) {
                    insert.setInt(1, i);
                    insert.setBytes(2, new byte[]{(byte) i});
                    insert.executeUpdate();
                }
            }
        }
        BuyerListingRepository repository = new BuyerListingRepository(provider);
        List<Listing> browse = repository.findLiveListings();
        assertEquals(List.of("live", "no_photos"), browse.stream().map(Listing::getId).sorted().toList());
        Listing card = browse.stream().filter(l -> "live".equals(l.getId())).findFirst().orElseThrow();
        assertEquals(1, card.getPhotos().size());
        assertArrayEquals(new byte[]{1}, card.getPhotos().getFirst());
        Listing firstOpen = repository.findVisibleById("live");
        assertEquals(6, firstOpen.getPhotos().size());
        assertEquals(card.getViewCount() + 1, firstOpen.getViewCount());
        Listing secondOpen = repository.findVisibleById("live");
        assertEquals(firstOpen.getViewCount() + 1, secondOpen.getViewCount());
        assertEquals(card.getRowVersion(), secondOpen.getRowVersion());
        assertEquals("Charlie Lee", repository.findVisibleById("live").getSellerFullName());
        assertEquals(12.34, repository.findVisibleById("live").getPrice());
        for (String hidden : List.of("PENDING_REVIEW", "REJECTED", "REMOVED", "inactive", "missing")) {
            assertNull(repository.findVisibleById(hidden), hidden);
            assertThrows(IllegalArgumentException.class, () -> repository.reportListing("alice", hidden, "Reason"));
        }
        for (String visible : List.of("RESERVED", "SOLD", "LENT", "GIVEN")) {
            assertEquals(ListingStatus.valueOf(visible), repository.findVisibleById(visible).getStatus());
        }
        assertThrows(IllegalArgumentException.class, () -> repository.reportListing("inactive_buyer", "live", "Reason"));
        assertThrows(IllegalArgumentException.class, () -> repository.reportListing("charlie", "live", "Reason"));
        repository.reportListing("alice", "live", "Potentially misleading description");
        try (Connection check = provider.getConnection(); Statement statement = check.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT * FROM listing_reports")) {
                assertTrue(rows.next());
                assertEquals("live", rows.getString("listing_id"));
                assertEquals("Item live", rows.getString("listing_title"));
                assertEquals("charlie", rows.getString("seller_username"));
                assertEquals("alice", rows.getString("buyer_username"));
                assertNotNull(rows.getTimestamp("created_at"));
                assertFalse(rows.next());
            }
            statement.executeUpdate("UPDATE listings SET listingStatus='REMOVED' WHERE id='live'");
            assertNull(repository.findVisibleById("live"));
            assertThrows(IllegalArgumentException.class, () -> repository.reportListing("alice", "live", "Again"));
            statement.executeUpdate("DELETE FROM listings WHERE id='live'");
            try (ResultSet rows = statement.executeQuery("SELECT listing_id,listing_title,seller_username FROM listing_reports")) {
                assertTrue(rows.next());
                assertNull(rows.getString("listing_id"));
                assertEquals("Item live", rows.getString("listing_title"));
                assertEquals("charlie", rows.getString("seller_username"));
                assertFalse(rows.next());
            }
        }
    }

    private static void insertListing(Connection connection, String id, String seller, String state) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO listings(id,seller_username,itemName,category,listingType,listingStatus,price,
                                     description,item_condition,meetup_location,rejection_reason)
                VALUES (?, ?, ?, 'Books', 'SELL', ?, 12.34, 'A useful textbook', 'LIKE_NEW', 'Library', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, seller);
            statement.setString(3, "Item " + id);
            statement.setString(4, state);
            statement.setString(5, "REJECTED".equals(state) ? "Review rejection" : null);
            statement.executeUpdate();
        }
    }
}
