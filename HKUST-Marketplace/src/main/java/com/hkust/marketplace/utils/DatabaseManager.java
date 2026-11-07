package com.hkust.marketplace.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import java.util.logging.Level;
import java.util.logging.Logger;
public class DatabaseManager {

    private static final String URL =
            "jdbc:mysql://localhost:3306/hkust_marketplace";
    private static final Logger LOGGER =
            Logger.getLogger(DatabaseManager.class.getName());

    public static Connection getConnection() throws SQLException {
        String username = System.getenv("MARKETPLACE_DB_USER");
        String password = System.getenv("MARKETPLACE_DB_PASSWORD");

        return DriverManager.getConnection(URL, username, password);
    }

    public static void main(String[] args) {
        try (Connection connection = getConnection()) {
            System.out.println("Database connected successfully.");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection failed.", e);
        }
    }
}
