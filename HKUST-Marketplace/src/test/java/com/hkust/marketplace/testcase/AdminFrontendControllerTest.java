package com.hkust.marketplace.testcase;

import com.hkust.marketplace.controllers.NotificationBoard;
import com.hkust.marketplace.controllers.SellerProfilePane;
import com.hkust.marketplace.controllers.admin.AdminDashboardController;
import com.hkust.marketplace.controllers.admin.AdminLoginController;
import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.LendingUnit;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.ListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.ReviewRepository;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.ListingService;
import com.hkust.marketplace.services.PasswordHasher;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated JavaFX Frontend Controller Test Suite for {@link AdminLoginController}
 * and {@link AdminDashboardController}.
 *
 * <p>File Placement:
 * {@code HKUST-Marketplace/src/test/java/com/hkust/marketplace/testcase/AdminFrontendControllerTest.java}
 *
 * <p>How to Run:
 * <ol>
 *   <li>Run inside a normal desktop session (required by JavaFX) with MySQL 8 running and
 *       {@code hkust_marketplace} initialized from {@code src/main/resources/db/schema.sql}.</li>
 *   <li>Ensure DB credentials are configured via {@code MARKETPLACE_DB_USER} /
 *       {@code MARKETPLACE_DB_PASSWORD} or {@code ~/.hkust-marketplace/database.properties}.</li>
 *   <li>In IntelliJ IDEA: right-click this file -> <b>More Run/Debug -> Run with Coverage</b>.</li>
 *   <li>Or via Maven: {@code .\mvnw.cmd verify -Dtest=AdminFrontendControllerTest}.</li>
 * </ol>
 *
 * <p>How Modal Alerts & Dialogs Are Tested Without Hanging:
 * A lightweight JavaFX {@link Timeline} watcher runs on the FX Application Thread during each test.
 * Because JavaFX {@code showAndWait()} uses a nested event loop, the watcher continues ticking while
 * any {@code Alert}, {@code TextInputDialog}, or custom {@code Dialog} is open, matches queued
 * {@link DialogRule} actions (typing inputs, clicking OK/Cancel/Save), records alert messages for
 * assertion, and auto-dismisses any unexpected popup so tests never deadlock. Dialog actions are
 * deferred with {@link Platform#runLater(Runnable)} so a controller alert opened from a dialog's
 * button handler is not created during a JavaFX animation/layout pulse (which would throw
 * "showAndWait is not allowed during animation or layout processing").
 *
 * <p>Database Safety:
 * Every test uses a unique random prefix (e.g. {@code fx1a2b3c4d_}) for all temporary accounts,
 * listings, deals, reviews, and notifications, and cleans up all created rows in {@link #tearDown()}.
 * Seeded demo accounts ({@code admin}, {@code alice}, {@code bob}, {@code charlie}, {@code diana})
 * are never altered.
 */
@DisplayName("Task 3 Frontend: AdminLoginController & AdminDashboardController Tests")
@Timeout(120)
public class AdminFrontendControllerTest {

    /** Valid minimal 1x1 red PNG so JavaFX Image decoding succeeds (isError() == false). */
    private static final byte[] VALID_PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
            (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, (byte) 0xF8, (byte) 0xCF, (byte) 0xC0, 0x00,
            0x00, 0x03, 0x01, 0x01, 0x00, (byte) 0xC9, (byte) 0xFE, (byte) 0x92,
            (byte) 0xEF, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    private static Stage primaryStage;

    // Repositories & services for test setup and DB assertions
    private final UserRepository userRepository = new UserRepository();
    private final ListingRepository listingRepository = new ListingRepository();
    private final DealRepository dealRepository = new DealRepository();
    private final NotificationRepository notificationRepository = new NotificationRepository();
    private final ReviewRepository reviewRepository = new ReviewRepository();
    private final ListingService listingService = new ListingService(
            userRepository, listingRepository, notificationRepository);

    // Per-test isolation state
    private String prefix;
    private final List<String> createdUsernames = new ArrayList<>();
    private final List<String> createdListingIds = new ArrayList<>();
    private final List<String> createdDealIds = new ArrayList<>();
    private final List<String> createdReviewIds = new ArrayList<>();

    private User testAdmin;
    private User testBuyer;
    private User testSeller;
    private User inactiveSeller;

    // Modal dialog automation state (accessed on JavaFX Application Thread)
    private record DialogRule(Predicate<DialogPane> matcher, Consumer<DialogPane> handler) {}
    private final Deque<DialogRule> dialogRules = new ArrayDeque<>();
    private final List<String> capturedAlerts = new ArrayList<>();
    private final List<String> unexpectedAlerts = new ArrayList<>();
    private Timeline dialogWatcher;
    private Window strayDialogWindow;
    private long strayDialogFirstSeenMs;
    private boolean dialogActionPending;

    @BeforeAll
    static void startJavaFxAndCheckDatabase() throws Exception {
        // 1. Verify database connectivity up front
        try (Connection conn = DatabaseManager.getConnection()) {
            assertNotNull(conn, "MySQL connection must be available for frontend controller tests.");
        }

        // 2. Start JavaFX toolkit once (safe if already started by another test)
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            assertTrue(started.await(15, TimeUnit.SECONDS), "Timed out starting JavaFX runtime.");
        } catch (IllegalStateException alreadyInitialized) {
            // Toolkit is already running in this JVM
        }
        Platform.setImplicitExit(false);

        // 3. Initialize a real primary Stage for SceneManager and modal dialog owners
        runOnFx(() -> {
            primaryStage = new Stage();
            primaryStage.setTitle("AdminFrontendControllerTest");
            SceneManager.init(primaryStage);
            primaryStage.show();
        });
    }

    @AfterAll
    static void hidePrimaryStage() throws Exception {
        if (primaryStage != null) {
            runOnFx(() -> {
                SessionManager.logout();
                primaryStage.hide();
            });
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        prefix = "fx" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_";
        createdUsernames.clear();
        createdListingIds.clear();
        createdDealIds.clear();
        createdReviewIds.clear();

        testAdmin = createTestUser("admin", "Frontend Test Admin", "AdminPass1", UserRole.ADMIN, true);
        testBuyer = createTestUser("buyer", "Frontend Test Buyer", "BuyerPass1", UserRole.BUYER, true);
        testSeller = createTestUser("seller", "Frontend Test Seller", "SellerPass1", UserRole.SELLER, true);
        inactiveSeller = createTestUser("offsel", "Inactive Test Seller", "SellerPass1", UserRole.SELLER, false);

        runOnFx(() -> {
            SessionManager.logout();
            dialogRules.clear();
            capturedAlerts.clear();
            unexpectedAlerts.clear();
            strayDialogWindow = null;
            strayDialogFirstSeenMs = 0L;
            dialogActionPending = false;
            startDialogWatcher();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        runOnFx(() -> {
            stopDialogWatcher();
            dialogActionPending = false;
            closeAnyStrayDialogs();
            SessionManager.logout();
        });

        // Clean up all DB rows created during the test (including listings created via UI forms)
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Collect any listings created via the UI form (not tracked manually in createdListingIds)
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM listings WHERE itemName LIKE ?")) {
                    ps.setString(1, prefix + "%");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String id = rs.getString(1);
                            if (!createdListingIds.contains(id)) createdListingIds.add(id);
                        }
                    }
                }

                for (String reviewId : createdReviewIds) {
                    executeDelete(conn, "DELETE FROM reviews WHERE id = ?", reviewId);
                }
                for (String listingId : createdListingIds) {
                    executeDelete(conn, "DELETE FROM listing_reports WHERE listing_id = ?", listingId);
                }
                for (String dealId : createdDealIds) {
                    executeDelete(conn, "DELETE FROM deals WHERE id = ?", dealId);
                }
                for (String listingId : createdListingIds) {
                    executeDelete(conn, "DELETE FROM deals WHERE listing_id = ?", listingId);
                    executeDelete(conn, "DELETE FROM listings WHERE id = ?", listingId);
                }
                for (String username : createdUsernames) {
                    executeDelete(conn, "DELETE FROM notifications WHERE username = ?", username);
                }
                executeDelete(conn, "DELETE FROM notifications WHERE message LIKE ?", "%" + prefix + "%");
                for (String username : createdUsernames) {
                    executeDelete(conn, "DELETE FROM users WHERE username = ?", username);
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    // =========================================================================
    // 1. ADMIN LOGIN CONTROLLER TESTS (AdminLoginController.java)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminLoginController")
    class AdminLoginControllerTests {

        /**
         * Test Case: TC-UI-LOGIN-01
         * Content: Verify AdminLoginView.fxml is login-only (no registration controls),
         *          errorLabel starts hidden, and clicking "← Back to Welcome" switches to WelcomeView.
         */
        @Test
        @DisplayName("TC-UI-LOGIN-01: Admin login screen has no registration link and Back navigates to Welcome")
        void loginScreenLayoutAndGoWelcome() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminLoginController> view = loadAdminLoginView();
                Label errorLabel = getField(view.controller(), "errorLabel", Label.class);
                assertFalse(errorLabel.isVisible(), "Error label should be hidden initially.");
                assertFalse(errorLabel.isManaged());

                // Confirm no registration button or hyperlink exists in AdminLoginView
                List<Button> buttons = findAll(view.root(), Button.class);
                List<Hyperlink> links = findAll(view.root(), Hyperlink.class);
                assertTrue(buttons.stream().noneMatch(b -> b.getText().toLowerCase().contains("register")));
                assertTrue(links.stream().noneMatch(h -> h.getText().toLowerCase().contains("register")));

                // Click "← Back to Welcome" -> calls goWelcome() -> SceneManager.showWelcome()
                Hyperlink backLink = links.stream()
                        .filter(h -> h.getText().contains("Back to Welcome"))
                        .findFirst().orElseThrow();
                backLink.fire();

                // WelcomeView displays "HKUST Marketplace"
                assertTrue(findAll(primaryStage.getScene().getRoot(), Label.class).stream()
                        .anyMatch(l -> "HKUST Marketplace".equals(l.getText())));
            });
        }

        /**
         * Test Case: TC-UI-LOGIN-02
         * Content: Verify AdminLoginController.handleLogin() displays exact inline error feedback
         *          for blank inputs, unknown user, wrong password, wrong role (Buyer/Seller),
         *          and deactivated admin account.
         */
        @Test
        @DisplayName("TC-UI-LOGIN-02: Login validation and error messages render in errorLabel")
        void loginErrorStatesDisplayCorrectMessages() throws Exception {
            User disabledAdmin = createTestUser("offadm", "Disabled Admin", "AdminPass1", UserRole.ADMIN, false);

            runOnFx(() -> {
                LoadedView<AdminLoginController> view = loadAdminLoginView();
                TextField userField = getField(view.controller(), "usernameField", TextField.class);
                PasswordField passField = getField(view.controller(), "passwordField", PasswordField.class);
                Label errorLabel = getField(view.controller(), "errorLabel", Label.class);
                Button loginBtn = findButtonByText(view.root(), "Login as Admin");

                // 1. Blank credentials
                userField.setText("   ");
                passField.setText("");
                loginBtn.fire();
                assertTrue(errorLabel.isVisible());
                assertEquals("Please enter credentials.", errorLabel.getText());
                assertNull(SessionManager.getCurrentUser());

                // 2. Non-existent user
                userField.setText(prefix + "nobody");
                passField.setText("AdminPass1");
                loginBtn.fire();
                assertEquals("User not found.", errorLabel.getText());

                // 3. Wrong password
                userField.setText(testAdmin.getUsername());
                passField.setText("WrongPassword9");
                loginBtn.fire();
                assertEquals("Incorrect password.", errorLabel.getText());

                // 4. Buyer / Seller role rejected on Admin portal
                userField.setText(testBuyer.getUsername());
                passField.setText("BuyerPass1");
                loginBtn.fire();
                assertEquals("This account is not an Admin account. Use the correct portal.", errorLabel.getText());

                // 5. Deactivated admin rejected
                userField.setText(disabledAdmin.getUsername());
                passField.setText("AdminPass1");
                loginBtn.fire();
                assertEquals("Account deactivated.", errorLabel.getText());
                assertFalse(SessionManager.isLoggedIn());
            });
        }

        /**
         * Test Case: TC-UI-LOGIN-03
         * Content: Successful login via AdminLoginController populates SessionManager and switches
         *          the stage to AdminDashboardView showing the signed-in admin's name.
         */
        @Test
        @DisplayName("TC-UI-LOGIN-03: Valid admin login opens AdminDashboardView and populates session")
        void validLoginOpensAdminDashboard() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminLoginController> view = loadAdminLoginView();
                TextField userField = getField(view.controller(), "usernameField", TextField.class);
                PasswordField passField = getField(view.controller(), "passwordField", PasswordField.class);
                Button loginBtn = findButtonByText(view.root(), "Login as Admin");

                userField.setText("  " + testAdmin.getUsername() + "  ");
                passField.setText("AdminPass1");
                loginBtn.fire();

                assertTrue(SessionManager.isAdmin());
                assertEquals(testAdmin.getUsername(), SessionManager.getCurrentUser().getUsername());

                // Verify AdminDashboardView is now active on primaryStage
                Parent dashboardRoot = primaryStage.getScene().getRoot();
                Label feedback = (Label) dashboardRoot.lookup(".login-success");
                assertNotNull(feedback);
                assertEquals("Log in successful", feedback.getText());
                assertTrue(findAll(dashboardRoot, Label.class).stream()
                        .anyMatch(l -> ("Admin: " + testAdmin.getFullName()).equals(l.getText())));
                assertTrue(findAll(dashboardRoot, Label.class).stream()
                        .anyMatch(l -> "Listing Review & Approval".equals(l.getText())));
            });
        }
    }

    // =========================================================================
    // 2. LISTING REVIEW & LOGOUT UI TESTS (AdminDashboardController)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminDashboardController: Listing Review & Logout")
    class DashboardReviewAndLogoutTests {

        /**
         * Test Case: TC-UI-REV-01
         * Content: Verify initialize() and showReview() render pending listing cards with all
         *          required submission details (title, status badge, seller username & full name,
         *          category, listing type, meet-up location, formatted submitted date, and photo thumbnail).
         */
        @Test
        @DisplayName("TC-UI-REV-01: Review screen renders all pending card fields and decoded photo thumbnail")
        void reviewScreenDisplaysPendingCardDetailsAndPhotos() throws Exception {
            String title = prefix + "Review Camera";
            createSellerListing(testSeller, title, ListingType.SELL, 320.00, null, null, List.of(VALID_PNG_BYTES));

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                Label pageTitle = getField(view.controller(), "pageTitle", Label.class);
                Label welcomeLabel = getField(view.controller(), "welcomeLabel", Label.class);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                assertEquals("Listing Review & Approval", pageTitle.getText());
                assertEquals("Admin: " + testAdmin.getFullName(), welcomeLabel.getText());

                Parent card = findCardContainingText(contentPane, title);
                assertNotNull(card, "Pending listing card must appear in Listing Review.");
                List<String> texts = allLabelTexts(card);

                assertTrue(texts.contains(title));
                assertTrue(texts.contains("PENDING REVIEW"));
                assertTrue(texts.contains("@" + testSeller.getUsername()));
                assertTrue(texts.contains(testSeller.getFullName()));
                assertTrue(texts.contains("Electronics"));
                assertTrue(texts.contains("For Sale"));
                assertTrue(texts.contains("Library"));
                assertTrue(texts.contains("Submitted:"), "The submitted-date row should be rendered.");
                assertTrue(texts.stream().anyMatch(t -> t.matches("\\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}")),
                        "The submitted date should use the dd MMM yyyy HH:mm format.");
                assertEquals(1, findAll(card, ImageView.class).size(),
                        "Valid PNG byte[] should render one ImageView thumbnail.");
            });
        }

        /**
         * Test Case: TC-UI-REV-02
         * Content: Exercise createPendingCard() badge branches (LIVE, REJECTED, SOLD), missing fields ("—"),
         *          invalid/empty photo bytes, and shortenForNotification() truncation (>500 chars).
         */
        @Test
        @DisplayName("TC-UI-REV-02: createPendingCard badge/photo fallbacks and shortenForNotification truncation")
        void reviewCardBranchesAndShortenHelper() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                AdminDashboardController ctrl = view.controller();

                // Test LIVE, REJECTED, and SOLD badge branches + missing fields & invalid photo blobs
                for (ListingStatus status : List.of(ListingStatus.LIVE, ListingStatus.REJECTED, ListingStatus.SOLD)) {
                    Listing sample = new Listing();
                    sample.setId(UUID.randomUUID().toString());
                    sample.setTitle(prefix + status.name());
                    sample.setSellerUsername(testSeller.getUsername());
                    sample.setSellerFullName(""); // triggers "—" fallback in addDetailRow
                    sample.setType(ListingType.GIVEAWAY);
                    sample.setStatus(status);
                    sample.setPostedAt(null); // triggers "—" fallback
                    sample.setPhotos(status == ListingStatus.LIVE
                            ? List.of()
                            : List.of(new byte[0], new byte[]{9, 9, 9})); // empty + invalid image skipped

                    VBox card = invokePrivate(ctrl, "createPendingCard",
                            new Class<?>[]{Listing.class}, new Object[]{sample}, VBox.class);
                    List<String> texts = allLabelTexts(card);
                    assertTrue(texts.contains(status.name().replace('_', ' ')));
                    assertTrue(texts.contains("—"));
                    if (status == ListingStatus.LIVE) {
                        assertTrue(texts.contains("No photos uploaded."));
                    } else {
                        assertEquals(0, findAll(card, ImageView.class).size());
                    }
                }

                // Test shortenForNotification: null, short, and >500 characters
                assertEquals("", invokeStaticPrivate(AdminDashboardController.class, "shortenForNotification",
                        new Class<?>[]{String.class}, new Object[]{null}, String.class));
                assertEquals("Short reason", invokeStaticPrivate(AdminDashboardController.class, "shortenForNotification",
                        new Class<?>[]{String.class}, new Object[]{"  Short reason  "}, String.class));
                String longReason = "A".repeat(520);
                String shortened = invokeStaticPrivate(AdminDashboardController.class, "shortenForNotification",
                        new Class<?>[]{String.class}, new Object[]{longReason}, String.class);
                assertEquals(503, shortened.length());
                assertTrue(shortened.endsWith("..."));
            });
        }

        /**
         * Test Case: TC-UI-REV-03
         * Content: Clicking Approve -> Cancel leaves listing pending; Approve -> OK persists LIVE,
         *          notifies the seller ("Listing Approved"), and refreshes the review queue.
         */
        @Test
        @DisplayName("TC-UI-REV-03: Approve button respects Cancel, persists LIVE on OK, and notifies seller")
        void approveButtonFlow() throws Exception {
            String title = prefix + "Approve Desk";
            Listing listing = createSellerListing(testSeller, title, ListingType.SELL, 150.00, null, null, List.of());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                // Step 1: Click Approve then Cancel -> remains PENDING_REVIEW
                expectDialogMatching(p -> containsText(p, "Approve this listing?"), AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(findCardContainingText(contentPane, title), "Approve").fire();
                assertNotNull(findCardContainingText(contentPane, title));

                // Step 2: Click Approve then OK -> transitions to LIVE and disappears from queue
                expectDialogMatching(p -> containsText(p, "Approve this listing?"), AdminFrontendControllerTest.this::clickOk);
                findButtonByText(findCardContainingText(contentPane, title), "Approve").fire();
                assertNull(findCardContainingText(contentPane, title));
            });

            Listing after = listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername());
            assertEquals(ListingStatus.LIVE, after.getStatus());
            assertTrue(notificationRepository.findByRecipient(testSeller.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Approved".equals(n.getTitle()) && n.getMessage().contains(title)));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-REV-04
         * Content: Clicking Reject validates blank reason ("Reason is required."), then accepts a
         *          written reason, persists REJECTED, and sends a "Listing Rejected" notification.
         */
        @Test
        @DisplayName("TC-UI-REV-04: Reject button blocks blank reason, persists REJECTED, and notifies seller")
        void rejectButtonFlow() throws Exception {
            String title = prefix + "Reject Broken Lamp";
            Listing listing = createSellerListing(testSeller, title, ListingType.SELL, 45.00, null, null, List.of());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                // Step 1: Blank reason -> shows "Reason is required." warning
                expectTextInputDialog("Please provide a reason for rejection", "   ");
                expectAlertAndClickOk("Reason is required.");
                findButtonByText(findCardContainingText(contentPane, title), "Reject").fire();
                assertNotNull(findCardContainingText(contentPane, title));

                // Step 2: Valid reason -> rejects listing and removes card
                expectTextInputDialog("Please provide a reason for rejection", "Missing item photo");
                findButtonByText(findCardContainingText(contentPane, title), "Reject").fire();
                assertNull(findCardContainingText(contentPane, title));
            });

            Listing after = listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername());
            assertEquals(ListingStatus.REJECTED, after.getStatus());
            assertEquals("Missing item photo", after.getRejectionReason());
            assertTrue(notificationRepository.findByRecipient(testSeller.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Rejected".equals(n.getTitle())
                            && n.getMessage().contains("Missing item photo")));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-REV-05
         * Content: Stale optimistic-lock conflict during approve_listing / reject_listing shows
         *          "This listing changed while you were reviewing it. The review queue will reload."
         *          and Logout clears SessionManager and returns to WelcomeView.
         */
        @Test
        @DisplayName("TC-UI-REV-05: Stale review conflict shows warning alert and Logout returns to Welcome")
        void staleReviewWarningAndLogout() throws Exception {
            String title = prefix + "Stale Textbook";
            Listing listing = createSellerListing(testSeller, title, ListingType.SELL, 90.00, null, null, List.of());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                // Seller edits listing in DB behind the admin's back (row_version 1 -> 2)
                try {
                    Listing edited = buildListingModel(prefix + "Edited Textbook", ListingType.SELL,
                            110.00, null, null, List.of());
                    listingRepository.updateOwned(listing.getId(), testSeller.getUsername(), edited);
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }

                // Admin clicks Approve on stale card -> conflict warning
                expectDialogMatching(p -> containsText(p, "Approve this listing?"), AdminFrontendControllerTest.this::clickOk);
                expectAlertAndClickOk("This listing changed while you were reviewing it.");
                findButtonByText(findCardContainingText(contentPane, title), "Approve").fire();

                // Queue reloaded with seller's edited title
                assertNotNull(findCardContainingText(contentPane, prefix + "Edited Textbook"));

                // Logout button clears session and navigates to WelcomeView
                findButtonByText(view.root(), "  Logout").fire();
                assertNull(SessionManager.getCurrentUser());
                assertTrue(findAll(primaryStage.getScene().getRoot(), Label.class).stream()
                        .anyMatch(l -> "HKUST Marketplace".equals(l.getText())));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }
    }

    // =========================================================================
    // 3. MANAGE ALL LISTINGS UI TESTS (AdminDashboardController)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminDashboardController: Manage All Listings")
    class ManageAllListingsUITests {

        /**
         * Test Case: TC-UI-LST-01
         * Content: Verify Create Listing form enforces active-sellers-only list, type-first gating,
         *          type-switching field resets, aggregated validation errors, and successful
         *          creation of a Lend listing.
         */
        @Test
        @DisplayName("TC-UI-LST-01: Create Listing form gating, validation, and Lend listing creation")
        @SuppressWarnings("unchecked")
        void createListingFormValidationAndSubmit() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                // Open Manage All Listings -> click Create Listing
                findButtonByText(view.root(), "  All Listings").fire();
                findButtonByText(contentPane, "Create Listing").fire();

                ComboBox<User> sellerCombo = findComboBoxByPrompt(contentPane, "Select Seller");
                ComboBox<String> typeCombo = findComboBoxByPrompt(contentPane, "Listing type *");
                TextField nameField = findTextFieldByPrompt(contentPane, "Item name *");
                ComboBox<String> catCombo = findComboBoxByPrompt(contentPane, "Category *");
                ComboBox<String> condCombo = findComboBoxByPrompt(contentPane, "Condition *");
                TextArea descArea = findFirstExact(contentPane, TextArea.class);
                TextField priceField = findTextFieldByPrompt(contentPane, "Price (HK$)");
                TextField durationField = findTextFieldByPrompt(contentPane, "Duration");
                ComboBox<String> meetupCombo = findComboBoxByPrompt(contentPane, "Meet-up location *");
                Button submitBtn = findButtonByText(contentPane, "Create Listing");

                // Active seller appears; inactive seller & buyer are excluded
                assertTrue(sellerCombo.getItems().stream().anyMatch(u -> u.getUsername().equals(testSeller.getUsername())));
                assertTrue(sellerCombo.getItems().stream().noneMatch(u -> u.getUsername().equals(inactiveSeller.getUsername())));
                assertTrue(sellerCombo.getItems().stream().noneMatch(u -> u.getUsername().equals(testBuyer.getUsername())));

                // Type-first gating: details container is disabled until a listing type is selected
                assertTrue(nameField.isDisabled());
                typeCombo.setValue("Sell");
                assertFalse(nameField.isDisabled());
                assertFalse(priceField.isDisabled());

                // Validation failure: missing fields + negative price
                priceField.setText("-5");
                expectAlertAndClickOk("Price must be a positive number.");
                submitBtn.fire();

                // Validation failure: > 2 decimal places
                priceField.setText("12.345");
                expectAlertAndClickOk("Price must have at most two decimal places.");
                submitBtn.fire();

                // Switching type to Lend clears priceField and tests invalid lending duration
                typeCombo.setValue("Lend");
                assertEquals("", priceField.getText());
                durationField.setText("0");
                expectAlertAndClickOk("Lending duration must be a positive whole number.");
                submitBtn.fire();

                // Valid Lend submission
                User chosenSeller = sellerCombo.getItems().stream()
                        .filter(u -> u.getUsername().equals(testSeller.getUsername()))
                        .findFirst().orElseThrow();
                sellerCombo.setValue(chosenSeller);
                nameField.setText(prefix + "UI Created Calculator");
                catCombo.setValue("Electronics");
                condCombo.setValue("Like New");
                descArea.setText("Created from Admin UI form");
                durationField.setText("5");
                meetupCombo.setValue("Library");

                expectAlertAndClickOk("Listing created. Status: Pending Review.");
                submitBtn.fire();

                // Navigates back to All Listings showing the new item
                assertNotNull(findCardContainingText(contentPane, prefix + "UI Created Calculator"));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-LST-02
         * Content: Clicking Edit opens pre-filled form with read-only seller label and photo previews
         *          (exercising refreshPhotoPreviews, per-photo Remove, and Clear Photos), confirms save,
         *          updates DB to PENDING_REVIEW, and navigates back to All Listings.
         */
        @Test
        @DisplayName("TC-UI-LST-02: Edit Listing pre-fills data/photos, handles photo removal, and saves changes")
        void editListingFormAndPhotoPreviews() throws Exception {
            String oldTitle = prefix + "Original Monitor";
            String newTitle = prefix + "Edited Monitor";
            Listing listing = createSellerListing(testSeller, oldTitle, ListingType.SELL,
                    500.00, null, null, List.of(VALID_PNG_BYTES, VALID_PNG_BYTES));
            approveListing(listing);

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  All Listings").fire();
                Parent row = findCardContainingText(contentPane, oldTitle);
                assertNotNull(row);
                findButtonByText(row, "Edit").fire();

                // Pre-filled title, price, and 2 photo previews
                TextField nameField = findTextFieldByPrompt(contentPane, "Item name *");
                TextField priceField = findTextFieldByPrompt(contentPane, "Price (HK$)");
                assertEquals(oldTitle, nameField.getText());
                assertEquals(500.0, Double.parseDouble(priceField.getText()), 0.001);
                assertTrue(allLabelTexts(contentPane).stream().anyMatch(t -> t.startsWith("Seller: ")),
                        "Edit form should display the read-only seller label.");
                assertTrue(allLabelTexts(contentPane).contains("2 of 6 photos selected"));

                // Click per-photo Remove button inside FlowPane -> drops to 1 of 6
                FlowPane previews = findFirstExact(contentPane, FlowPane.class);
                findButtonByText(previews, "Remove").fire();
                assertTrue(allLabelTexts(contentPane).contains("1 of 6 photos selected"));

                // Click Clear Photos -> drops to 0 of 6
                findButtonByText(contentPane, "Clear Photos").fire();
                assertTrue(allLabelTexts(contentPane).contains("0 of 6 photos selected"));

                // Save changes -> confirm dialog -> success alert
                nameField.setText(newTitle);
                priceField.setText("450");
                expectDialogMatching(p -> containsText(p, "The listing will return to Pending Review."), AdminFrontendControllerTest.this::clickOk);
                expectAlertAndClickOk("Listing updated. Status: Pending Review.");
                findButtonByText(contentPane, "Save Changes").fire();

                assertNotNull(findCardContainingText(contentPane, newTitle));
            });

            Listing updated = listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername());
            assertEquals(newTitle, updated.getTitle());
            assertEquals(ListingStatus.PENDING_REVIEW, updated.getStatus());
            assertEquals(0, updated.getPhotos().size());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-LST-03
         * Content: Clicking Remove opens confirmation + Removal Reason dialog, blocks blank reason,
         *          soft-deletes listing on valid reason, removes row from UI, and warns on already-removed target.
         */
        @Test
        @DisplayName("TC-UI-LST-03: Remove Listing validates reason, soft-deletes listing, and refreshes UI")
        void removeListingUiFlow() throws Exception {
            String title = prefix + "Banned Item";
            Listing listing = createSellerListing(testSeller, title, ListingType.SELL, 200.00, null, null, List.of());
            approveListing(listing);

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  All Listings").fire();
                Parent row = findCardContainingText(contentPane, title);
                assertNotNull(row);

                // Step 1: Confirm OK -> blank reason -> warning "Reason is required."
                expectDialogMatching(p -> containsText(p, "marked as REMOVED"), AdminFrontendControllerTest.this::clickOk);
                expectTextInputDialog("Please provide a reason for removal", "   ");
                expectAlertAndClickOk("Reason is required.");
                findButtonByText(row, "Remove").fire();
                assertNotNull(findCardContainingText(contentPane, title));

                // Step 2: Confirm OK -> valid reason -> "Listing removed." -> row disappears
                expectDialogMatching(p -> containsText(p, "marked as REMOVED"), AdminFrontendControllerTest.this::clickOk);
                expectTextInputDialog("Please provide a reason for removal", "Prohibited campus item");
                expectAlertAndClickOk("Listing removed.");
                findButtonByText(findCardContainingText(contentPane, title), "Remove").fire();
                assertNull(findCardContainingText(contentPane, title));

                // Step 3: Calling removeListing again warns already removed
                expectAlertAndClickOk("This listing no longer exists or was already removed.");
                invokePrivate(view.controller(), "removeListing",
                        new Class<?>[]{Listing.class, String.class},
                        new Object[]{listing, "Duplicate"}, Void.class);
            });

            assertNull(listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername()));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }
    }

    // =========================================================================
    // 4. MANAGE ALL USERS UI TESTS (AdminDashboardController)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminDashboardController: Manage All Users")
    class ManageUsersUITests {

        /**
         * Test Case: TC-UI-USR-01
         * Content: Manage Users lists Buyers & Sellers (excluding ADMINs), exposes the
         *          Edit/Deactivate/Delete controls, and toggles Deactivate/Activate in MySQL and UI.
         */
        @Test
        @DisplayName("TC-UI-USR-01: Manage Users hides admins and toggles user Active/Deactivated state")
        void showUsersAndToggleActive() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  Manage Users").fire();

                // Admin accounts are hidden; buyer and seller are shown
                assertNull(findCardContainingText(contentPane, "@" + testAdmin.getUsername()));
                Parent buyerRow = findCardContainingText(contentPane, "@" + testBuyer.getUsername());
                Parent sellerRow = findCardContainingText(contentPane, "@" + testSeller.getUsername());
                assertNotNull(buyerRow);
                assertNotNull(sellerRow);
                // Both non-admin rows expose the same management actions; there is no
                // role-specific "Seller Profile" button in the current dashboard.
                assertNotNull(findButtonByText(sellerRow, "Edit"));
                assertNotNull(findButtonByText(sellerRow, "Delete"));
                assertNotNull(findButtonByText(buyerRow, "Edit"));
                assertNotNull(findButtonByText(buyerRow, "Delete"));

                // Deactivate buyer -> row reloads showing Deactivated + Activate button
                findButtonByText(buyerRow, "Deactivate").fire();
                Parent deactivatedRow = findCardContainingText(contentPane, "@" + testBuyer.getUsername());
                assertTrue(allLabelTexts(deactivatedRow).stream().anyMatch(t -> t.contains("Deactivated")));
                assertNotNull(findButtonByText(deactivatedRow, "Activate"));

                // Activate buyer again -> row reloads showing Active + Deactivate button
                findButtonByText(deactivatedRow, "Activate").fire();
                Parent reactivatedRow = findCardContainingText(contentPane, "@" + testBuyer.getUsername());
                assertTrue(allLabelTexts(reactivatedRow).stream().anyMatch(t -> t.contains("Active")));
                assertNotNull(findButtonByText(reactivatedRow, "Deactivate"));
            });

            assertTrue(userRepository.findByUsername(testBuyer.getUsername()).isActive());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-USR-02
         * Content: Edit User modal dialog validates blank Full Name without closing, saves Buyer
         *          (Residence Hall) and Seller (Bio + Preferred Meet-up = "None" -> NULL) updates,
         *          and warns when target user no longer exists.
         */
        @Test
        @DisplayName("TC-UI-USR-02: Edit User dialog validates inputs and saves Buyer & Seller profiles")
        @SuppressWarnings("unchecked")
        void editUserDialogValidationAndSave() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  Manage Users").fire();

                // 1. Edit Buyer: first submit empty name (stays open), then submit valid name + Hall 8
                String buyerHeader = "Editing @" + testBuyer.getUsername() + " (BUYER)";
                expectDialogMatching(p -> buyerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("   ");
                    clickOk(pane);
                });
                expectAlertAndClickOk("Full Name cannot be empty.");
                expectDialogMatching(p -> buyerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("Edited Buyer UI");
                    ComboBox<String> hallCombo = findFirstExact(pane, ComboBox.class);
                    hallCombo.setValue("Hall 8");
                    clickOk(pane);
                });
                expectAlertAndClickOk("Profile saved.");

                findButtonByText(findCardContainingText(contentPane, "@" + testBuyer.getUsername()), "Edit").fire();
                assertTrue(allLabelTexts(findCardContainingText(contentPane, "@" + testBuyer.getUsername()))
                        .stream().anyMatch(t -> t.contains("Edited Buyer UI")));

                // 2. Edit Seller: update full name, bio, and set Preferred Meet-up to "None"
                String sellerHeader = "Editing @" + testSeller.getUsername() + " (SELLER)";
                expectDialogMatching(p -> sellerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("Edited Seller UI");
                    findFirstExact(pane, TextArea.class).setText("Updated seller bio via UI");
                    ComboBox<String> meetupCombo = findFirstExact(pane, ComboBox.class);
                    meetupCombo.setValue("None");
                    clickOk(pane);
                });
                expectAlertAndClickOk("Profile saved.");

                findButtonByText(findCardContainingText(contentPane, "@" + testSeller.getUsername()), "Edit").fire();

                // 3. Missing user warning
                expectAlertAndClickOk("no longer exists");
                invokePrivate(view.controller(), "openEditUserDialog",
                        new Class<?>[]{String.class}, new Object[]{prefix + "ghost_user"}, Void.class);
            });

            User updatedBuyer = userRepository.findByUsername(testBuyer.getUsername());
            assertEquals("Edited Buyer UI", updatedBuyer.getFullName());
            assertEquals("Hall 8", updatedBuyer.getResidenceHall());

            User updatedSeller = userRepository.findByUsername(testSeller.getUsername());
            assertEquals("Edited Seller UI", updatedSeller.getFullName());
            assertEquals("Updated seller bio via UI", updatedSeller.getBio());
            assertNull(updatedSeller.getPreferredMeetup());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-USR-03
         * Content: Clicking Delete on a seller opens confirmation, permanently deletes the account
         *          on OK, soft-deletes their listings as REMOVED, and removes the user row from UI.
         */
        @Test
        @DisplayName("TC-UI-USR-03: Delete User confirmation removes user from DB/UI and marks listings REMOVED")
        void deleteUserUiFlow() throws Exception {
            Listing sellerListing = createSellerListing(testSeller, prefix + "Orphaned Book",
                    ListingType.SELL, 70.00, null, null, List.of());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  Manage Users").fire();
                Parent sellerRow = findCardContainingText(contentPane, "@" + testSeller.getUsername());
                assertNotNull(sellerRow);

                expectDialogMatching(p -> containsText(p, "Permanently delete user @" + testSeller.getUsername()),
                        AdminFrontendControllerTest.this::clickOk);
                expectAlertAndClickOk("deleted permanently. Their listings were marked as REMOVED.");

                findButtonByText(sellerRow, "Delete").fire();
                assertNull(findCardContainingText(contentPane, "@" + testSeller.getUsername()));
            });

            assertNull(userRepository.findByUsername(testSeller.getUsername()));
            assertEquals("REMOVED", queryStringColumn("SELECT listingStatus FROM listings WHERE id = ?",
                    sellerListing.getId()));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }
    }

    // =========================================================================
    // 5. ALL DEALS RECORD UI TESTS (AdminDashboardController)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminDashboardController: All Deals Record")
    class AllDealsUITests {

        /**
         * Test Case: TC-UI-DLS-01
         * Content: All Deals screen populates 7-column TableView, filters by Deal Type
         *          (Bought/Borrowed/Received) and keyword search, resets cleanly, and renders
         *          "(deleted user)" when a participant account has been deleted.
         */
        @Test
        @DisplayName("TC-UI-DLS-01: All Deals TableView filters by type/keyword and renders deleted user placeholder")
        @SuppressWarnings("unchecked")
        void showDealsFiltersAndFormatting() throws Exception {
            Listing sellItem = createSellerListing(testSeller, prefix + "DealMonitor", ListingType.SELL,
                    400.00, null, null, List.of());
            Listing lendItem = createSellerListing(testSeller, prefix + "DealCalc", ListingType.LEND,
                    null, 5, LendingUnit.DAYS, List.of());
            approveListing(sellItem);
            approveListing(lendItem);

            Deal sellDeal = createDeal(testBuyer, sellItem, 380.00, null, null);
            LocalDateTime bStart = LocalDateTime.now().plusSeconds(1);
            Deal lendDeal = createDeal(testBuyer, lendItem, null, bStart, bStart.plusDays(2));

            // Permanently delete testBuyer so buyer_username becomes NULL (deleted = 1)
            new AuthService(userRepository).deleteUserAsAdmin(testAdmin, testBuyer.getUsername());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  All Deals").fire();

                TextField searchField = findTextFieldByPrompt(contentPane,
                        "Search by item title, buyer or seller username...");
                ComboBox<String> typeFilter = findFirstExact(contentPane, ComboBox.class);
                Button searchBtn = findButtonByText(contentPane, "Search");
                Button resetBtn = findButtonByText(contentPane, "Reset");
                TableView<Deal> table = findFirstExact(contentPane, TableView.class);

                assertEquals(8, table.getColumns().size(), "Title/buyer/seller/type/terms/start/end/status");

                // Search by unique prefix -> finds both deals
                searchField.setText(prefix);
                searchBtn.fire();
                assertEquals(2, table.getItems().size());

                // Filter to "Bought" -> only sellDeal; check cell value factories including "(deleted user)"
                typeFilter.getSelectionModel().select("Bought");
                searchBtn.fire(); // programmatic select() does not fire ComboBox.onAction
                assertEquals(1, table.getItems().size());
                assertEquals(prefix + "DealMonitor", table.getColumns().get(0).getCellData(0));
                assertEquals("(deleted user)", table.getColumns().get(1).getCellData(0));
                assertEquals(testSeller.getUsername(), table.getColumns().get(2).getCellData(0));
                assertEquals("Bought", table.getColumns().get(3).getCellData(0));
                assertEquals("—", table.getColumns().get(6).getCellData(0));
                assertEquals("Cancelled", table.getColumns().get(7).getCellData(0));

                // Filter to "Borrowed" -> only lendDeal
                typeFilter.getSelectionModel().select("Borrowed");
                searchBtn.fire();
                assertEquals(1, table.getItems().size());
                assertEquals("Borrowed", table.getColumns().get(3).getCellData(0));

                // Reset clears search and restores All Types
                resetBtn.fire();
                assertEquals("", searchField.getText());
                assertEquals("All Types", typeFilter.getValue());
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }
    }

    // =========================================================================
    // 6. SELLER RATINGS OVERVIEW UI TESTS (AdminDashboardController)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminDashboardController: Seller Ratings Overview")
    class SellerRatingsUITests {

        /**
         * Test Case: TC-UI-RAT-01
         * Content: Seller Ratings screen filters by seller, rating score, and inclusive dates,
         *          displays validation errors for invalid/inverted dates, hides inappropriate reviews
         *          via the Hide button (preserving active filters), and handles missing review warning.
         */
        @Test
        @DisplayName("TC-UI-RAT-01: Seller Ratings filters, date validation, and Hide button persistence")
        @SuppressWarnings("unchecked")
        void showRatingsFilterValidationAndHide() throws Exception {
            LocalDate today = LocalDate.now();
            String reviewId = createCompletedDealWithReview(testBuyer, testSeller, prefix + "RatedItem",
                    2, "Rude seller comment " + prefix, today.atTime(10, 0));

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);

                findButtonByText(view.root(), "  Seller Ratings").fire();

                TextField sellerFilter = findTextFieldByPrompt(contentPane, "Search seller");
                ComboBox<String> scoreFilter = findFirstExact(contentPane, ComboBox.class);
                TextField fromDate = findTextFieldByPrompt(contentPane, "From (yyyy-MM-dd)");
                TextField toDate = findTextFieldByPrompt(contentPane, "To (yyyy-MM-dd)");
                Button searchBtn = findButtonByText(contentPane, "Search");
                Button resetBtn = findButtonByText(contentPane, "Reset");

                // 1. Invalid From date format
                sellerFilter.setText(testSeller.getUsername());
                fromDate.setText("2026/11/10");
                searchBtn.fire();
                assertTrue(allLabelTexts(contentPane).contains("From date must use yyyy-MM-dd."));

                // 2. Inverted date range
                fromDate.setText(today.plusDays(1).toString());
                toDate.setText(today.toString());
                searchBtn.fire();
                assertTrue(allLabelTexts(contentPane).contains("Start date must not be after end date."));

                // 3. Valid Seller + Score ("2 stars") + Date range finds our review
                fromDate.setText(today.toString());
                toDate.setText(today.toString());
                scoreFilter.getSelectionModel().select("2 stars");
                searchBtn.fire(); // programmatic select() does not fire ComboBox.onAction
                Parent ratingCard = findCardContainingText(contentPane, "Rude seller comment " + prefix);
                assertNotNull(ratingCard);

                // 4. Click Hide -> persists hidden = TRUE, reloads keeping filters, marks (hidden)
                Button hideBtn = findButtonByText(ratingCard, "Hide");
                assertFalse(hideBtn.isDisabled());
                hideBtn.fire();

                Parent reloadedCard = findCardContainingText(contentPane, "Rude seller comment " + prefix);
                assertNotNull(reloadedCard);
                assertTrue(allLabelTexts(reloadedCard).stream().anyMatch(t -> t.contains("(hidden)")));
                Button unhideBtn = findButtonByText(reloadedCard, "Unhide");
                assertFalse(unhideBtn.isDisabled(), "Commit d550370 enables Undo/Unhide for hidden reviews.");
                // Active filters survive the hide-triggered reload.
                assertEquals(testSeller.getUsername(), sellerFilter.getText());
                assertEquals("2 stars", scoreFilter.getValue());
                assertEquals(today.toString(), fromDate.getText());
                assertEquals(today.toString(), toDate.getText());

                // 5. Click Unhide -> hidden flips back to FALSE and active filters stay applied.
                unhideBtn.fire();
                Parent unhiddenCard = findCardContainingText(contentPane, "Rude seller comment " + prefix);
                assertNotNull(unhiddenCard);
                assertFalse(allLabelTexts(unhiddenCard).stream().anyMatch(t -> t.contains("(hidden)")));
                assertFalse(findButtonByText(unhiddenCard, "Hide").isDisabled());
                assertEquals(testSeller.getUsername(), sellerFilter.getText());
                assertEquals("2 stars", scoreFilter.getValue());
                assertEquals(today.toString(), fromDate.getText());
                assertEquals(today.toString(), toDate.getText());

                // 6. Reset clears all filters
                resetBtn.fire();
                assertEquals("", sellerFilter.getText());
                assertEquals("All scores", scoreFilter.getValue());
                assertEquals("", fromDate.getText());
                assertEquals("", toDate.getText());

                // 7. Missing review warning in setReviewVisibility()
                Review missing = new Review();
                missing.setId("missing-" + prefix);
                expectAlertAndClickOk("no longer exists");
                invokePrivate(view.controller(), "setReviewVisibility",
                        new Class<?>[]{Review.class, Runnable.class},
                        new Object[]{missing, (Runnable) () -> {}}, Void.class);
            });

            List<Review> dbReviews = reviewRepository.searchReviews(testSeller.getUsername(), null, null, null);
            assertTrue(dbReviews.stream().anyMatch(r -> r.getId().equals(reviewId) && !r.isHidden()),
                    "The Hide -> Unhide round-trip must leave hidden = FALSE in MySQL.");
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }
    }

    // =========================================================================
    // 7. NOTIFICATIONS & OWN PROFILE UI TESTS (AdminDashboardController)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — AdminDashboardController: Notifications & My Profile")
    class NotificationsAndProfileUITests {

        /**
         * Test Case: TC-UI-NTF-01
         * Content: Clicking Notifications mounts NotificationBoard, loads the admin's unread DB
         *          notification asynchronously, deactivates cleanly on tab switch, and redirects
         *          to Admin Login when SessionManager has no signed-in user.
         */
        @Test
        @DisplayName("TC-UI-NTF-01: Notifications mounts NotificationBoard, loads alerts, and guards null session")
        void showNotificationsAndSessionGuard() throws Exception {
            notificationRepository.create(new Notification(
                    testAdmin.getUsername(), "UI Audit Alert", "Notification body " + prefix, "System"));

            AtomicReference<AdminDashboardController> ctrlRef = new AtomicReference<>();
            AtomicReference<StackPane> paneRef = new AtomicReference<>();
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                ctrlRef.set(view.controller());
                paneRef.set(getField(view.controller(), "contentPane", StackPane.class));
                findButtonByText(view.root(), "  Notifications").fire();
                assertTrue(paneRef.get().getChildren().get(0) instanceof NotificationBoard);
            });

            // Wait for NotificationBoard's async background load to finish rendering
            waitForConditionOnFx(() ->
                    findAll(paneRef.get(), ProgressIndicator.class).isEmpty()
                            && allLabelTexts(paneRef.get()).stream()
                            .anyMatch(t -> t.contains("Notification body " + prefix)), 5000);

            runOnFx(() -> {
                // Switching away deactivates notificationBoard via setActive()
                findButtonByText(primaryStage.getScene().getRoot(), "  All Deals").fire();
                NotificationBoard board = getField(ctrlRef.get(), "notificationBoard", NotificationBoard.class);
                assertFalse(getField(board, "active", Boolean.class),
                        "Switching tabs should deactivate the notification board.");

                // Null session redirects to Admin Login screen
                SessionManager.logout();
                invokePrivate(ctrlRef.get(), "showNotifications", new Class<?>[0], new Object[0], Void.class);
                assertTrue(findAll(primaryStage.getScene().getRoot(), Label.class).stream()
                        .anyMatch(l -> "Admin Login".equals(l.getText())));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        /**
         * Test Case: TC-UI-PRF-01
         * Content: My Profile validates blank Full Name, >100-char Full Name, and weak password,
         *          then updates Full Name + strong password, refreshes welcomeLabel, clears PasswordField,
         *          and redirects to Admin Login if session is null.
         */
        @Test
        @DisplayName("TC-UI-PRF-01: My Profile validates inputs, updates name & password, and updates top bar")
        void showProfileValidationAndSave() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                Label welcomeLabel = getField(view.controller(), "welcomeLabel", Label.class);

                findButtonByText(view.root(), "  My Profile").fire();

                TextField nameField = findFirstExact(contentPane, TextField.class);
                PasswordField passField = findFirstExact(contentPane, PasswordField.class);
                Button saveBtn = findButtonByText(contentPane, "Save");
                assertEquals(testAdmin.getFullName(), nameField.getText());

                // 1. Blank Full Name
                nameField.setText("   ");
                expectAlertAndClickOk("Full Name cannot be empty.");
                saveBtn.fire();

                // 2. Full Name > 100 chars
                nameField.setText("A".repeat(101));
                expectAlertAndClickOk("Full Name must be at most 100 characters.");
                saveBtn.fire();

                // 3. Weak password
                nameField.setText("Renamed UI Admin");
                passField.setText("weak");
                expectAlertAndClickOk("Password must be at least 8 characters.");
                saveBtn.fire();

                // 4. Valid Full Name + strong password
                nameField.setText("Renamed UI Admin");
                passField.setText("StrongAdmin2026");
                expectAlertAndClickOk("Profile updated.");
                saveBtn.fire();

                assertEquals("Admin: Renamed UI Admin", welcomeLabel.getText());
                assertEquals("", passField.getText());

                // 5. Null session redirects to Admin Login
                SessionManager.logout();
                invokePrivate(view.controller(), "showProfile", new Class<?>[0], new Object[0], Void.class);
                assertTrue(findAll(primaryStage.getScene().getRoot(), Label.class).stream()
                        .anyMatch(l -> "Admin Login".equals(l.getText())));
            });

            AuthService auth = new AuthService(userRepository);
            assertEquals(AuthService.LoginStatus.SUCCESS,
                    auth.login(testAdmin.getUsername(), "StrongAdmin2026", UserRole.ADMIN).status());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }
    }

    // =========================================================================
    // ADDITIONAL JAVAFX COVERAGE (TODO GAPS)
    // =========================================================================

    @Nested
    @DisplayName("Frontend — additional required and edge coverage")
    class AdditionalFrontendCoverageTests {

        @Test
        @DisplayName("TC-UI-REV-06: Reject -> Cancel keeps listing PENDING and sends no notification")
        void rejectCancelLeavesPending() throws Exception {
            String title = prefix + "RejectCancel";
            Listing listing = createSellerListing(testSeller, title, ListingType.SELL,
                    45.00, null, null, List.of());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                Parent card = findCardContainingText(contentPane, title);
                assertNotNull(card);
                expectDialogMatching(p -> p.getHeaderText() != null
                                && p.getHeaderText().contains("Please provide a reason for rejection"),
                        AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(card, "Reject").fire();
                assertNotNull(findCardContainingText(contentPane, title));
            });

            Listing after = listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername());
            assertEquals(ListingStatus.PENDING_REVIEW, after.getStatus());
            assertNull(after.getRejectionReason());
            assertTrue(notificationRepository.findByRecipient(testSeller.getUsername(), true).stream()
                    .noneMatch(n -> "Listing Rejected".equals(n.getTitle())));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-REV-07: stale Reject warns, reloads queue, and sends no rejection notification")
        void staleRejectConflictShowsWarning() throws Exception {
            String oldTitle = prefix + "StaleRejectUi";
            String newTitle = prefix + "StaleRejectEdited";
            Listing listing = createSellerListing(testSeller, oldTitle, ListingType.SELL,
                    45.00, null, null, List.of());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                Parent card = findCardContainingText(contentPane, oldTitle);
                assertNotNull(card);
                Listing edited = buildListingModel(newTitle, ListingType.SELL, 55.00, null, null, List.of());
                try {
                    listingRepository.updateOwned(listing.getId(), testSeller.getUsername(), edited);
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                expectTextInputDialog("Please provide a reason for rejection", "Too late reason");
                expectAlertAndClickOk("This listing changed while you were reviewing it.");
                findButtonByText(card, "Reject").fire();
                assertNotNull(findCardContainingText(contentPane, newTitle));
            });

            Listing after = listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername());
            assertEquals(ListingStatus.PENDING_REVIEW, after.getStatus());
            assertEquals(newTitle, after.getTitle());
            assertNull(after.getRejectionReason());
            assertTrue(notificationRepository.findByRecipient(testSeller.getUsername(), true).stream()
                    .noneMatch(n -> "Listing Rejected".equals(n.getTitle())));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-REV-08: LEND/GIVEAWAY cards show type and up to six thumbnails; Seller Profile opens")
        void lendGiveawayCardsAndSellerProfileButton() throws Exception {
            List<byte[]> photos = new ArrayList<>();
            for (int i = 0; i < 6; i++) photos.add(VALID_PNG_BYTES);
            String lendTitle = prefix + "LendSix";
            String giveawayTitle = prefix + "GiveawaySix";
            createSellerListing(testSeller, lendTitle, ListingType.LEND, null, 5, LendingUnit.DAYS, photos);
            createSellerListing(testSeller, giveawayTitle, ListingType.GIVEAWAY, null, null, null, photos);

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                Parent lendCard = findCardContainingText(contentPane, lendTitle);
                Parent giveawayCard = findCardContainingText(contentPane, giveawayTitle);
                assertNotNull(lendCard);
                assertNotNull(giveawayCard);
                assertTrue(allLabelTexts(lendCard).contains("For Lending"));
                assertTrue(allLabelTexts(giveawayCard).contains("Giveaway"));
                assertEquals(6, findAll(lendCard, ImageView.class).size());
                assertEquals(6, findAll(giveawayCard, ImageView.class).size());

                assertNotNull(findButtonByText(lendCard, "Seller Profile"));

                // Safe trigger: put the reusable profile button into the attached pane before firing.
                Button attachedProfile = SellerProfilePane.profileButton(testSeller.getUsername());
                contentPane.getChildren().add(attachedProfile);
                assertNotNull(attachedProfile.getScene());
                expectDialogMatching(p -> p.getButtonTypes().stream()
                                .anyMatch(bt -> bt.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE),
                        AdminFrontendControllerTest.this::clickCancel);
                attachedProfile.fire();

                // The button must also be rendered on All Listings and only Seller rows in Manage Users.
                findButtonByText(view.root(), "  All Listings").fire();
                Parent listingRow = findCardContainingText(contentPane, lendTitle);
                assertNotNull(listingRow);
                assertNotNull(findButtonByText(listingRow, "Seller Profile"));

                findButtonByText(view.root(), "  Manage Users").fire();
                Parent sellerRow = findCardContainingText(contentPane, "@" + testSeller.getUsername());
                Parent buyerRow = findCardContainingText(contentPane, "@" + testBuyer.getUsername());
                assertNotNull(findButtonByText(sellerRow, "Seller Profile"));
                assertTrue(findAll(buyerRow, Button.class).stream()
                        .noneMatch(b -> "Seller Profile".equals(b.getText())));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-USR-04: Delete/Edit cancel paths leave user, listing and deal untouched")
        void userDeleteAndEditCancelLeaveDataUntouched() throws Exception {
            Listing listing = createSellerListing(testSeller, prefix + "CancelUserListing",
                    ListingType.SELL, 70.00, null, null, List.of());
            approveListing(listing);
            createDeal(testBuyer, listing, 65.00, null, null);

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  Manage Users").fire();
                Parent sellerRow = findCardContainingText(contentPane, "@" + testSeller.getUsername());
                assertNotNull(sellerRow);

                expectDialogMatching(p -> containsText(p,
                                "Permanently delete user @" + testSeller.getUsername()),
                        AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(sellerRow, "Delete").fire();
                assertNotNull(findCardContainingText(contentPane, "@" + testSeller.getUsername()));

                String header = "Editing @" + testSeller.getUsername() + " (SELLER)";
                expectDialogMatching(p -> header.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("Cancelled Change");
                    findFirstExact(pane, TextArea.class).setText("Cancelled Bio");
                    clickCancel(pane);
                });
                findButtonByText(findCardContainingText(contentPane, "@" + testSeller.getUsername()), "Edit").fire();
            });

            assertNotNull(userRepository.findByUsername(testSeller.getUsername()));
            assertEquals("Frontend Test Seller",
                    userRepository.findByUsername(testSeller.getUsername()).getFullName());
            assertNotNull(listingRepository.findByIdAndSeller(listing.getId(), testSeller.getUsername()));
            assertTrue(dealRepository.findById(createdDealIds.get(createdDealIds.size() - 1)).isPresent());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-USR-05: Edit User length/bio validation and Buyer Residence Hall None -> NULL")
        void userValidationAndNullableResidence() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  Manage Users").fire();

                String buyerHeader = "Editing @" + testBuyer.getUsername() + " (BUYER)";
                expectDialogMatching(p -> buyerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("   ");
                    clickOk(pane);
                });
                expectAlertAndClickOk("Full Name cannot be empty.");
                expectDialogMatching(p -> buyerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("A".repeat(101));
                    clickOk(pane);
                });
                expectAlertAndClickOk("Full Name must be at most 100 characters.");
                expectDialogMatching(p -> buyerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("Buyer None Hall");
                    @SuppressWarnings("unchecked")
                    ComboBox<String> hall = findFirstExact(pane, ComboBox.class);
                    hall.setValue("None");
                    clickOk(pane);
                });
                expectAlertAndClickOk("Profile saved.");
                findButtonByText(findCardContainingText(contentPane, "@" + testBuyer.getUsername()), "Edit").fire();

                String sellerHeader = "Editing @" + testSeller.getUsername() + " (SELLER)";
                expectDialogMatching(p -> sellerHeader.equals(p.getHeaderText()), pane -> {
                    findFirstExact(pane, TextArea.class).setText("A".repeat(65536));
                    clickOk(pane);
                });
                expectAlertAndClickOk("Your bio is too long. Please shorten it.");
                expectDialogMatching(p -> sellerHeader.equals(p.getHeaderText()),
                        AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(findCardContainingText(contentPane, "@" + testSeller.getUsername()), "Edit").fire();
            });

            User buyer = userRepository.findByUsername(testBuyer.getUsername());
            assertEquals("Buyer None Hall", buyer.getFullName());
            assertNull(buyer.getResidenceHall());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-LST-04: admin creates SELL/GIVEAWAY/LEND(SECONDS), validates, and cancels")
        void adminCreatesSellGiveawayAndLendSeconds() throws Exception {
            String sellTitle = prefix + "UICreatedSell";
            String lendTitle = prefix + "UICreatedLendSeconds";
            String giveawayTitle = prefix + "UICreatedGiveaway";

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  All Listings").fire();
                findButtonByText(contentPane, "Create Listing").fire();
                findComboBoxByPrompt(contentPane, "Listing type *").setValue("Sell");
                fillListingForm(contentPane, sellTitle, "12.34", null);
                expectAlertAndClickOk("Listing created. Status: Pending Review.");
                findButtonByText(contentPane, "Create Listing").fire();
                Listing sell = listingByTitle(sellTitle);
                assertEquals(12.34, sell.getPrice(), 0.001);

                // Reopen for the validation and remaining creation cases.
                findButtonByText(contentPane, "Create Listing").fire();
                findComboBoxByPrompt(contentPane, "Listing type *").setValue("Sell");
                fillListingForm(contentPane, prefix + "ValidationSell", null, null);
                TextField priceField = findTextFieldByPrompt(contentPane, "Price (HK$)");
                priceField.setText("100000000");
                expectAlertAndClickOk("Price must not exceed HK$99,999,999.99.");
                findButtonByText(contentPane, "Create Listing").fire();
                priceField.setText("abc");
                expectAlertAndClickOk("Enter a valid number for price.");
                findButtonByText(contentPane, "Create Listing").fire();

                ComboBox<String> typeCombo = findComboBoxByPrompt(contentPane, "Listing type *");
                typeCombo.setValue("Lend");
                assertTrue(priceField.isDisabled());
                fillListingForm(contentPane, lendTitle, null, "abc");
                ComboBox<LendingUnit> unitCombo = findLendingUnitCombo(contentPane);
                unitCombo.setValue(LendingUnit.SECONDS);
                expectAlertAndClickOk("Enter a positive whole number for lending duration.");
                findButtonByText(contentPane, "Create Listing").fire();
                findTextFieldByPrompt(contentPane, "Duration").setText("5");
                expectAlertAndClickOk("Listing created. Status: Pending Review.");
                findButtonByText(contentPane, "Create Listing").fire();

                Listing lend = listingByTitle(lendTitle);
                assertEquals(LendingUnit.SECONDS, lend.getLendingUnit());
                assertEquals(5, lend.getLendingDuration());

                findButtonByText(contentPane, "Create Listing").fire();
                fillListingForm(contentPane, giveawayTitle, null, null);
                typeCombo = findComboBoxByPrompt(contentPane, "Listing type *");
                typeCombo.setValue("Give Away");
                assertTrue(priceField.isDisabled());
                assertTrue(findTextFieldByPrompt(contentPane, "Duration").isDisabled());
                expectAlertAndClickOk("Listing created. Status: Pending Review.");
                findButtonByText(contentPane, "Create Listing").fire();

                Listing giveaway = listingByTitle(giveawayTitle);
                assertNull(giveaway.getPrice());
                assertNull(giveaway.getLendingDuration());

                findButtonByText(contentPane, "Create Listing").fire();
                findButtonByText(contentPane, "Cancel").fire();
                assertTrue(findAll(contentPane, Button.class).stream()
                        .anyMatch(b -> "Create Listing".equals(b.getText())));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-LST-05: edit cancel, accepted-deal block, and Remove cancel keep DB unchanged")
        void listingEditAndRemoveCancelPaths() throws Exception {
            String cancelTitle = prefix + "EditCancelItem";
            String acceptedTitle = prefix + "AcceptedLockedItem";
            String removeTitle = prefix + "RemoveCancelItem";
            Listing cancelListing = createSellerListing(testSeller, cancelTitle, ListingType.SELL,
                    100.00, null, null, List.of());
            approveListing(cancelListing);
            Listing acceptedListing = createSellerListing(testSeller, acceptedTitle, ListingType.SELL,
                    100.00, null, null, List.of());
            approveListing(acceptedListing);
            Deal accepted = createDeal(testBuyer, acceptedListing, 95.00, null, null);
            transitionDeal(accepted.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    accepted.getCreatedAt().plusSeconds(1));
            Listing removeListing = createSellerListing(testSeller, removeTitle, ListingType.SELL,
                    100.00, null, null, List.of());
            approveListing(removeListing);

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  All Listings").fire();

                findButtonByText(findCardContainingText(contentPane, cancelTitle), "Edit").fire();
                findTextFieldByPrompt(contentPane, "Item name *").setText(prefix + "ShouldNotSave");
                expectDialogMatching(p -> containsText(p, "The listing will return to Pending Review."),
                        AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(contentPane, "Save Changes").fire();
                assertEquals(cancelTitle, listingById(cancelListing.getId()).getTitle());
                findButtonByText(contentPane, "Cancel").fire();

                findButtonByText(findCardContainingText(contentPane, acceptedTitle), "Edit").fire();
                findTextFieldByPrompt(contentPane, "Item name *").setText(prefix + "ShouldNotSaveAccepted");
                expectDialogMatching(p -> containsText(p, "The listing will return to Pending Review."),
                        AdminFrontendControllerTest.this::clickOk);
                expectAlertAndClickOk("accepted deal");
                findButtonByText(contentPane, "Save Changes").fire();
                assertEquals(acceptedTitle, listingById(acceptedListing.getId()).getTitle());
                findButtonByText(contentPane, "Cancel").fire();

                Parent removeRow = findCardContainingText(contentPane, removeTitle);
                expectDialogMatching(p -> containsText(p, "marked as REMOVED"),
                        AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(removeRow, "Remove").fire();
                assertNotNull(listingById(removeListing.getId()));

                removeRow = findCardContainingText(contentPane, removeTitle);
                expectDialogMatching(p -> containsText(p, "marked as REMOVED"),
                        AdminFrontendControllerTest.this::clickOk);
                expectDialogMatching(p -> p.getHeaderText() != null
                                && p.getHeaderText().contains("Please provide a reason for removal"),
                        AdminFrontendControllerTest.this::clickCancel);
                findButtonByText(removeRow, "Remove").fire();
                assertNotNull(listingById(removeListing.getId()));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-USR-06: Deactivate/Activate/Edit/Delete persist Account audit notifications")
        void userActionsPersistAuditNotifications() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  Manage Users").fire();

                findButtonByText(findCardContainingText(contentPane, "@" + testBuyer.getUsername()),
                        "Deactivate").fire();
                Parent buyerRow = findCardContainingText(contentPane, "@" + testBuyer.getUsername());
                findButtonByText(buyerRow, "Activate").fire();

                String buyerHeader = "Editing @" + testBuyer.getUsername() + " (BUYER)";
                expectDialogMatching(p -> buyerHeader.equals(p.getHeaderText()), pane -> {
                    findTextFieldByPrompt(pane, "Full Name *").setText("Audited Buyer");
                    clickOk(pane);
                });
                expectAlertAndClickOk("Profile saved.");
                findButtonByText(findCardContainingText(contentPane, "@" + testBuyer.getUsername()), "Edit").fire();

                expectDialogMatching(p -> containsText(p,
                                "Permanently delete user @" + testSeller.getUsername()),
                        AdminFrontendControllerTest.this::clickOk);
                expectAlertAndClickOk("deleted permanently. Their listings were marked as REMOVED.");
                findButtonByText(findCardContainingText(contentPane, "@" + testSeller.getUsername()), "Delete").fire();
            });

            List<Notification> audit = notificationRepository.findByRecipient(testAdmin.getUsername(), true);
            for (String title : List.of("Account Deactivated", "Account Activated",
                    "User Account Updated", "User Account Deleted")) {
                assertTrue(audit.stream().anyMatch(n -> title.equals(n.getTitle())),
                        "Missing audit notification: " + title);
            }
            assertNull(userRepository.findByUsername(testSeller.getUsername()));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-DLS-02: GIVEAWAY filter and completed/returned date formatting")
        void dealsGiveawayCompletedAndReturnedFormatting() throws Exception {
            Listing giveaway = createSellerListing(testSeller, prefix + "DealGiveaway",
                    ListingType.GIVEAWAY, null, null, null, List.of());
            approveListing(giveaway);
            createDeal(testBuyer, giveaway, null, null, null);

            Listing completedListing = createSellerListing(testSeller, prefix + "DealCompleted",
                    ListingType.SELL, 50.00, null, null, List.of());
            approveListing(completedListing);
            Deal completed = createDeal(testBuyer, completedListing, 45.00, null, null);
            transitionDeal(completed.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    completed.getCreatedAt().plusSeconds(1));
            transitionDeal(completed.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED,
                    completed.getCreatedAt().plusSeconds(2));

            Listing returnedListing = createSellerListing(testSeller, prefix + "DealReturned",
                    ListingType.LEND, null, 5, LendingUnit.DAYS, List.of());
            approveListing(returnedListing);
            Deal returned = createDeal(testBuyer, returnedListing, null,
                    LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusMinutes(2));
            transitionDeal(returned.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    returned.getCreatedAt().plusSeconds(1));
            transitionDeal(returned.getId(), DealStatus.ACCEPTED, 2L, DealStatus.RETURNED,
                    LocalDateTime.now().plusMinutes(3));

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  All Deals").fire();
                TextField search = findTextFieldByPrompt(contentPane,
                        "Search by item title, buyer or seller username...");
                ComboBox<String> typeFilter = findFirstExact(contentPane, ComboBox.class);
                Button searchBtn = findButtonByText(contentPane, "Search");
                TableView<Deal> table = findFirstExact(contentPane, TableView.class);
                String datePattern = "\\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}";

                search.setText(prefix);
                searchBtn.fire();
                Deal completedItem = table.getItems().stream()
                        .filter(d -> completedListing.getTitle().equals(d.getListingTitle()))
                        .findFirst().orElseThrow();
                Deal returnedItem = table.getItems().stream()
                        .filter(d -> returnedListing.getTitle().equals(d.getListingTitle()))
                        .findFirst().orElseThrow();
                assertTrue(((String) table.getColumns().get(5).getCellData(completedItem)).matches(datePattern));
                assertTrue(((String) table.getColumns().get(6).getCellData(completedItem)).matches(datePattern));
                assertTrue(((String) table.getColumns().get(6).getCellData(returnedItem)).matches(datePattern));

                typeFilter.getSelectionModel().select("Received");
                searchBtn.fire();
                assertEquals(1, table.getItems().size());
                assertEquals("Received", table.getColumns().get(3).getCellData(0));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-DLS-03: deleted seller, both-deleted exclusion, and empty search state")
        void dealsDeletedParticipantsAndEmptyState() throws Exception {
            User seller2 = createTestUser("seller2x", "Deleted Seller Two", "SellerPass1", UserRole.SELLER, true);
            User buyer2 = createTestUser("buyer2x", "Remaining Buyer Two", "BuyerPass1", UserRole.BUYER, true);
            Listing seller2Listing = createSellerListing(seller2, prefix + "DeletedSellerItem",
                    ListingType.SELL, 50.00, null, null, List.of());
            approveListing(seller2Listing);
            createDeal(buyer2, seller2Listing, 45.00, null, null);
            new AuthService(userRepository).deleteUserAsAdmin(testAdmin, seller2.getUsername());

            Listing bothDealsListing = createSellerListing(testSeller, prefix + "BothDeletedItem",
                    ListingType.SELL, 50.00, null, null, List.of());
            approveListing(bothDealsListing);
            createDeal(testBuyer, bothDealsListing, 45.00, null, null);
            new AuthService(userRepository).deleteUserAsAdmin(testAdmin, testBuyer.getUsername());
            new AuthService(userRepository).deleteUserAsAdmin(testAdmin, testSeller.getUsername());

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  All Deals").fire();
                TextField search = findTextFieldByPrompt(contentPane,
                        "Search by item title, buyer or seller username...");
                Button searchBtn = findButtonByText(contentPane, "Search");
                TableView<Deal> table = findFirstExact(contentPane, TableView.class);

                search.setText(prefix);
                searchBtn.fire();
                assertEquals(1, table.getItems().size());
                assertEquals(buyer2.getUsername(), table.getColumns().get(1).getCellData(0));
                assertEquals("(deleted user)", table.getColumns().get(2).getCellData(0));

                search.setText("missing-" + prefix);
                searchBtn.fire();
                assertTrue(table.getItems().isEmpty());
                assertEquals("No deals found.", ((Label) table.getPlaceholder()).getText());
                assertTrue(allLabelTexts(contentPane).contains("0 deals"));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-NTF-02: mark-as-read, show-read toggle, and per-admin isolation")
        void notificationMarkReadAndSecondAdminIsolation() throws Exception {
            User admin2 = createTestUser("admin2x", "Second Test Admin", "AdminPass2", UserRole.ADMIN, true);
            Notification first = new Notification(testAdmin.getUsername(), "UI Read Test",
                    "Read body " + prefix, "System");
            notificationRepository.create(first);
            Notification second = new Notification(admin2.getUsername(), "Second Admin Notice",
                    "Second body " + prefix, "System");
            notificationRepository.create(second);

            AtomicReference<StackPane> pane = new AtomicReference<>();
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                pane.set(getField(view.controller(), "contentPane", StackPane.class));
                findButtonByText(view.root(), "  Notifications").fire();
            });
            waitForConditionOnFx(() -> allLabelTexts(pane.get()).stream()
                    .anyMatch(t -> t.contains("Read body " + prefix)), 5000);

            runOnFx(() -> findButtonByText(pane.get(), "Mark as read").fire());
            waitForConditionOnFx(() -> {
                try {
                    return notificationRepository.findByRecipient(testAdmin.getUsername(), true).stream()
                            .anyMatch(n -> n.getId().equals(first.getId()) && n.isRead());
                } catch (SQLException ex) {
                    return false;
                }
            }, 5000);
            runOnFx(() -> findFirstExact(pane.get(), CheckBox.class).fire());
            waitForConditionOnFx(() -> allLabelTexts(pane.get()).stream()
                    .anyMatch(t -> t.contains("Read body " + prefix)), 5000);

            runOnFx(() -> {
                SessionManager.logout();
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(admin2);
                pane.set(getField(view.controller(), "contentPane", StackPane.class));
                findButtonByText(view.root(), "  Notifications").fire();
            });
            waitForConditionOnFx(() -> allLabelTexts(pane.get()).stream()
                    .anyMatch(t -> t.contains("Second body " + prefix)), 5000);
            assertTrue(allLabelTexts(pane.get()).stream()
                    .noneMatch(t -> t.contains("Read body " + prefix)));
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-PRF-02: weak password partial-failure, name-only audit, deleted session redirect")
        void profileNoPartialSaveAuditAndDeletedSession() throws Exception {
            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                Label welcome = getField(view.controller(), "welcomeLabel", Label.class);
                findButtonByText(view.root(), "  My Profile").fire();
                TextField name = findFirstExact(contentPane, TextField.class);
                PasswordField pass = findFirstExact(contentPane, PasswordField.class);

                name.setText("Partial Profile Name");
                pass.setText("weak");
                expectAlertAndClickOk("Password must be at least 8 characters.");
                findButtonByText(contentPane, "Save").fire();
                assertEquals("Admin: " + testAdmin.getFullName(), welcome.getText());

                name.setText("Renamed Admin Edge");
                pass.clear();
                expectAlertAndClickOk("Profile updated.");
                findButtonByText(contentPane, "Save").fire();
                assertEquals("Admin: Renamed Admin Edge", welcome.getText());
            });

            User after = userRepository.findByUsername(testAdmin.getUsername());
            assertEquals("Renamed Admin Edge", after.getFullName());
            assertTrue(PasswordHasher.verify("AdminPass1", after.getPasswordHash()));
            assertTrue(notificationRepository.findByRecipient(testAdmin.getUsername(), true).stream()
                    .anyMatch(n -> "Admin Profile Updated".equals(n.getTitle())));

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE username = ?")) {
                ps.setString(1, testAdmin.getUsername());
                ps.executeUpdate();
            }
            runOnFx(() -> {
                LoadedView<AdminDashboardController> deletedView = loadAdminDashboardView(testAdmin);
                invokePrivate(deletedView.controller(), "showProfile",
                        new Class<?>[0], new Object[0], Void.class);
                assertTrue(findAll(primaryStage.getScene().getRoot(), Label.class).stream()
                        .anyMatch(l -> "Admin Login".equals(l.getText())));
            });
            assertNull(SessionManager.getCurrentUser());
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        @Test
        @DisplayName("TC-UI-RAT-03: flagged badge, invalid To date, one-sided filters, empty result")
        void ratingsFlaggedDatesAndEmptyState() throws Exception {
            User buyer2 = createTestUser("buyer2r", "Ratings Buyer Two", "BuyerPass1", UserRole.BUYER, true);
            LocalDate today = LocalDate.now();
            String good = createCompletedDealWithReview(testBuyer, testSeller,
                    prefix + "RatingGood", 5, "Good rating " + prefix, today.minusDays(10).atTime(12, 0));
            String bad = createCompletedDealWithReview(buyer2, testSeller,
                    prefix + "RatingBad", 1, "Bad rating " + prefix, today.minusDays(3).atTime(12, 0));
            try (Connection conn = DatabaseManager.getConnection()) {
                reviewRepository.flag(conn, good);
            }

            runOnFx(() -> {
                LoadedView<AdminDashboardController> view = loadAdminDashboardView(testAdmin);
                StackPane contentPane = getField(view.controller(), "contentPane", StackPane.class);
                findButtonByText(view.root(), "  Seller Ratings").fire();
                TextField seller = findTextFieldByPrompt(contentPane, "Search seller");
                TextField from = findTextFieldByPrompt(contentPane, "From (yyyy-MM-dd)");
                TextField to = findTextFieldByPrompt(contentPane, "To (yyyy-MM-dd)");
                Button search = findButtonByText(contentPane, "Search");
                Button reset = findButtonByText(contentPane, "Reset");

                seller.setText(testSeller.getUsername());
                search.fire();
                Parent flaggedCard = findCardContainingText(contentPane, "Good rating " + prefix);
                assertNotNull(flaggedCard);
                assertTrue(allLabelTexts(flaggedCard).contains("FLAGGED"));

                to.setText("2026/11/10");
                search.fire();
                assertTrue(allLabelTexts(contentPane).contains("To date must use yyyy-MM-dd."));
                reset.fire();

                from.setText(today.minusDays(7).toString());
                seller.setText(testSeller.getUsername());
                search.fire();
                assertNotNull(findCardContainingText(contentPane, "Bad rating " + prefix));
                assertNull(findCardContainingText(contentPane, "Good rating " + prefix));

                from.clear();
                to.setText(today.minusDays(7).toString());
                search.fire();
                assertNotNull(findCardContainingText(contentPane, "Good rating " + prefix));
                assertNull(findCardContainingText(contentPane, "Bad rating " + prefix));

                reset.fire();
                seller.setText("missing-" + prefix);
                search.fire();
                assertTrue(allLabelTexts(contentPane).contains("No reviews match these filters."));
            });
            assertTrue(unexpectedAlerts.isEmpty(), "Unexpected alerts: " + unexpectedAlerts);
        }

        // --- helpers local to the additional UI coverage ---

        private Listing listingById(String id) {
            try {
                return listingRepository.findByIdAndSeller(id, testSeller.getUsername());
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }

        private Listing listingByTitle(String title) {
            try {
                return listingRepository.findBySeller(testSeller.getUsername()).stream()
                        .filter(l -> title.equals(l.getTitle())).findFirst().orElseThrow();
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }

        @SuppressWarnings("unchecked")
        private ComboBox<LendingUnit> findLendingUnitCombo(StackPane pane) {
            return (ComboBox<LendingUnit>) findAll(pane, ComboBox.class).stream()
                    .filter(cb -> cb.getItems().contains(LendingUnit.SECONDS))
                    .findFirst().orElseThrow();
        }

        @SuppressWarnings("unchecked")
        private void fillListingForm(StackPane pane, String title, String price, String duration) {
            ComboBox<User> sellerCombo = findComboBoxByPrompt(pane, "Select Seller");
            User chosen = sellerCombo.getItems().stream()
                    .filter(u -> u.getUsername().equals(testSeller.getUsername()))
                    .findFirst().orElseThrow();
            sellerCombo.setValue(chosen);
            findTextFieldByPrompt(pane, "Item name *").setText(title);
            findComboBoxByPrompt(pane, "Category *").setValue("Electronics");
            findComboBoxByPrompt(pane, "Condition *").setValue("Like New");
            findFirstExact(pane, TextArea.class).setText("UI coverage description " + title);
            findComboBoxByPrompt(pane, "Meet-up location *").setValue("Library");
            if (price != null) findTextFieldByPrompt(pane, "Price (HK$)").setText(price);
            if (duration != null) findTextFieldByPrompt(pane, "Duration").setText(duration);
        }
    }

    // =========================================================================
    // JAVAFX LOADING, DIALOG WATCHER & SCENE-GRAPH HELPERS
    // =========================================================================

    private record LoadedView<T>(Parent root, T controller) {}

    private LoadedView<AdminLoginController> loadAdminLoginView() {
        return loadView("/fxml/admin/AdminLoginView.fxml");
    }

    private LoadedView<AdminDashboardController> loadAdminDashboardView(User admin) {
        SessionManager.login(admin);
        return loadView("/fxml/admin/AdminDashboardView.fxml");
    }

    private <T> LoadedView<T> loadView(String fxmlPath) {
        try {
            URL resource = Objects.requireNonNull(getClass().getResource(fxmlPath),
                    "FXML not found on classpath: " + fxmlPath);
            FXMLLoader loader = new FXMLLoader(resource);
            Parent root = loader.load();
            if (primaryStage.getScene() == null) {
                primaryStage.setScene(new Scene(root, 1200, 780));
            } else {
                primaryStage.getScene().setRoot(root);
            }
            return new LoadedView<>(root, loader.getController());
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load FXML " + fxmlPath, ex);
        }
    }

    private void startDialogWatcher() {
        stopDialogWatcher();
        dialogWatcher = new Timeline(new KeyFrame(Duration.millis(25), event -> processTopModalDialog()));
        dialogWatcher.setCycleCount(Animation.INDEFINITE);
        dialogWatcher.play();
    }

    private void stopDialogWatcher() {
        if (dialogWatcher != null) {
            dialogWatcher.stop();
            dialogWatcher = null;
        }
    }

    private void processTopModalDialog() {
        // Dialog handlers click buttons, which may open a nested showAndWait() Alert from the
        // controller. Never do that inside a JavaFX animation pulse (Timeline callback) or
        // JavaFX throws "showAndWait is not allowed during animation or layout processing".
        if (dialogActionPending) return;
        Stage topDialogStage = findTopShowingDialogStage();
        if (topDialogStage == null) {
            strayDialogWindow = null;
            strayDialogFirstSeenMs = 0L;
            return;
        }
        DialogPane pane = (DialogPane) topDialogStage.getScene().getRoot();
        DialogRule next = dialogRules.peekFirst();
        if (next != null && next.matcher().test(pane)) {
            dialogRules.pollFirst();
            strayDialogWindow = null;
            strayDialogFirstSeenMs = 0L;
            runDialogAction(() -> next.handler().accept(pane));
            return;
        }

        // Fallback safety: if an unexpected dialog stays open > 600ms, dismiss it so the test never hangs
        long now = System.currentTimeMillis();
        if (topDialogStage != strayDialogWindow) {
            strayDialogWindow = topDialogStage;
            strayDialogFirstSeenMs = now;
        } else if (now - strayDialogFirstSeenMs > 600) {
            unexpectedAlerts.add(describeDialog(pane));
            Stage dialogToDismiss = topDialogStage;
            runDialogAction(() -> dismissDialog(pane, dialogToDismiss));
            strayDialogWindow = null;
            strayDialogFirstSeenMs = 0L;
        }
    }

    /**
     * Runs a dialog action after the current animation/layout pulse, while still allowing the
     * Timeline watcher to handle any nested dialog the controller opens from that action.
     */
    private void runDialogAction(Runnable action) {
        dialogActionPending = true;
        Platform.runLater(() -> {
            // Clear before the action so a nested Alert can be processed by the watcher.
            dialogActionPending = false;
            action.run();
        });
    }

    private Stage findTopShowingDialogStage() {
        List<Window> windows = new ArrayList<>(Window.getWindows());
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window w = windows.get(i);
            if (w instanceof Stage stage && stage != primaryStage && stage.isShowing()
                    && stage.getScene() != null && stage.getScene().getRoot() instanceof DialogPane) {
                return stage;
            }
        }
        return null;
    }

    private void closeAnyStrayDialogs() {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w instanceof Stage stage && stage != primaryStage && stage.isShowing()) {
                stage.close();
            }
        }
    }

    private void expectDialogMatching(Predicate<DialogPane> matcher, Consumer<DialogPane> handler) {
        dialogRules.addLast(new DialogRule(matcher, handler));
    }

    private void expectAlertAndClickOk(String expectedSubstring) {
        expectDialogMatching(
                pane -> containsText(pane, expectedSubstring),
                pane -> {
                    capturedAlerts.add(pane.getContentText());
                    clickOk(pane);
                });
    }

    private void expectTextInputDialog(String expectedHeaderSubstring, String inputText) {
        expectDialogMatching(
                pane -> pane.getHeaderText() != null && pane.getHeaderText().contains(expectedHeaderSubstring),
                pane -> {
                    TextField input = findFirstExact(pane, TextField.class);
                    input.setText(inputText);
                    clickOk(pane);
                });
    }

    private boolean containsText(DialogPane pane, String substring) {
        if (pane.getContentText() != null && pane.getContentText().contains(substring)) return true;
        if (pane.getHeaderText() != null && pane.getHeaderText().contains(substring)) return true;
        return allLabelTexts(pane).stream().anyMatch(t -> t.contains(substring));
    }

    private void clickOk(DialogPane pane) {
        for (ButtonType bt : pane.getButtonTypes()) {
            if (bt == ButtonType.OK || bt.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                Button btn = (Button) pane.lookupButton(bt);
                if (btn != null) {
                    btn.fire();
                    return;
                }
            }
        }
        throw new AssertionError("No OK button found on dialog: " + describeDialog(pane));
    }

    private void clickCancel(DialogPane pane) {
        for (ButtonType bt : pane.getButtonTypes()) {
            if (bt == ButtonType.CANCEL || bt.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) {
                Button btn = (Button) pane.lookupButton(bt);
                if (btn != null) {
                    btn.fire();
                    return;
                }
            }
        }
        throw new AssertionError("No Cancel button found on dialog: " + describeDialog(pane));
    }

    private void dismissDialog(DialogPane pane, Stage stage) {
        for (ButtonType bt : pane.getButtonTypes()) {
            Node n = pane.lookupButton(bt);
            if (n instanceof Button btn) {
                btn.fire();
                return;
            }
        }
        stage.close();
    }

    private String describeDialog(DialogPane pane) {
        return "[header=" + pane.getHeaderText() + ", content=" + pane.getContentText() + "]";
    }

    private static void runOnFx(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(20, TimeUnit.SECONDS), "Timed out waiting for JavaFX action to finish.");
        if (error.get() != null) {
            if (error.get() instanceof Exception ex) throw ex;
            if (error.get() instanceof Error err) throw err;
            throw new RuntimeException(error.get());
        }
    }

    private static void waitForConditionOnFx(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Boolean> met = new AtomicReference<>(false);
            runOnFx(() -> met.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(met.get())) return;
            Thread.sleep(40);
        }
        throw new AssertionError("Timed out waiting for JavaFX condition after " + timeoutMs + " ms.");
    }

    // --- Scene graph traversal helpers ---

    private static <T extends Node> List<T> findAll(Node root, Class<T> type) {
        List<T> results = new ArrayList<>();
        collectNodes(root, type::isInstance, results, Collections.newSetFromMap(new IdentityHashMap<>()));
        return results;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T findFirstExact(Node root, Class<T> exactType) {
        List<Node> matches = new ArrayList<>();
        collectNodes(root, n -> n.getClass() == exactType, matches,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        if (matches.isEmpty()) {
            throw new AssertionError("No node of exact type " + exactType.getSimpleName() + " found.");
        }
        return (T) matches.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> void collectNodes(Node node, Predicate<Node> filter,
                                                      List<T> out, Set<Node> visited) {
        if (node == null || !visited.add(node)) return;
        if (filter.test(node)) out.add((T) node);
        if (node instanceof ScrollPane sp && sp.getContent() != null) {
            collectNodes(sp.getContent(), filter, out, visited);
        }
        if (node instanceof DialogPane dp && dp.getContent() != null) {
            collectNodes(dp.getContent(), filter, out, visited);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectNodes(child, filter, out, visited);
            }
        }
    }

    private static Button findButtonByText(Node root, String text) {
        return findAll(root, Button.class).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Button not found with text: '" + text + "'"));
    }

    private static TextField findTextFieldByPrompt(Node root, String prompt) {
        List<Node> matches = new ArrayList<>();
        collectNodes(root, n -> n.getClass() == TextField.class
                && prompt.equals(((TextField) n).getPromptText()), matches,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        if (matches.isEmpty()) {
            throw new AssertionError("TextField not found with prompt: '" + prompt + "'");
        }
        return (TextField) matches.get(0);
    }

    @SuppressWarnings("rawtypes")
    private static ComboBox findComboBoxByPrompt(Node root, String prompt) {
        return findAll(root, ComboBox.class).stream()
                .filter(cb -> prompt.equals(cb.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ComboBox not found with prompt: '" + prompt + "'"));
    }

    private static Parent findCardContainingText(Node root, String uniqueSubstring) {
        List<Parent> cards = new ArrayList<>();
        collectNodes(root, n -> n instanceof Parent p && p.getStyleClass().contains("card"), cards,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        for (Parent card : cards) {
            if (allLabelTexts(card).stream().anyMatch(t -> t.contains(uniqueSubstring))) {
                return card;
            }
        }
        return null;
    }

    private static List<String> allLabelTexts(Node root) {
        return findAll(root, Label.class).stream()
                .map(Label::getText)
                .filter(Objects::nonNull)
                .toList();
    }

    // --- Reflection helpers (same package as controllers) ---

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name, Class<T> type) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Failed to read field " + name, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivate(Object target, String name, Class<?>[] paramTypes,
                                       Object[] args, Class<T> returnType) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return (T) m.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Failed to invoke " + name, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeStaticPrivate(Class<?> clazz, String name, Class<?>[] paramTypes,
                                             Object[] args, Class<T> returnType) {
        try {
            Method m = clazz.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return (T) m.invoke(null, args);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Failed to invoke static " + name, ex);
        }
    }

    // =========================================================================
    // DATABASE TEST DATA HELPERS
    // =========================================================================

    private User createTestUser(String suffix, String fullName, String rawPassword,
                                UserRole role, boolean active) throws SQLException {
        User user = new User();
        user.setUsername(prefix + suffix);
        user.setFullName(fullName);
        user.setPasswordHash(PasswordHasher.hash(rawPassword));
        user.setRole(role);
        user.setActive(active);
        if (role == UserRole.BUYER) {
            user.setResidenceHall("Hall 3");
        } else if (role == UserRole.SELLER) {
            user.setBio("Initial test bio");
            user.setPreferredMeetup("Library");
        }
        userRepository.register(user);
        createdUsernames.add(user.getUsername());
        return user;
    }

    private Listing buildListingModel(String title, ListingType type, Double price,
                                      Integer duration, LendingUnit unit, List<byte[]> photos) {
        Listing listing = new Listing();
        listing.setTitle(title);
        listing.setDescription("Frontend UI test description for " + title);
        listing.setCategory("Electronics");
        listing.setCondition("Like New");
        listing.setMeetupLocation("Library");
        listing.setType(type);
        listing.setPrice(price);
        listing.setLendingDuration(duration);
        listing.setLendingUnit(unit);
        listing.setPhotos(new ArrayList<>(photos));
        return listing;
    }

    private Listing createSellerListing(User seller, String title, ListingType type, Double price,
                                        Integer duration, LendingUnit unit, List<byte[]> photos) throws SQLException {
        Listing listing = buildListingModel(title, type, price, duration, unit, photos);
        listingService.createListing(seller, listing);
        createdListingIds.add(listing.getId());
        return listing;
    }

    private void approveListing(Listing listing) throws SQLException {
        Listing loaded = listingRepository.findByIdAndSeller(listing.getId(), listing.getSellerUsername());
        assertTrue(listingRepository.updateReview(loaded.getId(), ListingStatus.LIVE, null, loaded.getRowVersion()));
    }

    private Deal createDeal(User buyer, Listing listing, Double offerPrice,
                            LocalDateTime borrowStart, LocalDateTime borrowEnd) throws SQLException {
        Deal deal = new Deal();
        deal.setListingId(listing.getId());
        deal.setListingTitle(listing.getTitle());
        deal.setBuyerUsername(buyer.getUsername());
        deal.setSellerUsername(listing.getSellerUsername());
        deal.setDealType(listing.getType());
        deal.setOfferPrice(offerPrice);
        deal.setBorrowStart(borrowStart);
        deal.setBorrowEnd(borrowEnd);
        String id = dealRepository.create(deal);
        createdDealIds.add(id);
        return deal;
    }

    private void transitionDeal(String dealId, DealStatus expected, long expectedVersion,
                                DealStatus next, LocalDateTime at) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertTrue(dealRepository.updateStatus(conn, dealId, expected, expectedVersion, next, at));
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private String createCompletedDealWithReview(User buyer, User seller, String itemTitle,
                                                 int rating, String comment, LocalDateTime reviewCreatedAt)
            throws SQLException {
        Listing listing = createSellerListing(seller, itemTitle, ListingType.SELL, 100.00, null, null, List.of());
        approveListing(listing);
        Deal deal = createDeal(buyer, listing, 95.00, null, null);
        LocalDateTime t1 = deal.getCreatedAt().plusSeconds(1);
        transitionDeal(deal.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED, t1);
        transitionDeal(deal.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED, t1.plusSeconds(1));

        String reviewId = UUID.randomUUID().toString();
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                reviewRepository.create(conn, reviewId, deal.getId(), rating, comment);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE reviews SET created_at = ? WHERE id = ?")) {
                    ps.setTimestamp(1, Timestamp.valueOf(reviewCreatedAt));
                    ps.setString(2, reviewId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        }
        createdReviewIds.add(reviewId);
        return reviewId;
    }

    private void executeDelete(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
    }

    private String queryStringColumn(String sql, String param) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }
}
