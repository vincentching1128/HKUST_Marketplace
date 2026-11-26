package com.hkust.marketplace.models;

import java.time.LocalDateTime;

/**
 * MySQL-backed notification sent to one user.
 *
 * <p>Captures the recipient, title, message, category, creation timestamp, and read state
 * displayed by the admin, buyer, and seller notification boards.
 */
public class Notification {
    private String id;
    private String username; // recipient
    private String title;
    private String message;
    private String category; // Offer, Chat, System, Rating, etc.
    private LocalDateTime timestamp;
    private boolean read;

    public Notification() {
        this.timestamp = LocalDateTime.now();
        this.read = false;
    }

    public Notification(String username, String title, String message, String category) {
        this();
        this.username = username;
        this.title = title;
        this.message = message;
        this.category = category;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
}
