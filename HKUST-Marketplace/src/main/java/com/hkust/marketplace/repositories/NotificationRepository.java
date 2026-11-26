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
     * Inserts one notification for its recipient using a new connection.
     *
     * <p>A missing id and timestamp are filled in (UUID / now); the notification is stored with
     * its current read flag.
     *
     * @param notification the notification to insert; a missing id and timestamp are set on the
     *        object
     * @throws IllegalArgumentException when required text is blank or too long
     * @throws SQLException when the insert fails (including a missing user foreign key)
     */
    public void create(Notification notification) throws SQLException {
        NotificationValues values = prepare(notification);
        try (Connection connection = DatabaseManager.getConnection()) {
            insert(connection, notification, values);
        }
    }

    /**
     * Persist using the caller's connection; never commit, roll back, or close it.
     *
     * <p>Validation and id/timestamp filling match {@link #create(Notification)}.
     *
     * @param connection an open connection; the caller owns its lifecycle
     * @param notification the notification to insert
     * @throws IllegalArgumentException when the connection or notification is null, or
     *         required text is blank/too long
     * @throws SQLException when the insert fails
     */
    public void create(Connection connection, Notification notification) throws SQLException {
        if (connection == null) throw new IllegalArgumentException("A connection is required.");
        insert(connection, notification, prepare(notification));
    }

    /**
     * Use a current read when checking a delivery ID after acquiring the deal lock.
     *
     * <p>Reads with {@code FOR UPDATE} on the caller's connection; this method never commits.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param notificationId the notification ID
     * @return true when a notification with that ID exists
     * @throws SQLException when the query fails
     */
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
    /**
     * Newest-first unread inbox for one recipient (uses idx_notifications_recipient).
     *
     * <p>Equivalent to {@code findByRecipient(username, false)}.
     *
     * @param username the recipient username
     * @return the unread notifications, newest first; never {@code null}
     * @throws IllegalArgumentException when {@code username} is blank
     * @throws SQLException when the query fails
     */
    public List<Notification> findByRecipient(String username) throws SQLException {
        return findByRecipient(username, false);
    }

    /**
     * Optionally include read history without changing existing unread-only callers.
     *
     * <p>Uses a new connection. Results are ordered newest first, then by ID; with
     * {@code includeRead = false} only rows with {@code isRead = FALSE} are returned.
     *
     * @param username the recipient username
     * @param includeRead true to include already read notifications
     * @return matching notifications, newest first; never {@code null}
     * @throws IllegalArgumentException when {@code username} is blank
     * @throws SQLException when the query fails
     */
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
     * Marks one notification as read, using a new connection.
     *
     * @param notificationId the notification to mark
     * @return true when an unread notification with that id was changed
     * @throws IllegalArgumentException when {@code notificationId} is blank
     * @throws SQLException when the update fails
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

    /**
     * Scope a portal action to its authenticated recipient: marks all of one recipient's unread
     * notifications as read.
     *
     * <p>Uses a new connection. The username is used as supplied; the caller is expected to pass
     * the authenticated name.
     *
     * @param username the recipient whose unread notifications are changed
     * @return the number of notification rows updated
     * @throws SQLException when the update fails
     */
    public int markAllAsRead(String username) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE notifications SET isRead = TRUE WHERE username = ? AND isRead = FALSE")) {
            stmt.setString(1, username);
            return stmt.executeUpdate();
        }
    }

    /**
     * Marks one notification as read only when it belongs to the supplied recipient.
     *
     * <p>Uses a new connection; already read notifications are not counted.
     *
     * @param notificationId the notification to mark
     * @param username the recipient that must own the notification
     * @return true when exactly one unread row for that recipient was updated
     * @throws IllegalArgumentException when either argument is blank
     * @throws SQLException when the update fails
     */
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
     * <p>Uses a new connection; no explicit transaction is started, so the inserts are not
     * committed together.
     *
     * @param category the notification category
     * @param title the notification title
     * @param message the notification message
     * @return the number of active admins that received a notification
     * @throws IllegalArgumentException when required text is blank or too long
     * @throws SQLException when the admin lookup or an insert fails
     */
    public int notifyAllAdmins(String category, String title, String message) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            return notifyAllAdmins(conn, category, title, message);
        }
    }

    /**
     * Creates the same notification for every active admin using the caller's connection.
     *
     * <p>Never commits, rolls back, or closes the connection; the caller's transaction boundary
     * applies. Text limits and validation match {@link #create(Connection, Notification)}.
     *
     * @param conn an open connection; the caller owns its lifecycle
     * @param category the notification category
     * @param title the notification title
     * @param message the notification message
     * @return the number of active admins that received a notification
     * @throws IllegalArgumentException when required text is blank or too long
     * @throws SQLException when the admin lookup or an insert fails
     */
    public int notifyAllAdmins(Connection conn, String category, String title, String message) throws SQLException {
        String safeCategory = requiredText(category, "A notification category", MAX_CATEGORY);
        String safeTitle = requiredText(title, "A notification title", MAX_TITLE);
        String safeMessage = requiredText(message, "A notification message", MAX_MESSAGE);

        List<String> admins = new ArrayList<>();
        String sql = "SELECT username FROM users WHERE role = 'ADMIN' AND active = TRUE ORDER BY username";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                admins.add(rs.getString("username"));
            }
        }
        for (String admin : admins) {
            create(conn, new Notification(admin, safeTitle, safeMessage, safeCategory));
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
