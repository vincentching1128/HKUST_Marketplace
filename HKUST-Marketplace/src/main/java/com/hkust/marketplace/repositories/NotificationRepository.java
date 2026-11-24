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
 * Implemented: {@link #create(Notification)}, {@link #findByRecipient(String)},
 * {@link #markAsRead(String)} and {@link #notifyAllAdmins(String, String, String)}.
 * Still planned:
 * <ul>
 *   <li>countUnread(username) / markAllRead(username)</li>
 *   <li>deleteForUser(username) is handled by the users FK ON DELETE CASCADE</li>
 * </ul>
 * Column names to map: {@code isRead} to {@link Notification#isRead()} and
 * {@code timestamp} to {@link Notification#getTimestamp()}.
 */
public class NotificationRepository {

    private static final int MAX_USERNAME = 50;
    private static final int MAX_CATEGORY = 50;
    private static final int MAX_TITLE = 50;
    // Allow a full 2,000-character report reason plus listing and reporter details.
    private static final int MAX_MESSAGE = 4000;

    /**
     * Inserts one notification for its recipient. A missing id and timestamp are filled
     * in (UUID / now); the notification is stored with its current read flag.
     *
     * @throws IllegalArgumentException when required text is blank or too long
     * @throws SQLException when the insert fails (including a missing user foreign key)
     */
    public void create(Notification notification) throws SQLException {
        NotificationValues values = prepare(notification);
        try (Connection connection = DatabaseManager.getConnection()) {
            insert(connection, notification, values);
        }
    }

    /** Persist using the caller's connection; never commit, roll back, or close it. */
    public void create(Connection connection, Notification notification) throws SQLException {
        if (connection == null) throw new IllegalArgumentException("A connection is required.");
        insert(connection, notification, prepare(notification));
    }

    /** Use a current read when checking a delivery ID after acquiring the deal lock. */
    public boolean existsForUpdate(Connection connection, String notificationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM notifications WHERE id = ? FOR UPDATE")) {
            statement.setString(1, notificationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private record NotificationValues(String username, String category, String title, String message) {}

    private NotificationValues prepare(Notification notification) {
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

        return new NotificationValues(username, category, title, message);
    }

    private void insert(Connection conn, Notification notification, NotificationValues values) throws SQLException {
        String sql = """
                INSERT INTO notifications
                    (id, username, category, title, message, timestamp, isRead)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notification.getId());
            stmt.setString(2, values.username());
            stmt.setString(3, values.category());
            stmt.setString(4, values.title());
            stmt.setString(5, values.message());
            stmt.setTimestamp(6, Timestamp.valueOf(notification.getTimestamp()));
            stmt.setBoolean(7, notification.isRead());
            if (stmt.executeUpdate() != 1) {
                throw new SQLException("The notification could not be created.");
            }
        }
    }
    /** Newest-first inbox for one recipient (uses idx_notifications_recipient). */
    public List<Notification> findByRecipient(String username) throws SQLException {
        return findByRecipient(username, false);
    }

    /** Optionally include read history without changing existing unread-only callers. */
    public List<Notification> findByRecipient(String username, boolean includeRead) throws SQLException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A recipient username is required.");
        }
        String sql = """
                SELECT id, username, category, title, message, timestamp, isRead
                FROM notifications
                WHERE username = ?
                """ + (includeRead ? "" : " AND isRead = FALSE") + " ORDER BY timestamp DESC, id ASC";
        List<Notification> notifications = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapRow(rs));
                }
            }
        }
        return notifications;
    }

    /**
     * Marks one notification as read.
     *
     * @return true when an unread notification with that id was changed
     */
    public boolean markAsRead(String notificationId) throws SQLException {
        if (notificationId == null || notificationId.isBlank()) {
            throw new IllegalArgumentException("A notification ID is required.");
        }
        String sql = "UPDATE notifications SET isRead = TRUE WHERE id = ? AND isRead = FALSE";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notificationId);
            return stmt.executeUpdate() > 0;
        }
    }

    /** Scope a portal action to its authenticated recipient. */
    public boolean markAsRead(String notificationId, String username) throws SQLException {
        if (notificationId == null || notificationId.isBlank() || username == null || username.isBlank()) {
            throw new IllegalArgumentException("A notification ID and recipient are required.");
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE notifications SET isRead = TRUE WHERE id = ? AND username = ? AND isRead = FALSE")) {
            stmt.setString(1, notificationId);
            stmt.setString(2, username);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * Creates the same notification for every active admin account (admin audit feed:
     * listing submissions, account changes, reports).
     *
     * @return the number of admin notifications created
     */
    public int notifyAllAdmins(String category, String title, String message) throws SQLException {
        String safeCategory = requiredText(category, "A notification category", MAX_CATEGORY);
        String safeTitle = requiredText(title, "A notification title", MAX_TITLE);
        String safeMessage = requiredText(message, "A notification message", MAX_MESSAGE);

        List<String> admins = new ArrayList<>();
        String sql = "SELECT username FROM users WHERE role = 'ADMIN' AND active = TRUE ORDER BY username";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                admins.add(rs.getString("username"));
            }
        }
        for (String admin : admins) {
            create(new Notification(admin, safeTitle, safeMessage, safeCategory));
        }
        return admins.size();
    }

    private Notification mapRow(ResultSet rs) throws SQLException {
        Notification notification = new Notification();
        notification.setId(rs.getString("id"));
        notification.setUsername(rs.getString("username"));
        notification.setCategory(rs.getString("category"));
        notification.setTitle(rs.getString("title"));
        notification.setMessage(rs.getString("message"));
        Timestamp timestamp = rs.getTimestamp("timestamp");
        notification.setTimestamp(timestamp == null ? null : timestamp.toLocalDateTime());
        notification.setRead(rs.getBoolean("isRead"));
        return notification;
    }

    // Keep text within the application limits and schema column capacities.
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
