package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.services.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;


public class UserRepository {

    /** Re-read and hold an account stable for a caller-owned business transaction. */
    public User findByUsername(Connection connection, String username) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw new IllegalArgumentException("An account lock requires a transaction.");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM users WHERE username = ? FOR SHARE")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? mapRow(rows) : null;
            }
        }
    }

    public User findByUsername(String target) throws SQLException {
        String sql = "SELECT * FROM users WHERE username = ?"; //query
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
             stmt.setString(1, target); //allow SQL to identify the ? as java object

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) { //check if anything has been returned
                    return null;
                }

                return mapRow(rs); //turn the current row into a User object
            }
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User(); //create new user in the backend (not database)
        user.setUsername(rs.getString("username"));
        user.setFullName(rs.getString("full_name"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(UserRole.valueOf(rs.getString("role")));
        user.setResidenceHall(rs.getString("residence_hall"));
        user.setBio(rs.getString("bio"));
        user.setPreferredMeetup(rs.getString("preferred_meetup"));
        user.setActive(rs.getBoolean("active"));
        user.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return user;
    }

    public boolean existsByUsername(String target) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, target);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void register(User user) throws SQLException {
        requireHash(user.getPasswordHash());
        if (existsByUsername(user.getUsername())){
            throw new IllegalArgumentException("Username already exists");
        } //double check if username already exist in the database
        String sql = """
            INSERT INTO users
                (username, full_name, password_hash, role,
                 residence_hall, bio, preferred_meetup, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """; //insert user into the database
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)){
             stmt.setString(1, user.getUsername());
             stmt.setString(2, user.getFullName());
             stmt.setString(3, user.getPasswordHash());
             stmt.setString(4, user.getRole().name());
             stmt.setString(5, user.getResidenceHall());
             stmt.setString(6, user.getBio());
             stmt.setString(7, user.getPreferredMeetup());
             stmt.setBoolean(8, user.isActive());

            stmt.executeUpdate();
             } //filling arguments with java variables to the query

    }

    /** Updates profile fields for the supplied username. Returns false if no user matches. */
    public boolean updateProfile(User user) throws SQLException{
        return updateProfile(user, null);
    }

    public boolean updateProfile(User user, String passwordHash) throws SQLException {
        if (passwordHash != null) requireHash(passwordHash);
        String sql = """
            UPDATE users
            SET full_name = ?, residence_hall = ?, bio = ?, preferred_meetup = ?
            """ + (passwordHash == null ? " WHERE username = ?"
                : ", password_hash = ? WHERE username = ?");
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getFullName());
            stmt.setString(2, user.getResidenceHall());
            stmt.setString(3, user.getBio());
            stmt.setString(4, user.getPreferredMeetup());
            if (passwordHash == null) {
                stmt.setString(5, user.getUsername());
            } else {
                stmt.setString(5, passwordHash);
                stmt.setString(6, user.getUsername());
            }

            return stmt.executeUpdate() > 0;
        }
    }

    /** Stores an already hashed password. Authentication and authorization belong to the caller. */
    public boolean updatePasswordHash(String username, String hash) throws SQLException{
        requireHash(hash);
        String sql = "UPDATE users SET password_hash = ? WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hash);
            stmt.setString(2, username);

            return stmt.executeUpdate() > 0;
        }
    }

    /** Compare exact bytes, avoiding case-insensitive database collation during migration. */
    public boolean replacePasswordHash(String username, String previous, String hash) throws SQLException {
        requireHash(hash);
        String sql = "UPDATE users SET password_hash = ? WHERE username = ? "
                + "AND CAST(password_hash AS BINARY) = CAST(? AS BINARY)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hash);
            stmt.setString(2, username);
            stmt.setString(3, previous);
            return stmt.executeUpdate() > 0;
        }
    }

    private static void requireHash(String hash) {
        if (!PasswordHasher.isHash(hash)) {
            throw new IllegalArgumentException("A valid Argon2id password hash is required.");
        }
    }

    public void setActive(String username, boolean active) throws SQLException{
        if(!existsByUsername(username)){
            throw new IllegalArgumentException("No such username");
        }
        String sql = "UPDATE users SET active = ? WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, active); //write the requested state
            stmt.setString(2,username);
            int row = stmt.executeUpdate();
            if (row == 0) { //row disappeared between the check and the update
                throw new IllegalArgumentException("No such username");
            }
            System.out.println(row + (active ? " activated" : " deactivated"));
        }
    }


    public List<User> findAll() throws SQLException{
        String sql = "SELECT * FROM users ORDER BY username";
        List<User> users = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                users.add(mapRow(rs));
            }
        }
        return users;
    }

    /**
     * Permanently deletes a buyer/seller account in one transaction:
     * <ol>
     *   <li>locks/validates the account first, so retrying a deletion is idempotent
     *       (a missing account aborts before any counter is touched);</li>
     *   <li>marks the seller's listings REMOVED and deletes the user's notifications;</li>
     *   <li>increments the participant-deletion counter ({@code deleted}, capped at 2) on
     *       every deal and chat thread they participate in, cancelling still-open deals when
     *       one participant remains;</li>
     *   <li>deletes the user row; the deals/chat/listing FKs use ON DELETE SET NULL, so the
     *       preserved records keep the remaining participant.</li>
     * </ol>
     * Admin accounts cannot be removed here.
     *
     * @throws IllegalArgumentException when the username is missing, unknown or an admin
     */
    public void deleteUser(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A username is required.");
        }
        String name = username.trim();
        String lockUser = "SELECT username FROM users WHERE username = ? AND role <> 'ADMIN' FOR UPDATE";
        String markListingsRemoved = """
                UPDATE listings
                SET listingStatus = 'REMOVED', rejection_reason = 'User account deleted',
                    row_version = row_version + 1
                WHERE seller_username = ? AND listingStatus <> 'REMOVED'
                """;
        String deleteNotifications = "DELETE FROM notifications WHERE username = ?";
        String cancelDeals = """
                UPDATE deals
                SET status = CASE WHEN deleted = 0 AND status IN ('PENDING','COUNTERED','ACCEPTED')
                                  THEN 'CANCELLED' ELSE status END,
                    deleted = LEAST(deleted + 1, 2)
                WHERE deleted < 2 AND (buyer_username = ? OR seller_username = ?)
                """;
        String incrementChatThreads = """
                UPDATE chat_threads
                SET deleted = LEAST(deleted + 1, 2)
                WHERE deleted < 2 AND (buyer_username = ? OR seller_username = ?)
                """;
        String deleteUser = "DELETE FROM users WHERE username = ? AND role <> 'ADMIN'";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                //idempotency guard: only an existing, non-admin account can be deleted
                try (PreparedStatement stmt = conn.prepareStatement(lockUser)) {
                    stmt.setString(1, name);
                    try (ResultSet rows = stmt.executeQuery()) {
                        if (!rows.next()) {
                            throw new IllegalArgumentException("User not found or account cannot be deleted.");
                        }
                    }
                }
                try (PreparedStatement stmt = conn.prepareStatement(markListingsRemoved)) {
                    stmt.setString(1, name);
                    stmt.executeUpdate();
                }
                //remove the account's notifications in the same transaction (the FK also cascades)
                try (PreparedStatement stmt = conn.prepareStatement(deleteNotifications)) {
                    stmt.setString(1, name);
                    stmt.executeUpdate();
                }
                //deals: count the deletion, cancel open deals when one participant remains
                try (PreparedStatement stmt = conn.prepareStatement(cancelDeals)) {
                    stmt.setString(1, name);
                    stmt.setString(2, name);
                    stmt.executeUpdate();
                }
                //chats: deleted = 2 hides the thread from default queries, deleted = 1 keeps it
                try (PreparedStatement stmt = conn.prepareStatement(incrementChatThreads)) {
                    stmt.setString(1, name);
                    stmt.setString(2, name);
                    stmt.executeUpdate();
                }
                int deleted;
                try (PreparedStatement stmt = conn.prepareStatement(deleteUser)) {
                    stmt.setString(1, name);
                    deleted = stmt.executeUpdate();
                }
                if (deleted != 1) {
                    conn.rollback();
                    throw new IllegalArgumentException("User not found or account cannot be deleted.");
                }
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    //TO BE IMPLEMENTED

}
