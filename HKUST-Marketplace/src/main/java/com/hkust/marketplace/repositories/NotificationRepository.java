package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Database access for per-recipient notifications.
 * <p>
 * Implemented: {@link #create(Notification)}. Still planned:
 * <ul>
 *   <li>findByRecipient(username) - newest first (uses idx_notifications_recipient)</li>
 *   <li>countUnread(username) / markRead(id) / markAllRead(username)</li>
 *   <li>deleteForUser(username) is handled by the users FK ON DELETE CASCADE</li>
 * </ul>
 * Column names to map: {@code isRead} to {@link Notification#isRead()} and
 * {@code timestamp} to {@link Notification#getTimestamp()}.
 */
public class NotificationRepository {

    private static final int MAX_USERNAME = 50;
    private static final int MAX_CATEGORY = 50;
    private static final int MAX_TITLE = 50;
    private static final int MAX_MESSAGE = 2000;

    /**
     * Inserts one notification for its recipient. A missing id and timestamp are filled
     * in (UUID / now); the notification is stored with its current read flag.
     *
     * @throws IllegalArgumentException when required text is blank or too long
     * @throws SQLException when the insert fails (including a missing user foreign key)
     */
    public void create(Notification notification) throws SQLException {
        if (notification == null) {
            throw new IllegalArgumentException("A notification is required.");
        }
        String username = requiredText(notification.getUsername(), "A recipient username", MAX_USERNAME);
        String category = requiredText(notification.getCategory(), "A notification category", MAX_CATEGORY);
        String title = requiredText(notification.getTitle(), "A notification title", MAX_TITLE);
        String message = requiredText(notification.getMessage(), "A notification message", MAX_MESSAGE);

        if (notification.getId() == null || notification.getId().isBlank()) {
            notification.setId(UUID.randomUUID().toString());
        }
        if (notification.getTimestamp() == null) {
            notification.setTimestamp(LocalDateTime.now());
        }

        String sql = """
                INSERT INTO notifications
                    (id, username, category, title, message, timestamp, isRead)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notification.getId());
            stmt.setString(2, username);
            stmt.setString(3, category);
            stmt.setString(4, title);
            stmt.setString(5, message);
            stmt.setTimestamp(6, Timestamp.valueOf(notification.getTimestamp()));
            stmt.setBoolean(7, notification.isRead());
            if (stmt.executeUpdate() != 1) {
                throw new SQLException("The notification could not be created.");
            }
        }
    }
    public void markAsRead(String id){

    }
    //keep the values inside the VARCHAR limits declared in schema.sql
    private static String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new IllegalArgumentException(label + " must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
