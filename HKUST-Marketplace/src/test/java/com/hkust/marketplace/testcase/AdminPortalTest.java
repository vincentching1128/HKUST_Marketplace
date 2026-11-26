package com.hkust.marketplace.testcase;

import com.hkust.marketplace.models.ChatThread;
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
import com.hkust.marketplace.repositories.ChatRepository;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.ListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.ReviewRepository;
import com.hkust.marketplace.repositories.SellerStatsRepository;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.BuyerListingService;
import com.hkust.marketplace.services.ListingService;
import com.hkust.marketplace.services.NotificationService;
import com.hkust.marketplace.services.PasswordHasher;
import com.hkust.marketplace.utils.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated JUnit 5 Integration and Functional Test Suite for Task 3: Admin Portal.
 *
 * <p>File Placement:
 * {@code HKUST-Marketplace/src/test/java/com/hkust/marketplace/testcase/AdminPortalTest.java}
 *
 * <p>How to Execute:
 * <ol>
 *   <li>Ensure MySQL 8 is running and {@code hkust_marketplace} is initialized using
 *       {@code src/main/resources/db/schema.sql}.</li>
 *   <li>Configure DB credentials via environment variables
 *       {@code MARKETPLACE_DB_USER} and {@code MARKETPLACE_DB_PASSWORD}, or
 *       {@code ~/.hkust-marketplace/database.properties}.</li>
 *   <li>Ensure JDK 21 is active (matches {@code pom.xml} {@code maven.compiler.release=21}).</li>
 *   <li>Run:
 *       <ul>
 *         <li>Windows PowerShell: {@code .\mvnw.cmd test -Dtest=com.hkust.marketplace.testcase.AdminPortalTest}</li>
 *         <li>macOS / Linux: {@code ./mvnw test -Dtest=com.hkust.marketplace.testcase.AdminPortalTest}</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Database Isolation and Cleanup:
 * Every test generates a unique random prefix (e.g. {@code t9f3a2c1b_}) for all users,
 * listings, deals, reviews, chat threads, and notifications it creates, and removes them
 * in {@link #tearDown()} in foreign-key-safe order. Seeded demo accounts
 * ({@code admin}, {@code alice}, {@code bob}, {@code charlie}, {@code diana}) are never modified.
 *
 * <p>Task 3 Coverage Map:
 * <ul>
 *   <li>Task 3.1 Admin Login -> {@link Task31_AdminLoginTests}</li>
 *   <li>Task 3.2 Listing Review and Approval -> {@link Task32_ListingReviewTests}</li>
 *   <li>Task 3.3 Manage All Users -> {@link Task33_ManageUsersTests}</li>
 *   <li>Task 3.4 Manage Own Profile -> {@link Task34_ManageOwnProfileTests}</li>
 *   <li>Task 3.5 All Deals Record -> {@link Task35_AllDealsRecordTests}</li>
 *   <li>Task 3.6 Notification Board -> {@link Task36_NotificationBoardTests}</li>
 *   <li>Task 3.7 Manage All Listings -> {@link Task37_ManageAllListingsTests}</li>
 *   <li>Task 3.8 Seller Ratings Overview -> {@link Task38_SellerRatingsOverviewTests}</li>
 *   <li>Additional TODO coverage -> {@link Task3CoverageGapTests}</li>
 * </ul>
 *
 * <p>Manual JavaFX UI Checklist (visual/dialog behavior not executed by headless JUnit):
 * <ul>
 *   <li>AdminLoginView.fxml: verify there is no registration button or link.</li>
 *   <li>Listing Review: verify 140x105 photo thumbnails, Approve confirmation, and Rejection Reason dialog.</li>
 *   <li>Manage Users: verify Delete confirmation dialog; check whether Deactivate needs confirmation.</li>
 *   <li>Manage All Listings: verify type-first form gating, photo chooser (max 6 / 16 MiB), and Edit/Remove dialogs.</li>
 *   <li>Seller Ratings: verify the two-row filter bar and the enabled Hide/Unhide round-trip on hidden reviews.</li>
 * </ul>
 */
@DisplayName("Task 3: Admin Portal Functionality Test Suite")
public class AdminPortalTest {

    // Repositories & services under test
    private final UserRepository userRepository = new UserRepository();
    private final ListingRepository listingRepository = new ListingRepository();
    private final DealRepository dealRepository = new DealRepository();
    private final NotificationRepository notificationRepository = new NotificationRepository();
    private final ReviewRepository reviewRepository = new ReviewRepository();
    private final ChatRepository chatRepository = new ChatRepository();
    private final SellerStatsRepository sellerStatsRepository = new SellerStatsRepository();

    private final AuthService authService = new AuthService(userRepository);
    private final ListingService listingService = new ListingService(
            userRepository, listingRepository, notificationRepository);
    private final NotificationService adminNotificationService = new NotificationService(
            userRepository, notificationRepository, UserRole.ADMIN);
    private final BuyerListingService buyerListingService = new BuyerListingService();

    // Per-test isolation prefix and cleanup trackers
    private String prefix;
    private final List<String> createdUsernames = new ArrayList<>();
    private final List<String> createdListingIds = new ArrayList<>();
    private final List<String> createdDealIds = new ArrayList<>();
    private final List<String> createdReviewIds = new ArrayList<>();
    private final List<String> createdThreadIds = new ArrayList<>();

    // Isolated users created for each test case
    private User testAdmin;
    private User testAdmin2;
    private User testBuyer1;
    private User testBuyer2;
    private User testSeller1;
    private User testSeller2;

    @BeforeAll
    static void verifyDatabaseConnection() {
        try (Connection conn = DatabaseManager.getConnection()) {
            assertNotNull(conn, "MySQL connection must be available before running AdminPortalTest.");
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Cannot connect to MySQL hkust_marketplace database. "
                            + "Set MARKETPLACE_DB_USER and MARKETPLACE_DB_PASSWORD (or database.properties) "
                            + "and run schema.sql before executing tests.", ex);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        prefix = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_";
        createdUsernames.clear();
        createdListingIds.clear();
        createdDealIds.clear();
        createdReviewIds.clear();
        createdThreadIds.clear();

        testAdmin = createTestUser("admin1", "Test Admin One", "AdminPass1", UserRole.ADMIN, true);
        testAdmin2 = createTestUser("admin2", "Test Admin Two", "AdminPass2", UserRole.ADMIN, true);
        testBuyer1 = createTestUser("buyer1", "Test Buyer One", "BuyerPass1", UserRole.BUYER, true);
        testBuyer2 = createTestUser("buyer2", "Test Buyer Two", "BuyerPass2", UserRole.BUYER, true);
        testSeller1 = createTestUser("seller1", "Test Seller One", "SellerPass1", UserRole.SELLER, true);
        testSeller2 = createTestUser("seller2", "Test Seller Two", "SellerPass2", UserRole.SELLER, true);
    }

    @AfterEach
    void tearDown() throws SQLException {
        // Clean up child tables before parent tables to satisfy all FK constraints
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Chat messages (FK RESTRICT on thread_id) then chat threads
                for (String threadId : createdThreadIds) {
                    executeDelete(conn, "DELETE FROM chat_messages WHERE thread_id = ?", threadId);
                    executeDelete(conn, "DELETE FROM chat_threads WHERE id = ?", threadId);
                }

                // 2. Reviews
                for (String reviewId : createdReviewIds) {
                    executeDelete(conn, "DELETE FROM reviews WHERE id = ?", reviewId);
                }

                // 3. Listing reports referencing test listings
                for (String listingId : createdListingIds) {
                    executeDelete(conn, "DELETE FROM listing_reports WHERE listing_id = ?", listingId);
                }

                // 4. Deals
                for (String dealId : createdDealIds) {
                    executeDelete(conn, "DELETE FROM deals WHERE id = ?", dealId);
                }

                // 5. Listings (cascades to listing_photos)
                for (String listingId : createdListingIds) {
                    executeDelete(conn, "DELETE FROM listings WHERE id = ?", listingId);
                }

                // 6. Notifications addressed to test users or mentioning this test prefix (e.g. broadcast to seeded admin)
                for (String username : createdUsernames) {
                    executeDelete(conn, "DELETE FROM notifications WHERE username = ?", username);
                }
                executeDelete(conn, "DELETE FROM notifications WHERE message LIKE ?", "%" + prefix + "%");

                // 7. Test user accounts
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
    // TASK 3.1: ADMIN LOGIN
    // =========================================================================

    @Nested
    @DisplayName("Task 3.1 — Admin Login")
    class Task31_AdminLoginTests {

        /**
         * Test Case: TC-ADM-01
         * Content: Verify seeded admin credential ('admin' / 'Admin123') authenticates cleanly.
         * Steps:
         *   1. Check that the seeded 'admin' account exists and still uses default password 'Admin123'.
         *   2. Call authService.login("admin", "Admin123", UserRole.ADMIN).
         * Expected Result:
         *   - LoginStatus.SUCCESS with non-null User whose role is ADMIN.
         *   (Uses JUnit Assumptions so the test skips gracefully if you changed the seeded password.)
         */
        @Test
        @DisplayName("TC-ADM-01: Seeded admin credential logs in successfully")
        void seededAdminCanLogin() throws SQLException {
            User seeded = userRepository.findByUsername("admin");
            Assumptions.assumeTrue(seeded != null && seeded.isActive(),
                    "Seeded 'admin' user is absent or inactive; skipping seeded login test.");
            Assumptions.assumeTrue(PasswordHasher.verify("Admin123", seeded.getPasswordHash()),
                    "Seeded 'admin' password differs from default 'Admin123'; skipping.");

            AuthService.LoginResult result = authService.login("admin", "Admin123", UserRole.ADMIN);

            assertEquals(AuthService.LoginStatus.SUCCESS, result.status());
            assertNotNull(result.user());
            assertEquals("admin", result.user().getUsername());
            assertEquals(UserRole.ADMIN, result.user().getRole());
        }

        /**
         * Test Case: TC-ADM-02
         * Content: Admin login trims leading/trailing whitespace and normalizes username casing.
         * Steps:
         *   1. Pass uppercase testAdmin username wrapped in spaces with valid password.
         * Expected Result:
         *   - LoginStatus.SUCCESS returning testAdmin.
         */
        @Test
        @DisplayName("TC-ADM-02: Admin login trims whitespace and ignores username case")
        void adminLoginTrimsAndLowercasesUsername() throws SQLException {
            String messyUsername = "  " + testAdmin.getUsername().toUpperCase() + "  ";

            AuthService.LoginResult result = authService.login(messyUsername, "AdminPass1", UserRole.ADMIN);

            assertEquals(AuthService.LoginStatus.SUCCESS, result.status());
            assertNotNull(result.user());
            assertEquals(testAdmin.getUsername(), result.user().getUsername());
        }

        /**
         * Test Case: TC-ADM-03
         * Content: Blank username, empty password, or null role are rejected as INVALID_INPUT.
         * Steps:
         *   1. Invoke login with blank username, empty password, and null role.
         * Expected Result:
         *   - Each returns LoginStatus.INVALID_INPUT with null user.
         */
        @Test
        @DisplayName("TC-ADM-03: Blank or missing credentials return INVALID_INPUT")
        void blankCredentialsFailValidation() throws SQLException {
            assertEquals(AuthService.LoginStatus.INVALID_INPUT,
                    authService.login("   ", "AdminPass1", UserRole.ADMIN).status());
            assertEquals(AuthService.LoginStatus.INVALID_INPUT,
                    authService.login(testAdmin.getUsername(), "", UserRole.ADMIN).status());
            assertEquals(AuthService.LoginStatus.INVALID_INPUT,
                    authService.login(testAdmin.getUsername(), "AdminPass1", null).status());
        }

        /**
         * Test Case: TC-ADM-04
         * Content: Unknown username and incorrect password return distinct failure statuses.
         * Steps:
         *   1. Attempt login with a non-existent username -> USER_NOT_FOUND.
         *   2. Attempt login with valid admin username and wrong password -> INCORRECT_PASSWORD.
         * Expected Result:
         *   - Exact failure statuses and null User returned.
         */
        @Test
        @DisplayName("TC-ADM-04: Unknown username and wrong password return exact failure statuses")
        void unknownUserAndWrongPasswordRejected() throws SQLException {
            AuthService.LoginResult missing = authService.login(prefix + "ghost", "AdminPass1", UserRole.ADMIN);
            assertEquals(AuthService.LoginStatus.USER_NOT_FOUND, missing.status());
            assertNull(missing.user());

            AuthService.LoginResult badPass = authService.login(testAdmin.getUsername(), "WrongPass99", UserRole.ADMIN);
            assertEquals(AuthService.LoginStatus.INCORRECT_PASSWORD, badPass.status());
            assertNull(badPass.user());
        }

        /**
         * Test Case: TC-ADM-05
         * Content: Buyer and Seller accounts cannot authenticate through the Admin portal.
         * Steps:
         *   1. Attempt UserRole.ADMIN login using testBuyer1 and testSeller1 credentials.
         * Expected Result:
         *   - Both return LoginStatus.WRONG_ROLE.
         */
        @Test
        @DisplayName("TC-ADM-05: Buyer and Seller accounts are rejected with WRONG_ROLE")
        void nonAdminRolesRejectedOnAdminPortal() throws SQLException {
            assertEquals(AuthService.LoginStatus.WRONG_ROLE,
                    authService.login(testBuyer1.getUsername(), "BuyerPass1", UserRole.ADMIN).status());
            assertEquals(AuthService.LoginStatus.WRONG_ROLE,
                    authService.login(testSeller1.getUsername(), "SellerPass1", UserRole.ADMIN).status());
        }

        /**
         * Test Case: TC-ADM-06
         * Content: Deactivated admin account is blocked from signing in.
         * Steps:
         *   1. Deactivate testAdmin2 via userRepository.setActive(..., false).
         *   2. Attempt login with testAdmin2's valid password.
         * Expected Result:
         *   - Returns LoginStatus.ACCOUNT_DEACTIVATED.
         */
        @Test
        @DisplayName("TC-ADM-06: Deactivated admin account is rejected with ACCOUNT_DEACTIVATED")
        void deactivatedAdminCannotLogin() throws SQLException {
            userRepository.setActive(testAdmin2.getUsername(), false);

            AuthService.LoginResult result = authService.login(
                    testAdmin2.getUsername(), "AdminPass2", UserRole.ADMIN);

            assertEquals(AuthService.LoginStatus.ACCOUNT_DEACTIVATED, result.status());
            assertNull(result.user());
        }
    }

    // =========================================================================
    // TASK 3.2: LISTING REVIEW & APPROVAL SCREEN
    // =========================================================================

    @Nested
    @DisplayName("Task 3.2 — Listing Review & Approval Screen")
    class Task32_ListingReviewTests {

        /**
         * Test Case: TC-REV-01
         * Content: Pending Review queue loads all required submission metadata and photos in FIFO order.
         * Steps:
         *   1. Seller submits two listings sequentially (first with 1 photo, second with 0 photos).
         *   2. Query listingRepository.findByStatus(ListingStatus.PENDING_REVIEW).
         * Expected Result:
         *   - Older listing appears before newer listing (FIFO: posted_at ASC, id ASC).
         *   - Title, sellerUsername, sellerFullName, category, listingType, meetupLocation,
         *     postedAt, status (PENDING_REVIEW), and photo bytes are all populated.
         */
        @Test
        @DisplayName("TC-REV-01: Pending queue returns complete submission details in FIFO order")
        void pendingQueueReturnsCompleteFieldsFifo() throws SQLException, InterruptedException {
            // Arrange
            byte[] samplePhoto = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            Listing first = createSellerListing(testSeller1, prefix + "First Item", ListingType.SELL,
                    120.00, null, null, List.of(samplePhoto));
            Thread.sleep(20); // ensure distinct DATETIME(6) timestamps
            Listing second = createSellerListing(testSeller1, prefix + "Second Item", ListingType.GIVEAWAY,
                    null, null, null, List.of());

            // Act
            List<Listing> pending = listingRepository.findByStatus(ListingStatus.PENDING_REVIEW);
            List<Listing> ours = pending.stream()
                    .filter(l -> l.getId().equals(first.getId()) || l.getId().equals(second.getId()))
                    .toList();

            // Assert
            assertEquals(2, ours.size());
            assertEquals(first.getId(), ours.get(0).getId(), "Review queue must be FIFO (oldest first).");
            assertEquals(second.getId(), ours.get(1).getId());

            Listing loadedFirst = ours.get(0);
            assertEquals(prefix + "First Item", loadedFirst.getTitle());
            assertEquals(testSeller1.getUsername(), loadedFirst.getSellerUsername());
            assertEquals(testSeller1.getFullName(), loadedFirst.getSellerFullName());
            assertEquals("Electronics", loadedFirst.getCategory());
            assertEquals(ListingType.SELL, loadedFirst.getType());
            assertEquals("Library", loadedFirst.getMeetupLocation());
            assertNotNull(loadedFirst.getPostedAt());
            assertEquals(ListingStatus.PENDING_REVIEW, loadedFirst.getStatus());
            assertEquals(1, loadedFirst.getPhotos().size());
            assertEquals(0, ours.get(1).getPhotos().size());
        }

        /**
         * Test Case: TC-REV-02
         * Content: Approving a pending listing transitions it to LIVE, sets reviewed_at, clears
         *          rejection_reason, increments row_version, and removes it from the review queue.
         * Steps:
         *   1. Submit a pending listing (row_version = 1).
         *   2. Call listingRepository.updateReview(id, LIVE, null, rowVersion).
         * Expected Result:
         *   - Returns true; DB row has status=LIVE, reviewedAt!=null, rejectionReason==null, rowVersion=2.
         */
        @Test
        @DisplayName("TC-REV-02: Approving a pending listing moves it to LIVE and increments row_version")
        void approvePendingListingUpdatesStatusAndVersion() throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + "Desk Lamp", ListingType.SELL,
                    80.00, null, null, List.of());
            Listing loaded = listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername());
            assertEquals(1L, loaded.getRowVersion());

            boolean approved = listingRepository.updateReview(
                    loaded.getId(), ListingStatus.LIVE, null, loaded.getRowVersion());

            assertTrue(approved);
            Listing after = listingRepository.findByIdAndSeller(loaded.getId(), testSeller1.getUsername());
            assertEquals(ListingStatus.LIVE, after.getStatus());
            assertNotNull(after.getReviewedAt());
            assertNull(after.getRejectionReason());
            assertEquals(2L, after.getRowVersion());
            assertTrue(listingRepository.findByStatus(ListingStatus.PENDING_REVIEW).stream()
                    .noneMatch(l -> l.getId().equals(loaded.getId())));
        }

        /**
         * Test Case: TC-REV-03
         * Content: Rejecting a listing requires a non-blank written reason and stores it in MySQL.
         * Steps:
         *   1. Attempt rejection with blank and null reason -> expect IllegalArgumentException.
         *   2. Reject with valid trimmed reason -> expect true.
         * Expected Result:
         *   - Status becomes REJECTED, trimmed reason is persisted, reviewedAt set, rowVersion=2.
         */
        @Test
        @DisplayName("TC-REV-03: Rejecting requires a non-blank reason and stores it in MySQL")
        void rejectPendingListingRequiresAndStoresReason() throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + "Blurry Item", ListingType.SELL,
                    50.00, null, null, List.of());
            Listing loaded = listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername());

            assertThrows(IllegalArgumentException.class, () ->
                    listingRepository.updateReview(loaded.getId(), ListingStatus.REJECTED, "   ", loaded.getRowVersion()));
            assertThrows(IllegalArgumentException.class, () ->
                    listingRepository.updateReview(loaded.getId(), ListingStatus.REJECTED, null, loaded.getRowVersion()));

            boolean rejected = listingRepository.updateReview(
                    loaded.getId(), ListingStatus.REJECTED, "  Photos are unclear  ", loaded.getRowVersion());

            assertTrue(rejected);
            Listing after = listingRepository.findByIdAndSeller(loaded.getId(), testSeller1.getUsername());
            assertEquals(ListingStatus.REJECTED, after.getStatus());
            assertEquals("Photos are unclear", after.getRejectionReason());
            assertNotNull(after.getReviewedAt());
            assertEquals(2L, after.getRowVersion());
        }

        /**
         * Test Case: TC-REV-04
         * Content: Optimistic locking prevents stale approval when the seller edited the listing mid-review.
         * Steps:
         *   1. Admin loads pending listing at row_version = 1.
         *   2. Seller edits listing (row_version bumps to 2).
         *   3. Admin attempts updateReview using stale expectedRowVersion = 1.
         * Expected Result:
         *   - updateReview returns false; listing remains PENDING_REVIEW with the seller's new content.
         */
        @Test
        @DisplayName("TC-REV-04: Stale review decision is rejected by row_version optimistic lock")
        void staleApprovalRejectedByRowVersion() throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + "Original Textbook", ListingType.SELL,
                    100.00, null, null, List.of());
            Listing snapshotSeenByAdmin = listingRepository.findByIdAndSeller(
                    listing.getId(), testSeller1.getUsername());
            long staleVersion = snapshotSeenByAdmin.getRowVersion();

            // Seller modifies content after admin loaded the review queue
            Listing edited = buildListingModel(prefix + "Altered Content", ListingType.SELL, 999.00, null, null, List.of());
            listingRepository.updateOwned(listing.getId(), testSeller1.getUsername(), edited);

            boolean approved = listingRepository.updateReview(
                    listing.getId(), ListingStatus.LIVE, null, staleVersion);

            assertFalse(approved, "Stale approval must fail when row_version has changed.");
            Listing current = listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername());
            assertEquals(ListingStatus.PENDING_REVIEW, current.getStatus());
            assertEquals(staleVersion + 1, current.getRowVersion());
            assertEquals(prefix + "Altered Content", current.getTitle());
        }
    }

    // =========================================================================
    // TASK 3.3: MANAGE ALL USERS SCREEN
    // =========================================================================

    @Nested
    @DisplayName("Task 3.3 — Manage All Users Screen")
    class Task33_ManageUsersTests {

        /**
         * Test Case: TC-USR-01
         * Content: User list loads from MySQL sorted alphabetically by username.
         * Steps:
         *   1. Call userRepository.findAll().
         * Expected Result:
         *   - Contains test buyers/sellers in ascending username order.
         */
        @Test
        @DisplayName("TC-USR-01: findAll returns persisted users ordered by username")
        void findAllReturnsPersistedUsers() throws SQLException {
            List<User> all = userRepository.findAll();
            List<String> usernames = all.stream().map(User::getUsername).toList();

            assertTrue(usernames.contains(testBuyer1.getUsername()));
            assertTrue(usernames.contains(testSeller1.getUsername()));
            List<String> ours = usernames.stream().filter(u -> u.startsWith(prefix)).toList();
            assertEquals(ours.stream().sorted().toList(), ours);
        }

        /**
         * Test Case: TC-USR-02
         * Content: Admin edits Buyer (Full Name, Residence Hall) and Seller (Full Name, Bio,
         *          nullable Preferred Meet-up) profiles in MySQL.
         * Steps:
         *   1. Update testBuyer1 full name and residence hall.
         *   2. Update testSeller1 full name, bio, and set preferredMeetup = null ("None").
         * Expected Result:
         *   - Updated fields persist; role and passwordHash are preserved.
         */
        @Test
        @DisplayName("TC-USR-02: Admin edits Buyer and Seller profile fields in MySQL")
        void adminCanEditBuyerAndSellerProfiles() throws SQLException {
            User buyerUpdate = new User();
            buyerUpdate.setUsername(testBuyer1.getUsername());
            buyerUpdate.setFullName("Updated Buyer Name");
            buyerUpdate.setResidenceHall("Hall 7");
            assertTrue(userRepository.updateProfile(buyerUpdate));

            User reloadedBuyer = userRepository.findByUsername(testBuyer1.getUsername());
            assertEquals("Updated Buyer Name", reloadedBuyer.getFullName());
            assertEquals("Hall 7", reloadedBuyer.getResidenceHall());
            assertEquals(UserRole.BUYER, reloadedBuyer.getRole());

            User sellerUpdate = new User();
            sellerUpdate.setUsername(testSeller1.getUsername());
            sellerUpdate.setFullName("Updated Seller Name");
            sellerUpdate.setBio("Updated campus seller bio");
            sellerUpdate.setPreferredMeetup(null);
            assertTrue(userRepository.updateProfile(sellerUpdate));

            User reloadedSeller = userRepository.findByUsername(testSeller1.getUsername());
            assertEquals("Updated Seller Name", reloadedSeller.getFullName());
            assertEquals("Updated campus seller bio", reloadedSeller.getBio());
            assertNull(reloadedSeller.getPreferredMeetup());
        }

        /**
         * Test Case: TC-USR-03
         * Content: Activate/Deactivate persists exact boolean state and blocks/restores login.
         * Steps:
         *   1. Deactivate testBuyer1 -> verify DB active=false and login returns ACCOUNT_DEACTIVATED.
         *   2. Reactivate testBuyer1 -> verify DB active=true and login returns SUCCESS.
         *   3. Calling setActive on non-existent username throws IllegalArgumentException.
         */
        @Test
        @DisplayName("TC-USR-03: Deactivate and reactivate user persists to MySQL and enforces login block")
        void deactivateAndReactivateUser() throws SQLException {
            userRepository.setActive(testBuyer1.getUsername(), false);
            assertFalse(userRepository.findByUsername(testBuyer1.getUsername()).isActive());
            assertEquals(AuthService.LoginStatus.ACCOUNT_DEACTIVATED,
                    authService.login(testBuyer1.getUsername(), "BuyerPass1", UserRole.BUYER).status());

            userRepository.setActive(testBuyer1.getUsername(), true);
            assertTrue(userRepository.findByUsername(testBuyer1.getUsername()).isActive());
            assertEquals(AuthService.LoginStatus.SUCCESS,
                    authService.login(testBuyer1.getUsername(), "BuyerPass1", UserRole.BUYER).status());

            assertThrows(IllegalArgumentException.class,
                    () -> userRepository.setActive(prefix + "missing", false));
        }

        /**
         * Test Case: TC-USR-04
         * Content: Full permanent user deletion policy for listings, notifications, deals, and chats.
         * Steps:
         *   1. testSeller1 owns two LIVE listings (one with a photo). testBuyer1 has:
         *        - an open PENDING deal + chat thread on listing 1;
         *        - a finalized COMPLETED deal on listing 2;
         *        - notifications in the notifications table.
         *   2. Admin permanently deletes testBuyer1:
         *        - testBuyer1 row and notifications are deleted.
         *        - Open PENDING deal becomes CANCELLED with buyer_username=NULL, deleted=1 (visible).
         *        - Finalized COMPLETED deal stays COMPLETED with buyer_username=NULL, deleted=1 (visible).
         *        - Chat thread has deleted=1, remains visible to testSeller1.
         *   3. Admin permanently deletes testSeller1:
         *        - testSeller1 row and notifications are deleted.
         *        - Seller's listings remain in DB as REMOVED with seller_username=NULL,
         *          rejection_reason='User account deleted', and photos preserved.
         *        - Both deals and chat thread advance to deleted=2 and are hidden from default queries.
         *   4. Retrying deleteUserAsAdmin on deleted user throws IllegalArgumentException (idempotent).
         */
        @Test
        @DisplayName("TC-USR-04: Permanent deletion handles listings, notifications, open vs completed deals, and chats")
        void permanentDeletionCascadesAndPreservesHistoryCorrectly() throws SQLException {
            // Arrange
            byte[] photo = new byte[]{1, 2, 3, 4};
            Listing listing1 = createSellerListing(testSeller1, prefix + "Laptop Stand",
                    ListingType.SELL, 150.00, null, null, List.of(photo));
            Listing listing2 = createSellerListing(testSeller1, prefix + "USB Hub",
                    ListingType.SELL, 60.00, null, null, List.of());
            approveListing(listing1);
            approveListing(listing2);

            Deal openDeal = createDeal(testBuyer1, listing1, 140.00, null, null);
            ChatThread thread = createChatThread(openDeal);

            Deal completedDeal = createDeal(testBuyer1, listing2, 60.00, null, null);
            LocalDateTime t1 = completedDeal.getCreatedAt().plusSeconds(1);
            transitionDeal(completedDeal.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED, t1);
            transitionDeal(completedDeal.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED, t1.plusSeconds(1));

            notificationRepository.create(new Notification(
                    testBuyer1.getUsername(), "Buyer Note", "Message for " + prefix, "System"));
            notificationRepository.create(new Notification(
                    testSeller1.getUsername(), "Seller Note", "Message for " + prefix, "System"));

            // Act 1: Delete first participant (buyer)
            authService.deleteUserAsAdmin(testAdmin, testBuyer1.getUsername());

            // Assert 1: Buyer and buyer's notifications are gone
            assertNull(userRepository.findByUsername(testBuyer1.getUsername()));
            assertTrue(notificationRepository.findByRecipient(testBuyer1.getUsername(), true).isEmpty());

            // Assert 1: Open deal -> CANCELLED, deleted=1; Completed deal -> stays COMPLETED, deleted=1
            Deal openAfterBuyer = dealRepository.findById(openDeal.getId()).orElseThrow();
            assertEquals(DealStatus.CANCELLED, openAfterBuyer.getStatus());
            assertNull(openAfterBuyer.getBuyerUsername());
            assertEquals(testSeller1.getUsername(), openAfterBuyer.getSellerUsername());
            assertEquals(1, queryIntColumn("SELECT deleted FROM deals WHERE id = ?", openDeal.getId()));

            Deal completedAfterBuyer = dealRepository.findById(completedDeal.getId()).orElseThrow();
            assertEquals(DealStatus.COMPLETED, completedAfterBuyer.getStatus());
            assertNull(completedAfterBuyer.getBuyerUsername());
            assertEquals(1, queryIntColumn("SELECT deleted FROM deals WHERE id = ?", completedDeal.getId()));

            // Assert 1: Chat thread stays visible to seller with deleted=1
            assertEquals(1, queryIntColumn("SELECT deleted FROM chat_threads WHERE id = ?", thread.id()));
            try (Connection conn = DatabaseManager.getConnection()) {
                List<ChatThread> sellerThreads = chatRepository.findThreads(conn, testSeller1.getUsername());
                assertTrue(sellerThreads.stream().anyMatch(t -> t.id().equals(thread.id())));
            }

            // Act 2: Delete second participant (seller)
            authService.deleteUserAsAdmin(testAdmin, testSeller1.getUsername());

            // Assert 2: Seller & notifications removed
            assertNull(userRepository.findByUsername(testSeller1.getUsername()));
            assertTrue(notificationRepository.findByRecipient(testSeller1.getUsername(), true).isEmpty());

            // Assert 2: Seller's listings preserved as REMOVED with seller_username=NULL and photo intact
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT listingStatus, seller_username, rejection_reason FROM listings WHERE id = ?")) {
                ps.setString(1, listing1.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Listing row must be soft-deleted, not hard-deleted.");
                    assertEquals("REMOVED", rs.getString("listingStatus"));
                    assertNull(rs.getString("seller_username"));
                    assertEquals("User account deleted", rs.getString("rejection_reason"));
                }
            }
            assertEquals(1, queryIntColumn(
                    "SELECT COUNT(*) FROM listing_photos WHERE listing_id = ?", listing1.getId()));

            // Assert 2: Both deals & chat thread reach deleted=2 and drop out of default queries
            assertEquals(2, queryIntColumn("SELECT deleted FROM deals WHERE id = ?", openDeal.getId()));
            assertEquals(2, queryIntColumn("SELECT deleted FROM deals WHERE id = ?", completedDeal.getId()));
            assertEquals(2, queryIntColumn("SELECT deleted FROM chat_threads WHERE id = ?", thread.id()));
            assertTrue(dealRepository.findById(openDeal.getId()).isEmpty());
            assertTrue(dealRepository.findById(completedDeal.getId()).isEmpty());

            // Assert 3: Retry is idempotent and fails cleanly
            assertThrows(IllegalArgumentException.class,
                    () -> authService.deleteUserAsAdmin(testAdmin, testBuyer1.getUsername()));
        }

        /**
         * Test Case: TC-USR-05
         * Content: Admin accounts cannot be deleted, and non-admin callers cannot invoke deletion.
         * Steps:
         *   1. testAdmin tries to delete testAdmin2 -> IllegalArgumentException.
         *   2. testBuyer1 tries to delete testBuyer2 -> IllegalArgumentException.
         * Expected Result:
         *   - Both target accounts remain intact in MySQL.
         */
        @Test
        @DisplayName("TC-USR-05: Admin accounts are protected and non-admin callers are blocked")
        void adminAccountsProtectedFromDeletion() throws SQLException {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.deleteUserAsAdmin(testAdmin, testAdmin2.getUsername()));
            assertNotNull(userRepository.findByUsername(testAdmin2.getUsername()));

            assertThrows(IllegalArgumentException.class,
                    () -> authService.deleteUserAsAdmin(testBuyer1, testBuyer2.getUsername()));
            assertNotNull(userRepository.findByUsername(testBuyer2.getUsername()));
        }
    }

    // =========================================================================
    // TASK 3.4: MANAGE OWN PROFILE SCREEN
    // =========================================================================

    @Nested
    @DisplayName("Task 3.4 — Manage Own Profile Screen")
    class Task34_ManageOwnProfileTests {

        /**
         * Test Case: TC-PRF-01
         * Content: Admin updates Full Name while leaving new password blank.
         * Steps:
         *   1. Call authService.updateProfile(update, "").
         * Expected Result:
         *   - Full Name updates in MySQL; original password hash and login remain unchanged.
         */
        @Test
        @DisplayName("TC-PRF-01: Admin updates Full Name without altering password")
        void adminUpdatesFullNameOnly() throws SQLException {
            String originalHash = userRepository.findByUsername(testAdmin.getUsername()).getPasswordHash();

            User update = new User();
            update.setUsername(testAdmin.getUsername());
            update.setFullName("Chief Admin Renamed");
            assertTrue(authService.updateProfile(update, ""));

            User reloaded = userRepository.findByUsername(testAdmin.getUsername());
            assertEquals("Chief Admin Renamed", reloaded.getFullName());
            assertEquals(originalHash, reloaded.getPasswordHash());
            assertEquals(AuthService.LoginStatus.SUCCESS,
                    authService.login(testAdmin.getUsername(), "AdminPass1", UserRole.ADMIN).status());
        }

        /**
         * Test Case: TC-PRF-02
         * Content: Admin updates Full Name and Password with a strong password.
         * Steps:
         *   1. Call authService.updateProfile(update, "NewStrong99").
         *   2. Verify old password fails and new password succeeds.
         * Expected Result:
         *   - Stored hash changes to a valid Argon2id hash; old password rejected; new password accepted.
         */
        @Test
        @DisplayName("TC-PRF-02: Admin updates password with Argon2id hash and invalidates old password")
        void adminUpdatesPasswordSuccessfully() throws SQLException {
            String oldHash = userRepository.findByUsername(testAdmin.getUsername()).getPasswordHash();

            User update = new User();
            update.setUsername(testAdmin.getUsername());
            update.setFullName("Admin With New Password");
            assertTrue(authService.updateProfile(update, "NewStrong99"));

            User reloaded = userRepository.findByUsername(testAdmin.getUsername());
            assertNotEquals(oldHash, reloaded.getPasswordHash());
            assertTrue(PasswordHasher.isHash(reloaded.getPasswordHash()));
            assertEquals(AuthService.LoginStatus.INCORRECT_PASSWORD,
                    authService.login(testAdmin.getUsername(), "AdminPass1", UserRole.ADMIN).status());
            assertEquals(AuthService.LoginStatus.SUCCESS,
                    authService.login(testAdmin.getUsername(), "NewStrong99", UserRole.ADMIN).status());
        }

        /**
         * Test Case: TC-PRF-03
         * Content: Weak passwords (<8 chars, missing uppercase/lowercase/digit) are rejected.
         * Steps:
         *   1. Try four invalid passwords with authService.updateProfile.
         * Expected Result:
         *   - Each throws IllegalArgumentException and original password still authenticates.
         */
        @Test
        @DisplayName("TC-PRF-03: Weak passwords are rejected during admin profile update")
        void weakPasswordsRejected() throws SQLException {
            User update = new User();
            update.setUsername(testAdmin.getUsername());
            update.setFullName("Admin Name");

            assertThrows(IllegalArgumentException.class, () -> authService.updateProfile(update, "Ab12345"));
            assertThrows(IllegalArgumentException.class, () -> authService.updateProfile(update, "alllowercase1"));
            assertThrows(IllegalArgumentException.class, () -> authService.updateProfile(update, "ALLUPPERCASE1"));
            assertThrows(IllegalArgumentException.class, () -> authService.updateProfile(update, "NoDigitsHere"));

            assertEquals(AuthService.LoginStatus.SUCCESS,
                    authService.login(testAdmin.getUsername(), "AdminPass1", UserRole.ADMIN).status());
        }
    }

    // =========================================================================
    // TASK 3.5: ALL DEALS RECORD SCREEN
    // =========================================================================

    @Nested
    @DisplayName("Task 3.5 — All Deals Record Screen")
    class Task35_AllDealsRecordTests {

        /**
         * Test Case: TC-DLS-01
         * Content: All Deals Record displays SELL/LEND/GIVEAWAY deals with timestamps and supports
         *          filtering by Deal Type and keyword (title, buyer username, seller username).
         * Steps:
         *   1. Create 3 LIVE listings (SELL, LEND, GIVEAWAY) and 1 deal on each.
         *   2. Transition SELL deal -> COMPLETED and LEND deal -> RETURNED.
         *   3. Query dealRepository.searchDeals(...) with keyword and type filters.
         * Expected Result:
         *   - All 3 returned by prefix; each ListingType filter isolates the exact deal;
         *     COMPLETED has completedAt, RETURNED has borrowStart and returnedAt;
         *     buyer and seller keyword searches return exact matches.
         */
        @Test
        @DisplayName("TC-DLS-01: All Deals search filters by Deal Type and keyword (title, buyer, seller)")
        void searchDealsFiltersByTypeAndKeyword() throws SQLException {
            Listing sellItem = createSellerListing(testSeller1, prefix + "Monitor", ListingType.SELL,
                    600.00, null, null, List.of());
            Listing lendItem = createSellerListing(testSeller1, prefix + "Calculator", ListingType.LEND,
                    null, 7, LendingUnit.DAYS, List.of());
            Listing freeItem = createSellerListing(testSeller2, prefix + "Notebook", ListingType.GIVEAWAY,
                    null, null, null, List.of());
            approveListing(sellItem);
            approveListing(lendItem);
            approveListing(freeItem);

            Deal sellDeal = createDeal(testBuyer1, sellItem, 580.00, null, null);
            LocalDateTime borrowStart = LocalDateTime.now().plusSeconds(1);
            Deal lendDeal = createDeal(testBuyer2, lendItem, null, borrowStart, borrowStart.plusDays(3));
            createDeal(testBuyer1, freeItem, null, null, null);

            LocalDateTime sellT1 = sellDeal.getCreatedAt().plusSeconds(2);
            transitionDeal(sellDeal.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED, sellT1);
            transitionDeal(sellDeal.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED, sellT1.plusSeconds(2));

            LocalDateTime lendT1 = borrowStart.plusSeconds(2);
            transitionDeal(lendDeal.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED, lendT1);
            transitionDeal(lendDeal.getId(), DealStatus.ACCEPTED, 2L, DealStatus.RETURNED, lendT1.plusSeconds(2));

            // Filter by keyword (prefix) -> all 3 deals
            assertEquals(3, dealRepository.searchDeals(prefix, null, null, null, null).size());

            // Filter by Deal Type
            List<Deal> bought = dealRepository.searchDeals(prefix, ListingType.SELL, null, null, null);
            assertEquals(1, bought.size());
            assertEquals(prefix + "Monitor", bought.get(0).getListingTitle());
            assertEquals(DealStatus.COMPLETED, bought.get(0).getStatus());
            assertNotNull(bought.get(0).getCompletedAt());

            List<Deal> borrowed = dealRepository.searchDeals(prefix, ListingType.LEND, null, null, null);
            assertEquals(1, borrowed.size());
            assertEquals(DealStatus.RETURNED, borrowed.get(0).getStatus());
            assertNotNull(borrowed.get(0).getBorrowStart());
            assertNotNull(borrowed.get(0).getReturnedAt());

            List<Deal> received = dealRepository.searchDeals(prefix, ListingType.GIVEAWAY, null, null, null);
            assertEquals(1, received.size());
            assertEquals(testSeller2.getUsername(), received.get(0).getSellerUsername());

            // Filter by buyer username & seller username
            assertEquals(2, dealRepository.searchDeals(testBuyer1.getUsername(), null, null, null, null).size());
            assertEquals(1, dealRepository.searchDeals(testSeller2.getUsername(), null, null, null, null).size());
        }

        /**
         * Test Case: TC-DLS-02
         * Content: Inverted date range (from > to) is rejected by DealRepository.searchDeals.
         * Steps:
         *   1. Call searchDeals with from = tomorrow, to = today.
         * Expected Result:
         *   - Throws IllegalArgumentException.
         */
        @Test
        @DisplayName("TC-DLS-02: Inverted date range throws IllegalArgumentException")
        void searchDealsRejectsInvertedDateRange() {
            LocalDate today = LocalDate.now();
            assertThrows(IllegalArgumentException.class, () ->
                    dealRepository.searchDeals(prefix, null, null, today.plusDays(1), today));
        }
    }

    // =========================================================================
    // TASK 3.6: NOTIFICATION BOARD
    // =========================================================================

    @Nested
    @DisplayName("Task 3.6 — Notification Board")
    class Task36_NotificationBoardTests {

        /**
         * Test Case: TC-NTF-01
         * Content: Seller creating a listing notifies active admins with category, title, and timestamp.
         * Steps:
         *   1. testSeller1 creates a listing via listingService.createListing.
         *   2. Fetch unread admin notifications via adminNotificationService.
         * Expected Result:
         *   - Unread notification exists with category="New Listing", title="New Listing Submitted",
         *     and non-null timestamp.
         */
        @Test
        @DisplayName("TC-NTF-01: New seller listing creates categorized, timestamped admin notification")
        void sellerListingSubmissionNotifiesAdmins() throws SQLException {
            createSellerListing(testSeller1, prefix + "Wireless Mouse", ListingType.SELL,
                    90.00, null, null, List.of());

            List<Notification> unread = adminNotificationService.getNotifications(testAdmin, false);
            Optional<Notification> match = unread.stream()
                    .filter(n -> n.getMessage().contains(prefix + "Wireless Mouse"))
                    .findFirst();

            assertTrue(match.isPresent());
            assertEquals("New Listing", match.get().getCategory());
            assertEquals("New Listing Submitted", match.get().getTitle());
            assertNotNull(match.get().getTimestamp());
            assertFalse(match.get().isRead());
        }

        /**
         * Test Case: TC-NTF-02
         * Content: Account-change audit alerts notify active admins only.
         * Steps:
         *   1. Deactivate testAdmin2.
         *   2. Call notificationRepository.notifyAllAdmins("Account", "Account Deactivated", ...).
         * Expected Result:
         *   - Active testAdmin receives the notification; inactive testAdmin2 does not.
         */
        @Test
        @DisplayName("TC-NTF-02: notifyAllAdmins delivers account-change notifications only to active admins")
        void notifyAllAdminsTargetsOnlyActiveAdmins() throws SQLException {
            userRepository.setActive(testAdmin2.getUsername(), false);

            int count = notificationRepository.notifyAllAdmins(
                    "Account", "Account Deactivated", "Test audit event for " + prefix);

            assertTrue(count >= 1);
            assertTrue(notificationRepository.findByRecipient(testAdmin.getUsername(), false).stream()
                    .anyMatch(n -> "Account".equals(n.getCategory()) && n.getMessage().contains(prefix)));
            assertTrue(notificationRepository.findByRecipient(testAdmin2.getUsername(), true).stream()
                    .noneMatch(n -> n.getMessage().contains(prefix)));
        }

        /**
         * Test Case: TC-NTF-03
         * Content: Buyer reporting a live listing generates a moderation alert for admins.
         * Steps:
         *   1. Approve a listing to LIVE.
         *   2. testBuyer1 calls buyerListingService.reportListing with a reason.
         * Expected Result:
         *   - testAdmin receives a timestamped notification with category="Listing Report"
         *     and title="Listing Reported" containing the reason.
         */
        @Test
        @DisplayName("TC-NTF-03: Buyer reporting a listing notifies admins for moderation")
        void reportedListingNotifiesAdmins() throws SQLException {
            Listing live = createSellerListing(testSeller1, prefix + "Suspicious Item", ListingType.SELL,
                    200.00, null, null, List.of());
            approveListing(live);

            buyerListingService.reportListing(testBuyer1, live.getId(), "Counterfeit textbook " + prefix);

            List<Notification> unread = adminNotificationService.getNotifications(testAdmin, false);
            assertTrue(unread.stream().anyMatch(n ->
                    "Listing Report".equals(n.getCategory())
                            && "Listing Reported".equals(n.getTitle())
                            && n.getMessage().contains("Counterfeit textbook " + prefix)));
        }

        /**
         * Test Case: TC-NTF-04
         * Content: Mark-as-read persists isRead=TRUE, supports unread/read toggle, and enforces recipient isolation.
         * Steps:
         *   1. Create unread notifications for testAdmin and testBuyer1.
         *   2. Verify testAdmin cannot mark testBuyer1's notification as read (returns false).
         *   3. Mark testAdmin's notification as read -> true on first call, false on repeat.
         *   4. Verify unread list excludes it and includeRead=true list returns it with isRead=true.
         */
        @Test
        @DisplayName("TC-NTF-04: Mark-as-read persists isRead=TRUE and enforces recipient isolation")
        void markAsReadAndRecipientIsolation() throws SQLException {
            Notification adminNotif = new Notification(
                    testAdmin.getUsername(), "System Notice", "Admin alert " + prefix, "System");
            Notification buyerNotif = new Notification(
                    testBuyer1.getUsername(), "Buyer Notice", "Buyer alert " + prefix, "System");
            notificationRepository.create(adminNotif);
            notificationRepository.create(buyerNotif);

            // Cross-user isolation
            assertFalse(adminNotificationService.markAsRead(testAdmin, buyerNotif.getId()));

            // Own notification mark-as-read
            assertTrue(adminNotificationService.markAsRead(testAdmin, adminNotif.getId()));
            assertFalse(adminNotificationService.markAsRead(testAdmin, adminNotif.getId()));

            // Unread vs read history filtering
            assertTrue(adminNotificationService.getNotifications(testAdmin, false).stream()
                    .noneMatch(n -> n.getId().equals(adminNotif.getId())));
            assertTrue(adminNotificationService.getNotifications(testAdmin, true).stream()
                    .anyMatch(n -> n.getId().equals(adminNotif.getId()) && n.isRead()));
        }
    }

    // =========================================================================
    // TASK 3.7: MANAGE ALL LISTINGS SCREEN
    // =========================================================================

    @Nested
    @DisplayName("Task 3.7 — Manage All Listings Screen")
    class Task37_ManageAllListingsTests {

        /**
         * Test Case: TC-LST-01
         * Content: Admin creates a listing on behalf of an active seller; rejects inactive sellers and buyers.
         * Steps:
         *   1. testAdmin creates a LEND listing on behalf of testSeller1.
         *   2. Verify owner is testSeller1, status is PENDING_REVIEW, and other active admin (testAdmin2)
         *      is notified while acting admin (testAdmin) is excluded.
         *   3. Attempt creation for deactivated testSeller2 and for testBuyer1 -> IllegalArgumentException.
         */
        @Test
        @DisplayName("TC-LST-01: Admin creates listing on behalf of active seller and notifies other admins")
        void adminCreatesListingOnBehalfOfActiveSeller() throws SQLException {
            Listing toCreate = buildListingModel(prefix + "Admin Created Chair", ListingType.LEND,
                    null, 2, LendingUnit.WEEKS, List.of());

            listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), toCreate);
            createdListingIds.add(toCreate.getId());

            Listing saved = listingRepository.findByIdAndSeller(toCreate.getId(), testSeller1.getUsername());
            assertNotNull(saved);
            assertEquals(testSeller1.getUsername(), saved.getSellerUsername());
            assertEquals(ListingStatus.PENDING_REVIEW, saved.getStatus());
            assertEquals(2, saved.getLendingDuration());
            assertEquals(LendingUnit.WEEKS, saved.getLendingUnit());

            assertTrue(notificationRepository.findByRecipient(testAdmin2.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Created by Admin".equals(n.getTitle())
                            && n.getMessage().contains(prefix + "Admin Created Chair")));
            assertTrue(notificationRepository.findByRecipient(testAdmin.getUsername(), false).stream()
                    .noneMatch(n -> "Listing Created by Admin".equals(n.getTitle())
                            && n.getMessage().contains(prefix + "Admin Created Chair")));

            userRepository.setActive(testSeller2.getUsername(), false);
            Listing bad = buildListingModel(prefix + "Bad Seller", ListingType.GIVEAWAY, null, null, null, List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testSeller2.getUsername(), bad));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testBuyer1.getUsername(), bad));
        }

        /**
         * Test Case: TC-LST-02
         * Content: Listing validation enforces positive price (<= 2 decimals), positive lending duration,
         *          and maximum 6 photos.
         * Steps:
         *   1. Attempt createListingAsAdmin with negative price, 3-decimal price, 0 duration, and 7 photos.
         * Expected Result:
         *   - Every invalid input throws IllegalArgumentException.
         */
        @Test
        @DisplayName("TC-LST-02: Admin listing validation rejects invalid price, duration, and >6 photos")
        void listingValidationRejectsInvalidFields() {
            Listing badPrice = buildListingModel(prefix + "Bad Price", ListingType.SELL, -10.0, null, null, List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), badPrice));

            Listing threeDec = buildListingModel(prefix + "3 Dec", ListingType.SELL, 12.345, null, null, List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), threeDec));

            Listing badDuration = buildListingModel(prefix + "Bad Lend", ListingType.LEND, null, 0, LendingUnit.DAYS, List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), badDuration));

            List<byte[]> sevenPhotos = List.of(
                    new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4},
                    new byte[]{5}, new byte[]{6}, new byte[]{7});
            Listing tooManyPhotos = buildListingModel(prefix + "7 Pics", ListingType.GIVEAWAY, null, null, null, sevenPhotos);
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), tooManyPhotos));
        }

        /**
         * Test Case: TC-LST-03
         * Content: Admin editing a live listing resets it to PENDING_REVIEW, cancels open proposals,
         *          and notifies both buyers and seller referencing the pre-edit listing title.
         * Steps:
         *   1. Approve "Old Bike" to LIVE; testBuyer1 makes a PENDING offer.
         *   2. testAdmin edits the listing to "Refurbished Bike".
         * Expected Result:
         *   - Title updated, status reset to PENDING_REVIEW, reviewedAt cleared.
         *   - testBuyer1's deal becomes CANCELLED.
         *   - testBuyer1 receives "Listing Changed" and testSeller1 receives "Listing Edited",
         *     both referencing "Old Bike" (the pre-edit title).
         */
        @Test
        @DisplayName("TC-LST-03: Admin edit cancels open offers and notifies buyer & seller with pre-edit title")
        void adminEditCancelsOpenOffersAndNotifiesWithOldTitle() throws SQLException {
            String oldTitle = prefix + "Old Bike";
            String newTitle = prefix + "Refurbished Bike";
            Listing listing = createSellerListing(testSeller1, oldTitle, ListingType.SELL,
                    400.00, null, null, List.of());
            approveListing(listing);

            Deal pendingDeal = createDeal(testBuyer1, listing, 350.00, null, null);

            Listing changes = buildListingModel(newTitle, ListingType.SELL, 380.00, null, null, List.of());
            listingService.updateListingAsAdmin(testAdmin, testSeller1.getUsername(), listing.getId(), changes);

            Listing updated = listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername());
            assertEquals(newTitle, updated.getTitle());
            assertEquals(ListingStatus.PENDING_REVIEW, updated.getStatus());
            assertNull(updated.getReviewedAt());

            assertEquals(DealStatus.CANCELLED, dealRepository.findById(pendingDeal.getId()).orElseThrow().getStatus());

            assertTrue(notificationRepository.findByRecipient(testBuyer1.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Changed".equals(n.getTitle())
                            && n.getMessage().contains(oldTitle)
                            && n.getMessage().contains("cancelled")));
            assertTrue(notificationRepository.findByRecipient(testSeller1.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Edited".equals(n.getTitle())
                            && n.getMessage().contains(oldTitle)));
        }

        /**
         * Test Case: TC-LST-04
         * Content: Admin edit is blocked when an ACCEPTED deal exists on the listing.
         * Steps:
         *   1. Create LIVE listing, create deal, accept deal.
         *   2. Attempt listingService.updateListingAsAdmin(...).
         * Expected Result:
         *   - Throws IllegalArgumentException; listing title remains unchanged.
         */
        @Test
        @DisplayName("TC-LST-04: Admin edit is blocked when listing has an ACCEPTED deal")
        void adminEditBlockedByAcceptedDeal() throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + "Locked Item", ListingType.SELL,
                    300.00, null, null, List.of());
            approveListing(listing);

            Deal deal = createDeal(testBuyer1, listing, 290.00, null, null);
            transitionDeal(deal.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED, deal.getCreatedAt().plusSeconds(1));

            Listing changes = buildListingModel(prefix + "Should Not Save", ListingType.SELL, 250.00, null, null, List.of());
            assertThrows(IllegalArgumentException.class, () ->
                    listingService.updateListingAsAdmin(testAdmin, testSeller1.getUsername(), listing.getId(), changes));

            assertEquals(prefix + "Locked Item",
                    listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername()).getTitle());
        }

        /**
         * Test Case: TC-LST-05
         * Content: Admin soft-removes a listing with a required reason, hides it from all finders,
         *          and notifies the seller and buyers with open offers only (not declined offers).
         * Steps:
         *   1. Approve listing to LIVE; testBuyer1 submits PENDING offer; testBuyer2 has DECLINED offer.
         *   2. Verify blank reason throws IllegalArgumentException.
         *   3. Remove with valid reason -> true; repeat removal -> false.
         * Expected Result:
         *   - DB row kept with listingStatus='REMOVED'; excluded from findAllListing / findByStatus / findByIdAndSeller.
         *   - testSeller1 and testBuyer1 receive "Listing Removed" with reason; testBuyer2 is not notified.
         */
        @Test
        @DisplayName("TC-LST-05: Admin soft-removes listing with reason and notifies seller & active-offer buyers")
        void adminRemoveSoftDeletesAndNotifiesAffectedUsers() throws SQLException {
            String title = prefix + "Prohibited Gadget";
            Listing listing = createSellerListing(testSeller1, title, ListingType.SELL,
                    500.00, null, null, List.of());
            approveListing(listing);

            createDeal(testBuyer1, listing, 480.00, null, null);
            Deal declinedDeal = createDeal(testBuyer2, listing, 450.00, null, null);
            transitionDeal(declinedDeal.getId(), DealStatus.PENDING, 1L, DealStatus.DECLINED,
                    declinedDeal.getCreatedAt().plusSeconds(1));

            // Blank reason rejected
            assertThrows(IllegalArgumentException.class, () ->
                    listingService.removeListingAsAdmin(testAdmin, listing.getId(), testSeller1.getUsername(), "   "));

            // Valid removal
            assertTrue(listingService.removeListingAsAdmin(
                    testAdmin, listing.getId(), testSeller1.getUsername(), "Violates campus rules"));

            // Duplicate removal returns false
            assertFalse(listingService.removeListingAsAdmin(
                    testAdmin, listing.getId(), testSeller1.getUsername(), "Duplicate remove"));

            // Soft-deleted in DB, hidden from all finders
            assertNull(listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername()));
            assertTrue(listingRepository.findAllListing().stream().noneMatch(l -> l.getId().equals(listing.getId())));
            assertTrue(listingRepository.findByStatus(ListingStatus.REMOVED).isEmpty());
            assertEquals("REMOVED", queryStringColumn("SELECT listingStatus FROM listings WHERE id = ?", listing.getId()));

            // Seller & active-offer buyer notified; declined buyer not notified
            assertTrue(notificationRepository.findByRecipient(testSeller1.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Removed".equals(n.getTitle())
                            && n.getMessage().contains("Violates campus rules")));
            assertTrue(notificationRepository.findByRecipient(testBuyer1.getUsername(), false).stream()
                    .anyMatch(n -> "Listing Removed".equals(n.getTitle())
                            && n.getMessage().contains("Violates campus rules")));
            assertTrue(notificationRepository.findByRecipient(testBuyer2.getUsername(), false).stream()
                    .noneMatch(n -> "Listing Removed".equals(n.getTitle()) && n.getMessage().contains(title)));
        }

        /**
         * Test Case: TC-LST-06
         * Content: Authorization guard blocks deactivated admins and non-admin callers from admin listing actions.
         * Steps:
         *   1. Deactivate testAdmin2; attempt createListingAsAdmin and updateListingAsAdmin.
         *   2. Attempt removeListingAsAdmin as testBuyer1.
         * Expected Result:
         *   - All throw IllegalArgumentException.
         */
        @Test
        @DisplayName("TC-LST-06: Deactivated admins and non-admins are blocked from admin listing operations")
        void inactiveAdminAndNonAdminBlockedFromListingActions() throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + "Protected Listing", ListingType.SELL,
                    100.00, null, null, List.of());
            userRepository.setActive(testAdmin2.getUsername(), false);

            Listing payload = buildListingModel(prefix + "Attempt", ListingType.SELL, 99.00, null, null, List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin2, testSeller1.getUsername(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.updateListingAsAdmin(testAdmin2, testSeller1.getUsername(), listing.getId(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.removeListingAsAdmin(testBuyer1, listing.getId(), testSeller1.getUsername(), "Reason"));
        }
    }

    // =========================================================================
    // TASK 3.8: SELLER RATINGS OVERVIEW
    // =========================================================================

    @Nested
    @DisplayName("Task 3.8 — Seller Ratings Overview")
    class Task38_SellerRatingsOverviewTests {

        /**
         * Test Case: TC-RAT-01
         * Content: Seller Ratings search filters by seller username keyword, exact score (1..5),
         *          and inclusive created-date bounds, returning newest reviews first.
         * Steps:
         *   1. Insert 3 reviews spanning two sellers, scores 5 and 2, and distinct dates.
         *   2. Query reviewRepository.searchReviews(...) with seller, score, date bounds, and invalid inputs.
         * Expected Result:
         *   - Correct subsets in newest-first order; invalid rating (0/6) and inverted dates throw IllegalArgumentException.
         */
        @Test
        @DisplayName("TC-RAT-01: Seller Ratings search filters by seller, score, and inclusive date range")
        void searchReviewsFiltersBySellerScoreAndDate() throws SQLException {
            LocalDate today = LocalDate.now();
            String rev1 = createCompletedDealWithReview(testBuyer1, testSeller1, prefix + "ItemA",
                    5, "Great seller " + prefix, today.minusDays(5).atTime(12, 0));
            String rev2 = createCompletedDealWithReview(testBuyer2, testSeller1, prefix + "ItemB",
                    2, "Late meetup " + prefix, today.minusDays(2).atTime(15, 30));
            String rev3 = createCompletedDealWithReview(testBuyer1, testSeller2, prefix + "ItemC",
                    5, "Friendly " + prefix, today.atTime(9, 0));

            // Seller filter (newest first: rev2 then rev1)
            List<Review> seller1Reviews = reviewRepository.searchReviews(testSeller1.getUsername(), null, null, null);
            assertEquals(2, seller1Reviews.size());
            assertEquals(rev2, seller1Reviews.get(0).getId());
            assertEquals(rev1, seller1Reviews.get(1).getId());

            // Seller + exact score
            List<Review> seller1FiveStar = reviewRepository.searchReviews(testSeller1.getUsername(), 5, null, null);
            assertEquals(1, seller1FiveStar.size());
            assertEquals(rev1, seller1FiveStar.get(0).getId());

            // Inclusive date boundaries
            List<Review> bounded = reviewRepository.searchReviews(
                    testSeller1.getUsername(), null, today.minusDays(5), today.minusDays(2));
            assertEquals(2, bounded.size());

            List<Review> todayOnly = reviewRepository.searchReviews(
                    testSeller2.getUsername(), null, today, today);
            assertEquals(1, todayOnly.size());
            assertEquals(rev3, todayOnly.get(0).getId());

            // Invalid filters rejected
            assertThrows(IllegalArgumentException.class,
                    () -> reviewRepository.searchReviews(testSeller1.getUsername(), 0, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> reviewRepository.searchReviews(testSeller1.getUsername(), 6, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> reviewRepository.searchReviews(testSeller1.getUsername(), null, today, today.minusDays(1)));
        }

        /**
         * Test Case: TC-RAT-02
         * Content: Hiding an inappropriate review persists hidden=TRUE, keeps it visible in admin overview,
         *          and excludes it from seller-facing reviews and SellerStats average rating.
         * Steps:
         *   1. Create a 5-star review and a 1-star review for testSeller1 (initial avg = 3.0).
         *   2. Hide the 1-star review via reviewRepository.hide(badReviewId, false).
         * Expected Result:
         *   - Admin searchReviews still shows both rows with badReview.isHidden() == true.
         *   - Seller findBySeller returns only the 5-star review.
         *   - SellerStatsRepository averageRating updates from 3.0 to 5.0.
         *   - Blank ID throws IllegalArgumentException; unknown ID returns false.
         */
        @Test
        @DisplayName("TC-RAT-02: Hiding a review persists hidden=TRUE and excludes it from seller view and stats")
        void hideReviewExcludesFromSellerViewAndStats() throws SQLException {
            LocalDateTime now = LocalDateTime.now();
            String goodReviewId = createCompletedDealWithReview(testBuyer1, testSeller1, prefix + "GoodBook",
                    5, "Excellent condition " + prefix, now.minusMinutes(10));
            String badReviewId = createCompletedDealWithReview(testBuyer2, testSeller1, prefix + "BadBook",
                    1, "Inappropriate comment " + prefix, now.minusMinutes(5));

            assertEquals(3.0, sellerStatsRepository.findBySeller(testSeller1.getUsername()).averageRating(), 0.001);

            // Hide: repository writes hidden = !currentHidden -> TRUE
            assertTrue(reviewRepository.hide(badReviewId, false));

            // Admin view retains hidden review with hidden=true
            List<Review> adminView = reviewRepository.searchReviews(testSeller1.getUsername(), null, null, null);
            assertEquals(2, adminView.size());
            Review hiddenRow = adminView.stream().filter(r -> r.getId().equals(badReviewId)).findFirst().orElseThrow();
            assertTrue(hiddenRow.isHidden());

            // Seller view excludes hidden review
            try (Connection conn = DatabaseManager.getConnection()) {
                List<Review> sellerView = reviewRepository.findBySeller(conn, testSeller1.getUsername());
                assertEquals(1, sellerView.size());
                assertEquals(goodReviewId, sellerView.get(0).getId());
            }

            // Seller stats average recalculates without the hidden review
            assertEquals(5.0, sellerStatsRepository.findBySeller(testSeller1.getUsername()).averageRating(), 0.001);

            // Validation & missing ID
            assertThrows(IllegalArgumentException.class, () -> reviewRepository.hide("  ", false));
            assertFalse(reviewRepository.hide("nonexistent-" + prefix, false));
        }
    }

    // =========================================================================
    // ADDITIONAL REQUIRED / EDGE-COVERAGE CASES (TODO GAPS)
    // =========================================================================

    @Nested
    @DisplayName("Task 3 — Additional required and edge coverage")
    class Task3CoverageGapTests {

        @Test
        @DisplayName("TC-ADM-07: seeded hash, legacy-login upgrade, migration idempotency, oversized password")
        void seededHashAndLegacyMigration() throws SQLException {
            User seeded = userRepository.findByUsername("admin");
            assertNotNull(seeded);
            assertTrue(PasswordHasher.isHash(seeded.getPasswordHash()),
                    "Seeded admin password must never be stored as plaintext.");

            String legacyOne = prefix + "legacy1";
            insertLegacyUser(legacyOne, "Legacy Buyer", "LegacyPass1", UserRole.BUYER);
            AuthService.LoginResult upgraded = authService.login(legacyOne, "LegacyPass1", UserRole.BUYER);
            assertEquals(AuthService.LoginStatus.SUCCESS, upgraded.status());
            User legacyReloaded = userRepository.findByUsername(legacyOne);
            assertNotNull(legacyReloaded);
            assertTrue(PasswordHasher.isHash(legacyReloaded.getPasswordHash()));
            assertTrue(PasswordHasher.verify("LegacyPass1", legacyReloaded.getPasswordHash()));

            String legacyTwo = prefix + "legacy2";
            insertLegacyUser(legacyTwo, "Legacy Seller", "LegacyPass2", UserRole.SELLER);
            String unchangedAdminHash = userRepository.findByUsername(testAdmin2.getUsername()).getPasswordHash();
            assertTrue(authService.migrateLegacyPasswords() >= 1,
                    "The remaining legacy row should be upgraded.");
            User legacyTwoReloaded = userRepository.findByUsername(legacyTwo);
            assertTrue(PasswordHasher.isHash(legacyTwoReloaded.getPasswordHash()));
            assertEquals(unchangedAdminHash,
                    userRepository.findByUsername(testAdmin2.getUsername()).getPasswordHash(),
                    "Migration must not overwrite an already-hashed admin password.");
            assertEquals(0, authService.migrateLegacyPasswords(),
                    "A second migration pass must be idempotent.");

            String oversized = "A".repeat(PasswordHasher.MAX_PASSWORD_LENGTH + 1);
            assertEquals(AuthService.LoginStatus.INVALID_INPUT,
                    authService.login(testAdmin.getUsername(), oversized, UserRole.ADMIN).status());
        }

        @Test
        @DisplayName("TC-REV-06: six-photo queue preserves every photo in position order")
        void sixPhotoPendingQueuePreservesOrder() throws SQLException {
            List<byte[]> photos = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                byte[] bytes = new byte[]{(byte) i, (byte) (i + 1), (byte) (i + 2)};
                photos.add(bytes);
            }
            Listing listing = createSellerListing(testSeller1, prefix + "SixPhoto",
                    ListingType.SELL, 50.00, null, null, photos);

            Listing loaded = listingRepository.findByStatus(ListingStatus.PENDING_REVIEW).stream()
                    .filter(l -> l.getId().equals(listing.getId()))
                    .findFirst().orElseThrow();
            assertEquals(6, loaded.getPhotos().size());
            for (int i = 0; i < 6; i++) {
                assertTrue(Arrays.equals(photos.get(i), loaded.getPhotos().get(i)),
                        "Photo at position " + (i + 1) + " changed order.");
            }
        }

        @Test
        @DisplayName("TC-REV-07: stale rejection keeps seller's edited pending listing")
        void staleRejectKeepsEditedListing() throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + "StaleReject",
                    ListingType.SELL, 100.00, null, null, List.of());
            Listing loaded = listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername());
            long staleVersion = loaded.getRowVersion();

            Listing edited = buildListingModel(prefix + "Edited Before Reject", ListingType.SELL,
                    120.00, null, null, List.of());
            listingRepository.updateOwned(listing.getId(), testSeller1.getUsername(), edited);

            assertFalse(listingRepository.updateReview(
                    listing.getId(), ListingStatus.REJECTED, "Too late", staleVersion));
            Listing after = listingRepository.findByIdAndSeller(listing.getId(), testSeller1.getUsername());
            assertEquals(ListingStatus.PENDING_REVIEW, after.getStatus());
            assertEquals(prefix + "Edited Before Reject", after.getTitle());
            assertNull(after.getRejectionReason());
        }

        @Test
        @DisplayName("TC-REV-08: pending/rejected listings stay hidden and LIVE becomes buyer-visible")
        void buyerVisibilityTracksReviewStatus() throws SQLException {
            Listing pending = createSellerListing(testSeller1, prefix + "BuyerInvisible",
                    ListingType.SELL, 25.00, null, null, List.of());
            assertFalse(liveIds(testBuyer1).contains(pending.getId()));

            assertTrue(listingRepository.updateReview(pending.getId(), ListingStatus.REJECTED,
                    "No", listingRepository.findByIdAndSeller(pending.getId(), testSeller1.getUsername()).getRowVersion()));
            assertFalse(liveIds(testBuyer1).contains(pending.getId()));

            Listing live = createSellerListing(testSeller1, prefix + "BuyerVisible",
                    ListingType.SELL, 30.00, null, null, List.of());
            approveListing(live);
            assertTrue(liveIds(testBuyer1).contains(live.getId()));
            assertEquals(ListingStatus.LIVE, buyerListingService.getListing(testBuyer1, live.getId()).getStatus());
        }

        @Test
        @DisplayName("TC-USR-06: deleting seller first preserves history; then deleting buyer hides it")
        void reverseDeletionOrderPreservesHistory() throws SQLException {
            Listing pendingListing = approvedListing("OpenDeal");
            Listing counterListing = approvedListing("CounterDeal");
            Listing acceptedListing = approvedListing("AcceptedDeal");
            Listing declinedListing = approvedListing("DeclinedDeal");
            Listing returnedListing = approvedLendListing("ReturnedDeal");
            Listing completedListing = approvedListing("CompletedDeal");
            Listing reportedListing = approvedListing("ReportedDeal");

            Deal pending = createDeal(testBuyer1, pendingListing, 90.00, null, null);
            Deal counter = createDeal(testBuyer1, counterListing, 90.00, null, null);
            counterDeal(counter);
            Deal accepted = createDeal(testBuyer1, acceptedListing, 90.00, null, null);
            transitionDeal(accepted.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    accepted.getCreatedAt().plusSeconds(1));
            Deal declined = createDeal(testBuyer1, declinedListing, 90.00, null, null);
            transitionDeal(declined.getId(), DealStatus.PENDING, 1L, DealStatus.DECLINED,
                    declined.getCreatedAt().plusSeconds(1));
            Deal returned = createDeal(testBuyer1, returnedListing, null,
                    LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusMinutes(2));
            transitionDeal(returned.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    returned.getCreatedAt().plusSeconds(1));
            transitionDeal(returned.getId(), DealStatus.ACCEPTED, 2L, DealStatus.RETURNED,
                    LocalDateTime.now().plusMinutes(3));
            Deal completed = createDeal(testBuyer1, completedListing, 90.00, null, null);
            transitionDeal(completed.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    completed.getCreatedAt().plusSeconds(1));
            transitionDeal(completed.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED,
                    completed.getCreatedAt().plusSeconds(2));

            ChatThread thread = createChatThread(pending);
            sendChatMessage(thread, testBuyer1, "Message " + prefix);
            buyerListingService.reportListing(testBuyer1, reportedListing.getId(), "Reported " + prefix);

            authService.deleteUserAsAdmin(testAdmin, testSeller1.getUsername());
            assertNull(userRepository.findByUsername(testSeller1.getUsername()));

            for (Listing listing : List.of(pendingListing, counterListing, acceptedListing,
                    declinedListing, returnedListing, completedListing, reportedListing)) {
                assertEquals("REMOVED", queryStringColumn(
                        "SELECT listingStatus FROM listings WHERE id = ?", listing.getId()));
                assertNull(queryStringColumn("SELECT seller_username FROM listings WHERE id = ?", listing.getId()));
            }

            assertEquals(DealStatus.CANCELLED, dealAfterDeletion(pending));
            assertEquals(DealStatus.CANCELLED, dealAfterDeletion(counter));
            assertEquals(DealStatus.CANCELLED, dealAfterDeletion(accepted));
            assertEquals(DealStatus.DECLINED, dealAfterDeletion(declined));
            assertEquals(DealStatus.RETURNED, dealAfterDeletion(returned));
            assertEquals(DealStatus.COMPLETED, dealAfterDeletion(completed));
            for (Deal deal : List.of(pending, counter, accepted, declined, returned, completed)) {
                assertEquals(1, queryIntColumn("SELECT deleted FROM deals WHERE id = ?", deal.getId()));
                assertNull(queryStringColumn("SELECT seller_username FROM deals WHERE id = ?", deal.getId()));
            }

            assertEquals(1, queryIntColumn("SELECT deleted FROM chat_threads WHERE id = ?", thread.id()));
            try (Connection conn = DatabaseManager.getConnection()) {
                assertTrue(chatRepository.findThreads(conn, testBuyer1.getUsername()).stream()
                        .anyMatch(t -> t.id().equals(thread.id())));
                assertEquals(1, chatRepository.findMessages(conn, thread.id(), null).size());
            }
            assertEquals(testBuyer1.getUsername(), queryStringColumn(
                    "SELECT buyer_username FROM listing_reports WHERE listing_id = ?", reportedListing.getId()));

            assertEquals(AuthService.LoginStatus.USER_NOT_FOUND,
                    authService.login(testSeller1.getUsername(), "SellerPass1", UserRole.SELLER).status());

            authService.deleteUserAsAdmin(testAdmin, testBuyer1.getUsername());
            assertNull(userRepository.findByUsername(testBuyer1.getUsername()));
            for (Deal deal : List.of(pending, counter, accepted, declined, returned, completed)) {
                assertTrue(dealRepository.findById(deal.getId()).isEmpty());
                assertEquals(2, queryIntColumn("SELECT deleted FROM deals WHERE id = ?", deal.getId()));
                assertNull(queryStringColumn("SELECT buyer_username FROM deals WHERE id = ?", deal.getId()));
            }
            assertEquals(2, queryIntColumn("SELECT deleted FROM chat_threads WHERE id = ?", thread.id()));
            try (Connection conn = DatabaseManager.getConnection()) {
                assertTrue(chatRepository.findThreads(conn, testBuyer1.getUsername()).isEmpty());
                assertEquals(1, chatRepository.findMessages(conn, thread.id(), null).size(),
                        "Chat messages are retained even when the thread is soft-deleted.");
            }
            assertNull(queryStringColumn(
                    "SELECT buyer_username FROM listing_reports WHERE listing_id = ?", reportedListing.getId()));
            assertEquals(AuthService.LoginStatus.USER_NOT_FOUND,
                    authService.login(testBuyer1.getUsername(), "BuyerPass1", UserRole.BUYER).status());
        }

        @Test
        @DisplayName("TC-PRF-04: weak/oversized password update is atomic and leaves name/hash unchanged")
        void profileUpdateIsAtomic() throws SQLException {
            User before = userRepository.findByUsername(testAdmin.getUsername());
            User update = new User();
            update.setUsername(testAdmin.getUsername());
            update.setFullName("Should Not Persist");

            assertThrows(IllegalArgumentException.class,
                    () -> authService.updateProfile(update, "weak"));
            User afterWeak = userRepository.findByUsername(testAdmin.getUsername());
            assertEquals(before.getFullName(), afterWeak.getFullName());
            assertEquals(before.getPasswordHash(), afterWeak.getPasswordHash());

            assertThrows(IllegalArgumentException.class,
                    () -> authService.updateProfile(update, "A".repeat(PasswordHasher.MAX_PASSWORD_LENGTH + 1)));
            User afterHuge = userRepository.findByUsername(testAdmin.getUsername());
            assertEquals(before.getFullName(), afterHuge.getFullName());
            assertEquals(before.getPasswordHash(), afterHuge.getPasswordHash());
        }

        @Test
        @DisplayName("TC-USR-07: null and non-admin callers cannot permanently delete users")
        void deleteUserAuthorizationRejectsNullAndNonAdminCallers() throws SQLException {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.deleteUserAsAdmin(null, testBuyer1.getUsername()));
            assertThrows(IllegalArgumentException.class,
                    () -> authService.deleteUserAsAdmin(testBuyer1, testBuyer2.getUsername()));
            assertNotNull(userRepository.findByUsername(testBuyer1.getUsername()));
            assertNotNull(userRepository.findByUsername(testBuyer2.getUsername()));
        }

        @Test
        @DisplayName("TC-DLS-02: deal search inclusive dates, one-sided bounds, status and deleted=2 filters")
        void dealSearchDateAndStatusFilters() throws SQLException {
            LocalDate today = LocalDate.now();
            Listing listing = approvedListing("SearchDeal");
            Deal first = createDeal(testBuyer1, listing, 90.00, null, null);
            Deal second = createDeal(testBuyer2, listing, 80.00, null, null);
            Deal third = createDeal(testBuyer1, listing, 70.00, null, null);
            transitionDeal(second.getId(), DealStatus.PENDING, 1L, DealStatus.CANCELLED,
                    second.getCreatedAt().plusSeconds(1));
            transitionDeal(third.getId(), DealStatus.PENDING, 1L, DealStatus.DECLINED,
                    third.getCreatedAt().plusSeconds(1));
            setDealCreated(first.getId(), today.minusDays(2).atTime(12, 0));
            setDealCreated(second.getId(), today.minusDays(1).atTime(12, 0));
            setDealCreated(third.getId(), today.plusDays(1).atTime(12, 0));

            assertTrue(ids(dealRepository.searchDeals(prefix, ListingType.SELL, null,
                    today.minusDays(2), today.minusDays(1))).containsAll(List.of(first.getId(), second.getId())));
            assertFalse(ids(dealRepository.searchDeals(prefix, ListingType.SELL, null,
                    today.minusDays(2), today.minusDays(1))).contains(third.getId()));
            assertEquals(List.of(second.getId()), ids(dealRepository.searchDeals(prefix, null, null,
                    today.minusDays(1), today.minusDays(1))));
            assertTrue(ids(dealRepository.searchDeals(prefix, null, null, today.minusDays(1), null))
                    .containsAll(List.of(second.getId(), third.getId())));
            assertEquals(List.of(first.getId()), ids(dealRepository.searchDeals(prefix, null, null,
                    null, today.minusDays(2))));
            assertEquals(List.of(first.getId()), ids(dealRepository.searchDeals(prefix, null, "PENDING", null, null)));
            assertEquals(List.of(second.getId()), ids(dealRepository.searchDeals(prefix, null, "CANCELLED", null, null)));

            setDealDeleted(third.getId(), 2);
            assertTrue(dealRepository.findById(third.getId()).isEmpty());
            List<String> activeIds = ids(dealRepository.searchDeals(prefix, null, null, null, null));
            assertEquals(2, activeIds.size());
            assertFalse(activeIds.contains(third.getId()));
        }

        @Test
        @DisplayName("TC-NTF-05: announcement delivery and NotificationService role/active guards")
        void announcementAndNotificationAuthorization() throws SQLException {
            assertTrue(notificationRepository.notifyAllAdmins("Announcement", "Maintenance",
                    "Announcement body " + prefix) >= 1);
            assertTrue(adminNotificationService.getNotifications(testAdmin, false).stream()
                    .anyMatch(n -> "Announcement".equals(n.getCategory())
                            && "Maintenance".equals(n.getTitle())
                            && n.getMessage().contains(prefix)));

            Notification own = new Notification(testAdmin.getUsername(), "System Notice",
                    "Guard check " + prefix, "System");
            notificationRepository.create(own);
            assertThrows(IllegalArgumentException.class,
                    () -> adminNotificationService.getNotifications(testBuyer1, false));
            assertThrows(IllegalArgumentException.class,
                    () -> adminNotificationService.getNotifications(testSeller1, false));
            userRepository.setActive(testAdmin2.getUsername(), false);
            assertThrows(IllegalArgumentException.class,
                    () -> adminNotificationService.getNotifications(testAdmin2, false));
            assertThrows(IllegalArgumentException.class,
                    () -> adminNotificationService.markAsRead(testBuyer1, own.getId()));
        }

        @Test
        @DisplayName("TC-LST-07: admin create SELL/GIVEAWAY/LEND(SECONDS), six-photo boundary and empty photo")
        void adminCreationTypesAndPhotoBoundaries() throws SQLException {
            Listing sell = buildListingModel(prefix + "AdminSell", ListingType.SELL,
                    12.34, null, null, List.of());
            listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), sell);
            createdListingIds.add(sell.getId());
            Listing sellLoaded = listingRepository.findByIdAndSeller(sell.getId(), testSeller1.getUsername());
            assertEquals(ListingStatus.PENDING_REVIEW, sellLoaded.getStatus());
            assertEquals(12.34, sellLoaded.getPrice(), 0.001);

            Listing giveaway = buildListingModel(prefix + "AdminGiveaway", ListingType.GIVEAWAY,
                    99.00, 5, LendingUnit.DAYS, List.of());
            listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), giveaway);
            createdListingIds.add(giveaway.getId());
            Listing giveawayLoaded = listingRepository.findByIdAndSeller(giveaway.getId(), testSeller1.getUsername());
            assertNull(giveawayLoaded.getPrice());
            assertNull(giveawayLoaded.getLendingDuration());
            assertNull(giveawayLoaded.getLendingUnit());

            Listing lend = buildListingModel(prefix + "AdminSeconds", ListingType.LEND,
                    null, 5, LendingUnit.SECONDS, List.of());
            listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), lend);
            createdListingIds.add(lend.getId());
            Listing lendLoaded = listingRepository.findByIdAndSeller(lend.getId(), testSeller1.getUsername());
            assertEquals(5, lendLoaded.getLendingDuration());
            assertEquals(LendingUnit.SECONDS, lendLoaded.getLendingUnit());

            List<byte[]> six = new ArrayList<>();
            for (int i = 0; i < 6; i++) six.add(new byte[]{(byte) i});
            Listing sixPhotos = buildListingModel(prefix + "AdminSixPhotos", ListingType.SELL,
                    20.00, null, null, six);
            listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), sixPhotos);
            createdListingIds.add(sixPhotos.getId());
            assertEquals(6, listingRepository.findByIdAndSeller(sixPhotos.getId(),
                    testSeller1.getUsername()).getPhotos().size());

            Listing emptyPhoto = buildListingModel(prefix + "AdminEmptyPhoto", ListingType.SELL,
                    20.00, null, null, List.of(new byte[0]));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin, testSeller1.getUsername(), emptyPhoto));
        }

        @Test
        @DisplayName("TC-LST-08: admin edit cancels COUNTERED and does not notify DECLINED/COMPLETED buyers")
        void adminEditCancelsOnlyOpenProposals() throws SQLException {
            Listing target = approvedListing("EditTarget");
            Deal countered = createDeal(testBuyer1, target, 90.00, null, null);
            counterDeal(countered);
            Deal declined = createDeal(testBuyer2, target, 80.00, null, null);
            transitionDeal(declined.getId(), DealStatus.PENDING, 1L, DealStatus.DECLINED,
                    declined.getCreatedAt().plusSeconds(1));
            Deal completed = createDeal(testBuyer1, target, 70.00, null, null);
            transitionDeal(completed.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    completed.getCreatedAt().plusSeconds(1));
            transitionDeal(completed.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED,
                    completed.getCreatedAt().plusSeconds(2));

            Listing changes = buildListingModel(prefix + "EditedTarget", ListingType.SELL,
                    110.00, null, null, List.of());
            listingService.updateListingAsAdmin(testAdmin, testSeller1.getUsername(), target.getId(), changes);

            assertEquals(DealStatus.CANCELLED, dealAfterDeletion(countered));
            assertEquals(DealStatus.DECLINED, dealAfterDeletion(declined));
            assertEquals(DealStatus.COMPLETED, dealAfterDeletion(completed));
            assertTrue(notificationRepository.findByRecipient(testBuyer1.getUsername(), true).stream()
                    .anyMatch(n -> "Listing Changed".equals(n.getTitle())
                            && n.getMessage().contains(target.getTitle())));
            assertTrue(notificationRepository.findByRecipient(testBuyer2.getUsername(), true).stream()
                    .noneMatch(n -> "Listing Changed".equals(n.getTitle())));
            assertTrue(notificationRepository.findByRecipient(testSeller1.getUsername(), true).stream()
                    .anyMatch(n -> "Listing Edited".equals(n.getTitle())));
        }

        @Test
        @DisplayName("TC-LST-09: changing listing type clears obsolete DB fields")
        void adminTypeChangeClearsObsoleteFields() throws SQLException {
            Listing mutable = createSellerListing(testSeller1, prefix + "Mutable", ListingType.SELL,
                    50.00, null, null, List.of());
            Listing asLend = buildListingModel(prefix + "NowLend", ListingType.LEND,
                    null, 7, LendingUnit.DAYS, List.of());
            listingService.updateListingAsAdmin(testAdmin, testSeller1.getUsername(), mutable.getId(), asLend);
            Listing lend = listingRepository.findByIdAndSeller(mutable.getId(), testSeller1.getUsername());
            assertEquals(ListingType.LEND, lend.getType());
            assertNull(lend.getPrice());
            assertEquals(7, lend.getLendingDuration());
            assertEquals(LendingUnit.DAYS, lend.getLendingUnit());

            Listing asGiveaway = buildListingModel(prefix + "NowGiveaway", ListingType.GIVEAWAY,
                    null, null, null, List.of());
            listingService.updateListingAsAdmin(testAdmin, testSeller1.getUsername(), mutable.getId(), asGiveaway);
            Listing giveaway = listingRepository.findByIdAndSeller(mutable.getId(), testSeller1.getUsername());
            assertEquals(ListingType.GIVEAWAY, giveaway.getType());
            assertNull(giveaway.getPrice());
            assertNull(giveaway.getLendingDuration());
            assertNull(giveaway.getLendingUnit());
        }

        @Test
        @DisplayName("TC-LST-10: soft removal preserves deals/chat/report and notifies only active offers")
        void adminRemovalPreservesHistoryAndNotifiesOnlyOpenOffers() throws SQLException {
            Listing target = approvedListing("RemoveTarget");
            Deal countered = createDeal(testBuyer1, target, 90.00, null, null);
            counterDeal(countered);
            Deal completed = createDeal(testBuyer2, target, 80.00, null, null);
            transitionDeal(completed.getId(), DealStatus.PENDING, 1L, DealStatus.ACCEPTED,
                    completed.getCreatedAt().plusSeconds(1));
            transitionDeal(completed.getId(), DealStatus.ACCEPTED, 2L, DealStatus.COMPLETED,
                    completed.getCreatedAt().plusSeconds(2));
            ChatThread thread = createChatThread(countered);
            sendChatMessage(thread, testBuyer1, "Removal history " + prefix);
            buyerListingService.reportListing(testBuyer1, target.getId(), "Report " + prefix);

            Listing other = approvedListing("OtherListing");
            createDeal(testBuyer1, other, 60.00, null, null);

            assertTrue(listingService.removeListingAsAdmin(testAdmin, target.getId(),
                    testSeller1.getUsername(), "Policy " + prefix));
            assertFalse(liveIds(testBuyer1).contains(target.getId()));
            assertNull(listingRepository.findByIdAndSeller(target.getId(), testSeller1.getUsername()));
            assertEquals("REMOVED", queryStringColumn("SELECT listingStatus FROM listings WHERE id = ?", target.getId()));
            // Soft removal preserves the deal history/statuses; active proposals are notified.
            assertEquals(DealStatus.COUNTERED, dealAfterDeletion(countered));
            assertEquals(DealStatus.COMPLETED, dealAfterDeletion(completed));
            try (Connection conn = DatabaseManager.getConnection()) {
                assertEquals(1, chatRepository.findMessages(conn, thread.id(), null).size());
            }
            assertEquals(1, queryIntColumn("SELECT COUNT(*) FROM listing_reports WHERE listing_id = ?", target.getId()));
            assertTrue(notificationRepository.findByRecipient(testBuyer1.getUsername(), true).stream()
                    .anyMatch(n -> "Listing Removed".equals(n.getTitle())
                            && n.getMessage().contains(target.getTitle())));
            assertTrue(notificationRepository.findByRecipient(testBuyer2.getUsername(), true).stream()
                    .noneMatch(n -> "Listing Removed".equals(n.getTitle())));
            assertTrue(notificationRepository.findByRecipient(testBuyer1.getUsername(), true).stream()
                    .noneMatch(n -> "Listing Removed".equals(n.getTitle())
                            && n.getMessage().contains(other.getTitle())));
        }

        @Test
        @DisplayName("TC-LST-11: create/update/remove authorization matrix (known active-admin gap reported)")
        void adminListingAuthorizationMatrix() throws SQLException {
            Listing target = createSellerListing(testSeller1, prefix + "AuthTarget",
                    ListingType.SELL, 100.00, null, null, List.of());
            Listing payload = buildListingModel(prefix + "AuthPayload", ListingType.SELL,
                    100.00, null, null, List.of());

            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testBuyer1, testSeller1.getUsername(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testSeller1, testSeller1.getUsername(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(null, testSeller1.getUsername(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.updateListingAsAdmin(testBuyer1, testSeller1.getUsername(), target.getId(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.updateListingAsAdmin(null, testSeller1.getUsername(), target.getId(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.removeListingAsAdmin(testBuyer1, target.getId(),
                            testSeller1.getUsername(), "Reason"));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.removeListingAsAdmin(testSeller1, target.getId(),
                            testSeller1.getUsername(), "Reason"));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.removeListingAsAdmin(null, target.getId(),
                            testSeller1.getUsername(), "Reason"));

            userRepository.setActive(testAdmin2.getUsername(), false);
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.createListingAsAdmin(testAdmin2, testSeller1.getUsername(), payload));
            assertThrows(IllegalArgumentException.class,
                    () -> listingService.updateListingAsAdmin(testAdmin2, testSeller1.getUsername(),
                            target.getId(), payload));
        }

        @Test
        @DisplayName("TC-RAT-03: hide/unhide round-trip, flag persistence, rating and one-sided date filters")
        void reviewUnhideFlaggedAndQueryCoverage() throws SQLException {
            LocalDate today = LocalDate.now();
            String good = createCompletedDealWithReview(testBuyer1, testSeller1,
                    prefix + "GoodReview", 5, "Good " + prefix, today.minusDays(10).atTime(12, 0));
            String bad = createCompletedDealWithReview(testBuyer2, testSeller1,
                    prefix + "BadReview", 1, "Bad " + prefix, today.minusDays(5).atTime(12, 0));

            assertEquals(3.0, sellerStatsRepository.findBySeller(testSeller1.getUsername()).averageRating(), 0.001);
            assertTrue(reviewRepository.hide(bad, false));
            assertTrue(reviewRepository.searchReviews(testSeller1.getUsername(), null, null, null).stream()
                    .filter(r -> r.getId().equals(bad)).findFirst().orElseThrow().isHidden());
            try (Connection conn = connection()) {
                assertEquals(1, reviewRepository.findBySeller(conn, testSeller1.getUsername()).size());
            }
            assertEquals(5.0, sellerStatsRepository.findBySeller(testSeller1.getUsername()).averageRating(), 0.001);

            assertTrue(reviewRepository.hide(bad, true));
            assertFalse(reviewRepository.searchReviews(testSeller1.getUsername(), null, null, null).stream()
                    .filter(r -> r.getId().equals(bad)).findFirst().orElseThrow().isHidden());
            try (Connection conn = connection()) {
                assertEquals(2, reviewRepository.findBySeller(conn, testSeller1.getUsername()).size());
            }
            assertEquals(3.0, sellerStatsRepository.findBySeller(testSeller1.getUsername()).averageRating(), 0.001);

            try (Connection conn = DatabaseManager.getConnection()) {
                reviewRepository.flag(conn, good);
            }
            assertTrue(reviewRepository.searchReviews(testSeller1.getUsername(), null, null, null).stream()
                    .filter(r -> r.getId().equals(good)).findFirst().orElseThrow().isFlagged());
            assertTrue(reviewRepository.findAll().stream()
                    .anyMatch(r -> r.getId().equals(good) && r.isFlagged()));

            assertTrue(reviewRepository.findByRating(5).stream().anyMatch(r -> r.getId().equals(good)));
            assertTrue(reviewRepository.findByRating(1).stream().anyMatch(r -> r.getId().equals(bad)));
            for (int rating = 1; rating <= 5; rating++) {
                assertNotNull(reviewRepository.findByRating(rating),
                        "findByRating(" + rating + ") must return a list.");
            }
            assertThrows(IllegalArgumentException.class, () -> reviewRepository.findByRating(0));
            assertThrows(IllegalArgumentException.class, () -> reviewRepository.findByRating(6));

            List<Review> fromOnly = reviewRepository.searchReviews(testSeller1.getUsername(), null,
                    today.minusDays(7), null);
            assertTrue(fromOnly.stream().anyMatch(r -> r.getId().equals(bad)));
            assertFalse(fromOnly.stream().anyMatch(r -> r.getId().equals(good)));
            List<Review> toOnly = reviewRepository.searchReviews(testSeller1.getUsername(), null,
                    null, today.minusDays(7));
            assertTrue(toOnly.stream().anyMatch(r -> r.getId().equals(good)));
            assertFalse(toOnly.stream().anyMatch(r -> r.getId().equals(bad)));
        }

        private Connection connection() throws SQLException {
            return DatabaseManager.getConnection();
        }

        // --- helpers local to the gap-coverage suite ---

        private Listing approvedListing(String suffix) throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + suffix,
                    ListingType.SELL, 100.00, null, null, List.of());
            approveListing(listing);
            return listing;
        }

        private Listing approvedLendListing(String suffix) throws SQLException {
            Listing listing = createSellerListing(testSeller1, prefix + suffix,
                    ListingType.LEND, null, 5, LendingUnit.DAYS, List.of());
            approveListing(listing);
            return listing;
        }

        private void insertLegacyUser(String username, String fullName, String rawPassword,
                                      UserRole role) throws SQLException {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO users (username, full_name, password_hash, role, active) "
                                 + "VALUES (?, ?, ?, ?, TRUE)")) {
                ps.setString(1, username);
                ps.setString(2, fullName);
                ps.setString(3, rawPassword);
                ps.setString(4, role.name());
                ps.executeUpdate();
            }
            createdUsernames.add(username);
        }

        private void counterDeal(Deal deal) throws SQLException {
            try (Connection conn = DatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    assertTrue(dealRepository.updateProposal(conn, deal.getId(), DealStatus.PENDING, 1L,
                            80.00, null, null));
                    conn.commit();
                } catch (SQLException | RuntimeException failure) {
                    conn.rollback();
                    throw failure;
                }
            }
        }

        private void sendChatMessage(ChatThread thread, User sender, String body) throws SQLException {
            try (Connection conn = DatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    chatRepository.insert(conn, new com.hkust.marketplace.models.ChatMessage(
                            0, thread.id(), sender.getRole(), sender.getUsername(),
                            com.hkust.marketplace.models.ChatMessage.Kind.TEXT, body,
                            null, null, null, null, null, null, null));
                    conn.commit();
                } catch (SQLException | RuntimeException failure) {
                    conn.rollback();
                    throw failure;
                }
            }
        }

        private void setDealCreated(String dealId, LocalDateTime createdAt) throws SQLException {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE deals SET createdDate = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.valueOf(createdAt));
                ps.setString(2, dealId);
                ps.executeUpdate();
            }
        }

        private void setDealDeleted(String dealId, int deleted) throws SQLException {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE deals SET deleted = ? WHERE id = ?")) {
                ps.setInt(1, deleted);
                ps.setString(2, dealId);
                ps.executeUpdate();
            }
        }

        private List<String> ids(List<Deal> deals) {
            return deals.stream().map(Deal::getId).toList();
        }

        private List<String> liveIds(User buyer) throws SQLException {
            return buyerListingService.getLiveListings(buyer).stream().map(Listing::getId).toList();
        }

        private DealStatus dealAfterDeletion(Deal deal) throws SQLException {
            return dealRepository.findById(deal.getId()).orElseThrow().getStatus();
        }
    }

    // =========================================================================
    // HELPER METHODS FOR ISOLATED TEST DATA SETUP & VERIFICATION
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
            user.setBio("Initial bio");
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
        listing.setDescription("Automated test description for " + title);
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
        boolean ok = listingRepository.updateReview(
                loaded.getId(), ListingStatus.LIVE, null, loaded.getRowVersion());
        assertTrue(ok, "Helper failed to approve test listing " + listing.getId());
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
                boolean updated = dealRepository.updateStatus(conn, dealId, expected, expectedVersion, next, at);
                assertTrue(updated, "Helper failed to transition deal " + dealId + " to " + next);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    private ChatThread createChatThread(Deal deal) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                ChatThread thread = chatRepository.findOrCreate(conn, deal);
                conn.commit();
                createdThreadIds.add(thread.id());
                return thread;
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

    private int queryIntColumn(String sql, String param) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
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
