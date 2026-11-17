package com.hkust.marketplace.services;

import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Authorizes the seller inbox and scopes mark-as-read to the current recipient. */
public class SellerNotificationService {
    private final UserRepository users;
    private final NotificationRepository notifications;

    public SellerNotificationService() { this(new UserRepository(), new NotificationRepository()); }

    public SellerNotificationService(UserRepository users, NotificationRepository notifications) {
        this.users = Objects.requireNonNull(users);
        this.notifications = Objects.requireNonNull(notifications);
    }

    public List<Notification> getNotifications(User seller, boolean includeRead) throws SQLException {
        return notifications.findByRecipient(requireSeller(seller).getUsername(), includeRead);
    }

    public boolean markAsRead(User seller, String notificationId) throws SQLException {
        return notifications.markAsRead(notificationId, requireSeller(seller).getUsername());
    }

    private User requireSeller(User seller) throws SQLException {
        User current = seller == null || seller.getUsername() == null || seller.getUsername().isBlank()
                ? null : users.findByUsername(seller.getUsername());
        if (current == null || current.getRole() != UserRole.SELLER || !current.isActive()) {
            throw new IllegalArgumentException("An active seller account is required to access notifications.");
        }
        return current;
    }
}
