package com.hkust.marketplace;

import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.services.NotificationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationBoardIT extends MySqlFixture {
    @Test
    void hidingAReviewNotifiesBothParticipantsOnce() throws Exception {
        var item = live(ListingType.SELL);
        var deal = buys.submit(buyer, item.getId(), ListingType.SELL, 35.0, null, null);
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        buys.complete(buyer, deal.getId(), buys.getMyDeal(buyer, deal.getId()).getRowVersion());
        reviews.submitReview(buyer, deal.getId(), 4, "As described");
        String reviewId = reviews.getSellerReviews(seller).getFirst().getId();

        assertThrows(IllegalArgumentException.class, () -> reviews.setHidden(seller, reviewId, true));
        reviews.setHidden(admin, reviewId, true);
        reviews.setHidden(admin, reviewId, true);

        assertTrue(reviews.getSellerReviews(seller).isEmpty());
        assertTrue(buys.getHistory(buyer).getFirst().hidden());
        var buyerNotices = inbox(buyer).stream().filter(n -> "Your Review Was Hidden".equals(n.getTitle())).toList();
        var sellerNotices = inbox(seller).stream().filter(n -> "A Seller Review Was Hidden".equals(n.getTitle())).toList();
        assertEquals(1, buyerNotices.size());
        assertEquals(1, sellerNotices.size());
        assertTrue(buyerNotices.getFirst().getMessage().contains(item.getTitle()));
        assertTrue(sellerNotices.getFirst().getMessage().contains(item.getTitle()));
        assertFalse(buyerNotices.getFirst().isRead());
        assertFalse(sellerNotices.getFirst().isRead());

        reviews.setHidden(admin, reviewId, false);
        assertEquals(reviewId, reviews.getSellerReviews(seller).getFirst().getId());
    }

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
