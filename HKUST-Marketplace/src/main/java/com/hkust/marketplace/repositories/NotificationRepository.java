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
 * Functions are intentionally left for the next step. Planned methods:
 * <ul>
 *   <li>create(Notification) - insert a notification with a generated id, unread</li>
 *   <li>findByRecipient(username) - newest first (uses idx_notifications_recipient)</li>
 *   <li>countUnread(username) / markRead(id) / markAllRead(username)</li>
 *   <li>deleteForUser(username) is handled by the users FK ON DELETE CASCADE</li>
 * </ul>
 * Column names to map: {@code isRead} to {@link Notification#isRead()} and
 * {@code timestamp} to {@link Notification#getTimestamp()}.
 */
public class NotificationRepository {
}
