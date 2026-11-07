package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;


public class UserRepository {

    public User findByUsername(String target) throws SQLException {
        String sql = "SELECT * FROM users WHERE username = ?"; //query
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
             stmt.setString(1, target); //allow SQL to identify the ? as java object

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) { //check if anything has been returned
                    return null;
                }

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
        }
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
        String sql = """
            UPDATE users
            SET full_name = ?, residence_hall = ?, bio = ?, preferred_meetup = ?
            WHERE username = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getFullName());
            stmt.setString(2, user.getResidenceHall());
            stmt.setString(3, user.getBio());
            stmt.setString(4, user.getPreferredMeetup());
            stmt.setString(5, user.getUsername());

            return stmt.executeUpdate() > 0;
        }
    }

    /** Stores an already hashed password. Authentication and authorization belong to the caller. */
    public boolean updatePasswordHash(String username, String hash) throws SQLException{
        String sql = "UPDATE users SET password_hash = ? WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hash);
            stmt.setString(2, username);

            return stmt.executeUpdate() > 0;
        }
    }

    //TO BE IMPLEMENTED
    public void setActive(String username, boolean active) throws SQLException{
        return;
    }

    public List<User> findAll() throws SQLException{
        List<User> users = new ArrayList<>();
        return users;
    }



}
