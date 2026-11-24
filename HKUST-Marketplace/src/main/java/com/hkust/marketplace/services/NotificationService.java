package com.hkust.marketplace.services;

import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Authorizes a portal inbox and scopes mark-as-read to the current recipient. */
public class NotificationService {
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final UserRole role;

    public NotificationService(UserRepository users, NotificationRepository notifications, UserRole role) {
        this.users = Objects.requireNonNull(users);
        this.notifications = Objects.requireNonNull(notifications);
        this.role = Objects.requireNonNull(role);
    }

    public List<Notification> getNotifications(User recipient, boolean includeRead) throws SQLException {
        return notifications.findByRecipient(requireRecipient(recipient).getUsername(), includeRead);
    }

    public boolean markAsRead(User recipient, String notificationId) throws SQLException {
        return notifications.markAsRead(notificationId, requireRecipient(recipient).getUsername());
    }

    public int markAllAsRead(User recipient) throws SQLException {
        return notifications.markAllAsRead(requireRecipient(recipient).getUsername());
    }

    private User requireRecipient(User recipient) throws SQLException {
        User current = recipient == null || recipient.getRole() != role
                || recipient.getUsername() == null || recipient.getUsername().isBlank()
                ? null : users.findByUsername(recipient.getUsername());
        if (current == null || current.getRole() != role || !current.isActive()) {
            throw new IllegalArgumentException("An active account for this portal is required to access notifications.");
        }
        return current;
    }
}
