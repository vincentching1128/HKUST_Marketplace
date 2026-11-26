package com.hkust.marketplace.utils;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * Central MySQL connection factory for the marketplace application.
 *
 * <p>Resolves credentials from an explicit {@code marketplace.db.config} properties file or
 * the {@code MARKETPLACE_DB_USER}/{@code MARKETPLACE_DB_PASSWORD} environment pair, then
 * opens a JDBC connection to the local {@code hkust_marketplace} database. Callers own and
 * must close the returned connection.
 */
public class DatabaseManager {

    private static final String URL =
            "jdbc:mysql://localhost:3306/hkust_marketplace";
    private static final Logger LOGGER =
            Logger.getLogger(DatabaseManager.class.getName());

    public static Connection getConnection() throws SQLException {
        String explicitConfig = System.getProperty("marketplace.db.config");
        Properties credentials = explicitConfig == null
                ? loadCredentials(System.getenv("MARKETPLACE_DB_USER"), System.getenv("MARKETPLACE_DB_PASSWORD"),
                        Path.of(System.getProperty("user.home"), ".hkust-marketplace", "database.properties"))
                : loadCredentials(null, null, Path.of(explicitConfig));

        return DriverManager.getConnection(credentials.getProperty("url", URL), credentials.getProperty("username"),
                credentials.getProperty("password"));
    }

    // Keep each username/password pair together; never mix different accounts' credentials.
    static Properties loadCredentials(String username, String password, Path configFile)
            throws SQLException {
        Properties credentials = new Properties();
        if (hasCredentials(username, password)) {
            credentials.setProperty("username", username);
            credentials.setProperty("password", password);
            return credentials;
        }

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                credentials.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                throw new SQLException("Cannot read the saved database connection at "
                        + configFile + ". Check the file or set both MARKETPLACE_DB_USER "
                        + "and MARKETPLACE_DB_PASSWORD.", e);
            }
        }

        if (!hasCredentials(credentials.getProperty("username"),
                credentials.getProperty("password"))) {
            throw new SQLException("Database credentials are missing. Set both "
                    + "MARKETPLACE_DB_USER and MARKETPLACE_DB_PASSWORD, or save username "
                    + "and password properties in " + configFile + ".");
        }
        return credentials;
    }

    private static boolean hasCredentials(String username, String password) {
        return username != null && !username.isBlank()
                && password != null && !password.isEmpty();
    }

    public static void main(String[] args) {
        try (Connection connection = getConnection()) {
            System.out.println("Database connected successfully.");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection failed.", e);
        }
    }
}
