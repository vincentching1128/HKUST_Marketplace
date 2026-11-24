package com.hkust.marketplace;

import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.services.NotificationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationBoardIT extends MySqlFixture {
    @Test
    void bulkReadKeepsHistoryAndOnlyUpdatesTheCurrentRecipient() throws Exception {
        notificationRows.create(new Notification(seller.getUsername(), "First", "First message", "Listing"));
        notificationRows.create(new Notification(seller.getUsername(), "Second", "Second message", "Listing"));
        notificationRows.create(new Notification(buyer.getUsername(), "Other", "Other message", "Offer"));
        var service = new NotificationService(users, notificationRows, seller.getRole());
        assertThrows(IllegalArgumentException.class, () -> service.markAllAsRead(buyer));
        assertEquals(2, service.markAllAsRead(seller));
        assertEquals(0, service.markAllAsRead(seller));
        assertTrue(service.getNotifications(seller, false).isEmpty());
        assertEquals(2, inbox(seller).stream().filter(Notification::isRead).count());
        assertFalse(inbox(buyer).getFirst().isRead());
    }
}
