package com.hkust.marketplace;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.*;
import com.hkust.marketplace.services.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owns a local MySQL 8 process. Every test gets the production schema and seed in a fresh database. */
public abstract class MySqlFixture {
    @TempDir static Path work;
    private static Process server;
    private static String serverUrl;
    private static final String password = UUID.randomUUID().toString();
    private static String previousConfig;
    final UserRepository users = new UserRepository();
    final ListingRepository listingRows = new ListingRepository();
    final NotificationRepository notificationRows = new NotificationRepository();
    final ListingService listings = new ListingService();
    final BuyerListingService browse = new BuyerListingService();
    final BuyerDealService buys = new BuyerDealService();
    final DealService offers = new DealService();
    final ChatService chat = new ChatService();
    final ReviewService reviews = new ReviewService();
    User buyer, seller, admin, otherBuyer, otherSeller;

    @BeforeAll static void startDatabase() throws Exception {
        previousConfig = System.getProperty("marketplace.db.config");
        String executable = System.getenv("MYSQLD");
        if (executable == null) {
            Path windows = Path.of("C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqld.exe");
            executable = Files.isRegularFile(windows) ? windows.toString() : "mysqld";
        }
        String data = "--datadir=" + work.resolve("data");
        Path log = work.resolve("mysqld.log");
        Process init = new ProcessBuilder(executable, "--no-defaults", "--initialize-insecure", data)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!init.waitFor(90, TimeUnit.SECONDS) || init.exitValue() != 0) {
            init.destroyForcibly();
            throw new IllegalStateException("Could not initialize test MySQL: " + Files.readString(log));
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path startup = work.resolve("startup.sql");
        Files.writeString(startup, "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "';\n");
        server = new ProcessBuilder(executable, "--no-defaults", data, "--port=" + port,
                "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin", "--console",
                "--socket=" + work.resolve("mysql.sock"), "--init-file=" + startup)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        serverUrl = "jdbc:mysql://127.0.0.1:" + port + "/";
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        while (server.isAlive() && Instant.now().isBefore(deadline)) {
            try (Connection ignored = DriverManager.getConnection(serverUrl, "root", password)) { return; }
            catch (SQLException starting) { Thread.sleep(100); }
        }
        throw new IllegalStateException("Test MySQL did not start: " + Files.readString(log));
    }

    @BeforeEach void createDatabase() throws Exception {
        String database = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection conn = DriverManager.getConnection(serverUrl, "root", password);
             Statement sql = conn.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        try (Connection conn = DriverManager.getConnection(serverUrl + database, "root", password);
             Statement sql = conn.createStatement()) {
            String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"))
                    .replaceAll("(?m)^\\s*--[^\\r\\n]*", "").strip();
            for (String statement : schema.split(";")) {
                if (!statement.isBlank() && !statement.strip().equals("USE hkust_marketplace")) sql.execute(statement);
            }
        }
        Path config = work.resolve("database.properties");
        Files.writeString(config, "url=" + serverUrl + database + "\nusername=root\npassword=" + password + "\n");
        System.setProperty("marketplace.db.config", config.toString());
        buyer = users.findByUsername("alice");
        otherBuyer = users.findByUsername("bob");
        seller = users.findByUsername("charlie");
        otherSeller = users.findByUsername("diana");
        admin = users.findByUsername("admin");
    }

    @AfterAll static void stopDatabase() throws Exception {
        if (previousConfig == null) System.clearProperty("marketplace.db.config");
        else System.setProperty("marketplace.db.config", previousConfig);
        if (server == null || !server.isAlive()) return;
        try (Connection conn = DriverManager.getConnection(serverUrl, "root", password);
             Statement sql = conn.createStatement()) {
            sql.execute("SHUTDOWN");
        } catch (SQLException stopping) {
            // MySQL may drop the connection as it shuts down.
        } finally {
            if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly().waitFor();
        }
    }

    static Listing draft(ListingType type) {
        Listing item = new Listing();
        item.setTitle("Calculus textbook"); item.setDescription("First edition");
        item.setCategory("Books"); item.setCondition("Like New");
        item.setMeetupLocation("Library"); item.setType(type);
        if (type == ListingType.SELL) item.setPrice(42.50);
        if (type == ListingType.LEND) {
            item.setLendingDuration(5); item.setLendingUnit(LendingUnit.SECONDS);
        }
        return item;
    }

    Listing live(ListingType type) throws SQLException {
        Listing item = draft(type);
        listings.createListing(seller, item);
        assertTrue(listingRows.updateReview(item.getId(), ListingStatus.LIVE, null, item.getRowVersion()));
        return listings.getListing(seller, item.getId());
    }

    List<Notification> inbox(User user) throws SQLException {
        return new NotificationService(users, notificationRows, user.getRole()).getNotifications(user, true);
    }
}
