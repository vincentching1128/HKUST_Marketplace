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

    /**
     * Creates a portal-scoped notification service.
     *
     * <p>The role is the portal allowed to use this instance; every inbox operation
     * requires an active account with exactly that role.
     *
     * @param users the account repository used to authorise the recipient
     * @param notifications the notification repository
     * @param role the portal role allowed to use this instance
     * @throws NullPointerException if any argument is {@code null}
     */
    public NotificationService(UserRepository users, NotificationRepository notifications, UserRole role) {
        this.users = Objects.requireNonNull(users);
        this.notifications = Objects.requireNonNull(notifications);
        this.role = Objects.requireNonNull(role);
    }

    /**
     * Returns the signed-in recipient's notifications, newest first.
     *
     * <p>The recipient's stored account must be active and have the role this service was
     * created for; the inbox is then scoped to that account's username.
     *
     * @param recipient the signed-in account
     * @param includeRead {@code true} to include read history, {@code false} for unread
     *     notifications only
     * @return the matching notifications
     * @throws IllegalArgumentException if the account is missing, inactive, has the wrong
     *     role, or has no username
     * @throws SQLException if the account or notification query fails
     */
    public List<Notification> getNotifications(User recipient, boolean includeRead) throws SQLException {
        return notifications.findByRecipient(requireRecipient(recipient).getUsername(), includeRead);
    }

    /**
     * Marks one notification as read when it belongs to the signed-in recipient.
     *
     * <p>The update is scoped by both notification id and recipient username, so one
     * portal cannot mark another account's notification.
     *
     * @param recipient the signed-in account
     * @param notificationId the notification identifier
     * @return {@code true} when an unread notification owned by the recipient was marked
     *     read; {@code false} when it does not exist, is already read, or belongs to
     *     another account
     * @throws IllegalArgumentException if the account is not an active account for this
     *     portal, or the notification id is blank
     * @throws SQLException if the account lookup or update fails
     */
    public boolean markAsRead(User recipient, String notificationId) throws SQLException {
        return notifications.markAsRead(notificationId, requireRecipient(recipient).getUsername());
    }

    /**
     * Marks every unread notification of the signed-in recipient as read.
     *
     * @param recipient the signed-in account
     * @return the number of notifications that were changed
     * @throws IllegalArgumentException if the account is not an active account for this
     *     portal
     * @throws SQLException if the account lookup or update fails
     */
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
