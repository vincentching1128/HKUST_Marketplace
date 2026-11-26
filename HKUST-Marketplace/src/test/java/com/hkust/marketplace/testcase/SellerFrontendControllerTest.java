package com.hkust.marketplace.testcase;

import com.hkust.marketplace.controllers.admin.AdminDashboardController;
import com.hkust.marketplace.controllers.seller.SellerDashboardController;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.LendingUnit;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Tests Seller FXML handlers and asynchronous dashboard panes against disposable MySQL. */
@DisplayName("Task 2: Seller Portal JavaFX")
public class SellerFrontendControllerTest extends SellerTestFixture {
    private record DialogRule(String text, Consumer<DialogPane> action) {}

    private final ArrayDeque<DialogRule> dialogs = new ArrayDeque<>();
    private final List<String> dialogErrors = new ArrayList<>();
    private Stage stage;
    private Timeline watcher;
    private Window handling;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
            assertTrue(ready.await(15, TimeUnit.SECONDS), "JavaFX startup timed out");
        } catch (IllegalStateException alreadyStarted) {
            // The Admin frontend suite may have initialized the toolkit in this JVM.
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void openStage() throws Exception {
        runOnFx(() -> {
            stage = new Stage();
            SceneManager.init(stage);
            stage.setScene(new Scene(new javafx.scene.layout.Pane(), 1180, 760));
            stage.show();
            watcher = new Timeline(new KeyFrame(Duration.millis(75), event -> watchDialogs()));
            watcher.setCycleCount(Timeline.INDEFINITE);
            watcher.play();
        });
    }

    @AfterEach
    void closeStage() throws Exception {
        if (stage == null) return;
        runOnFx(() -> {
            if (watcher != null) watcher.stop();
            var openDialogs = Window.getWindows().stream().filter(Window::isShowing)
                    .filter(window -> window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane).toList();
            openDialogs.forEach(Window::hide);
            stage.hide();
            stage.setScene(null);
            SessionManager.logout();
            assertTrue(openDialogs.isEmpty(), () -> "JavaFX dialogs left open: " + openDialogs.size());
            assertTrue(dialogErrors.isEmpty(), () -> "Unexpected dialogs: " + dialogErrors);
            assertTrue(dialogs.isEmpty(), () -> "Expected dialogs did not open: " + dialogs);
        });
    }

    @Nested
    class Task21_SellerRegistrationTests {
        @Test
        void registrationValidatesFormAndSavesNormalizedSeller() throws Exception {
            runOnFx(() -> {
                load("SellerRegisterView.fxml");
                click("Create Seller Account");
                assertEquals("Username is required.", label("errorLabel").getText());
                field("usernameField").setText(prefix + "a".repeat(50));
                click("Create Seller Account");
                assertTrue(label("errorLabel").getText().contains("at most 50"));
                field("usernameField").setText(prefix.toUpperCase(Locale.ROOT) + "REGISTER");
                field("passwordField").setText(PASSWORD);
                field("confirmPasswordField").setText(PASSWORD);
                click("Create Seller Account");
                assertTrue(label("errorLabel").getText().contains("Full Name cannot be empty"));

                field("fullNameField").setText("Registered Seller");
                field("passwordField").setText("weak");
                click("Create Seller Account");
                assertTrue(label("errorLabel").getText().contains("at least 8"));

                field("passwordField").setText(PASSWORD);
                field("confirmPasswordField").setText("Mismatch42");
                click("Create Seller Account");
                assertEquals("Passwords do not match.", label("errorLabel").getText());
                field("confirmPasswordField").setText(PASSWORD);
                ((TextArea) root().lookup("#bioArea")).setText("Town hall pickup");
                select(combo("Select location"), "Library");
                click("Create Seller Account");
                assertTrue(label("successLabel").isVisible());
            });
            User saved = users.findByUsername(prefix + "register");
            assertEquals(UserRole.SELLER, saved.getRole());
            assertEquals("Town hall pickup", saved.getBio());
            assertEquals("Library", saved.getPreferredMeetup());
            assertTrue(saved.getPasswordHash().startsWith("$argon2id$"));
        }

        @Test
        void optionalFieldsCanBeOmittedAndTakenUsernamesAreRejected() throws Exception {
            runOnFx(() -> {
                load("SellerRegisterView.fxml");
                field("fullNameField").setText("Minimal Seller");
                field("passwordField").setText(PASSWORD);
                field("confirmPasswordField").setText(PASSWORD);
                for (User existing : List.of(seller, buyer, admin)) {
                    field("usernameField").setText(existing.getUsername().toUpperCase(Locale.ROOT));
                    click("Create Seller Account");
                    assertEquals("Username already taken.", label("errorLabel").getText());
                }
                field("usernameField").setText(prefix + "minimal");
                select(combo("Select location"), "None");
                click("Create Seller Account");
                assertTrue(label("successLabel").isVisible());
            });
            User saved = users.findByUsername(prefix + "minimal");
            assertNull(saved.getBio());
            assertNull(saved.getPreferredMeetup());
        }
    }

    @Nested
    class Task22_SellerLoginTests {
        @Test
        void accountLinksNavigateBetweenSellerFormsAndWelcome() throws Exception {
            runOnFx(() -> {
                load("SellerLoginView.fxml");
                clickLink("Register here");
                assertNotNull(button("Create Seller Account"));
                clickLink("Login here");
                assertNotNull(button("Login"));
                clickLink("Back to Welcome");
                click("Enter as Seller");
                clickLink("Register here");
                clickLink("Back to Welcome");
                assertNotNull(button("Enter as Seller"));
                assertNull(SessionManager.getCurrentUser());
            });
        }

        @Test
        void sellerLoginNavigatesAndLogoutClearsSession() throws Exception {
            runOnFx(() -> {
                load("SellerLoginView.fxml");
                click("Login");
                assertEquals("Please enter both username and password.", label("errorLabel").getText());
                field("usernameField").setText(seller.getUsername().toUpperCase(Locale.ROOT));
                field("passwordField").setText("Wrong123");
                click("Login");
                assertEquals("Incorrect password.", label("errorLabel").getText());
                assertNull(root().lookup(".login-success"));

                field("passwordField").setText(PASSWORD);
                click("Login");
                assertEquals(seller.getUsername(), SessionManager.getCurrentUser().getUsername());
                assertNotNull(find(root(), Button.class, b -> b.getText().strip().equals("My Listings")));
                Label feedback = (Label) root().lookup(".login-success");
                assertNotNull(feedback);
                assertEquals("Log in successful", feedback.getText());
                click("Logout");
                assertNull(SessionManager.getCurrentUser());
                assertNull(root().lookup(".login-success"));
            });
        }

        @Test
        void wrongRoleUnknownAndInactiveLoginsStayOnLoginScreen() throws Exception {
            User inactive = createUser("inactive", UserRole.SELLER, false);
            runOnFx(() -> {
                load("SellerLoginView.fxml");
                field("passwordField").setText(PASSWORD);
                for (User wrongRole : List.of(buyer, admin)) {
                    field("usernameField").setText(wrongRole.getUsername());
                    click("Login");
                    assertTrue(label("errorLabel").getText().contains("not a Seller account"));
                }
                field("usernameField").setText(prefix + "missing");
                click("Login");
                assertEquals("User not found.", label("errorLabel").getText());
                field("usernameField").setText(inactive.getUsername());
                click("Login");
                assertEquals("Account deactivated.", label("errorLabel").getText());
                assertNull(SessionManager.getCurrentUser());
            });
        }
    }

    @Nested
    class Task23_CreateNewListingTests {
        @Test
        void photoSelectionsCanBeRemovedClearedSavedAndViewed(@TempDir Path directory) throws Exception {
            File red = writePng(directory.resolve("red.png"), 0xFF0000);
            File blue = writePng(directory.resolve("blue.png"), 0x0000FF);
            File corrupt = Files.writeString(directory.resolve("invalid.png"), "not an image").toFile();
            var chosen = new AtomicReference<List<File>>();
            var controller = new SellerDashboardController() {
                @Override protected List<File> choosePhotoFiles(Window owner) {
                    assertSame(stage, owner);
                    return chosen.get();
                }
            };

            runOnFx(() -> {
                SessionManager.login(seller);
                load("SellerDashboardView.fxml", controller);
                click("Create Listing");
                select(combo("Listing type *"), "Give Away");
                click("Choose Photos");
                assertTrue(shows("0 of 6 photos selected"));

                chosen.set(java.util.Collections.nCopies(7, red));
                alert("at most six photos", ButtonType.OK);
                click("Choose Photos");
                chosen.set(List.of(red, corrupt));
                alert("unable to read a supported image", ButtonType.OK);
                click("Choose Photos");
                assertTrue(shows("0 of 6 photos selected"));

                chosen.set(List.of(red, blue));
                click("Choose Photos");
                assertTrue(shows("2 of 6 photos selected"));
                click("Remove");
                assertTrue(shows("1 of 6 photos selected"));
                click("Clear Photos");
                assertTrue(shows("0 of 6 photos selected"));
                assertNull(button("Remove"));
                click("Choose Photos");

                input("Item name *").setText(prefix + "photo-listing");
                select(combo("Category *"), "Textbooks");
                select(combo("Condition *"), "Like New");
                input("Description *").setText(prefix + "Photo gallery");
                select(combo("Meet-up location *"), "Library");
                alert("Listing saved!", ButtonType.OK);
                click("Submit for Review");
                click("View Details");
                assertTrue(shows("Photo 1 of 2"));
                ToggleButton next = find(root(), ToggleButton.class, b -> "Show photo 2".equals(b.getAccessibleText()));
                assertNotNull(next);
                assertNotNull(next.getGraphic());
                next.fire();
                assertTrue(next.isSelected());
                assertTrue(shows("Photo 2 of 2"));
            });
            var photos = listings.getMyListings(seller).getFirst().getPhotos();
            assertEquals(2, photos.size());
            assertArrayEquals(Files.readAllBytes(red.toPath()), photos.get(0));
            assertArrayEquals(Files.readAllBytes(blue.toPath()), photos.get(1));
        }

        @Test
        void creationSwapsTypeInputsAndValidatesBeforePersisting() throws Exception {
            runOnFx(() -> {
                dashboard();
                click("Create Listing");
                select(combo("Listing type *"), "Sell");
                assertTrue(input("Price (HK$)").isVisible());
                alert("Price is required.", ButtonType.OK);
                click("Submit for Review");
                input("Price (HK$)").setText("-1");
                alert("Price must be a positive number.", ButtonType.OK);
                click("Submit for Review");
                input("Price (HK$)").setText("invalid");
                alert("Enter a valid number for price.", ButtonType.OK);
                click("Submit for Review");

                select(combo("Listing type *"), "Lend");
                var duration = input("Duration");
                duration.setText("0");
                alert("Lending duration must be a positive whole number.", ButtonType.OK);
                click("Submit for Review");
                duration.setText("5");
                ComboBox<?> unit = nodes(root()).filter(ComboBox.class::isInstance).map(ComboBox.class::cast)
                        .filter(box -> box.getItems().contains(LendingUnit.SECONDS)).findFirst().orElseThrow();
                select(unit, "SECONDS");
                assertTrue(duration.isVisible());

                input("Item name *").setText(prefix + "ui-listing");
                select(combo("Category *"), "Textbooks");
                select(combo("Condition *"), "Like New");
                input("Description *").setText(prefix + "Listing description");
                select(combo("Meet-up location *"), "Library");
                alert("Listing saved!", ButtonType.OK);
                click("Submit for Review");
                assertTrue(shows(prefix + "ui-listing"));
                assertTrue(shows("PENDING REVIEW"));
            });
            Listing persisted = listings.getMyListings(seller).getFirst();
            assertEquals(ListingStatus.PENDING_REVIEW, persisted.getStatus());
            assertEquals(ListingType.LEND, persisted.getType());
            assertEquals(LendingUnit.SECONDS, persisted.getLendingUnit());
        }
    }

    @Nested
    class Task24_MyListingsTests {
        @Test
        void rejectedLoanShowsReasonAndResubmitsWithUpdatedDuration() throws Exception {
            Listing item = createListing(seller, ListingType.LEND, "rejected-loan");
            String reason = prefix + "Clarify the loan period";
            assertTrue(listingRows.updateReview(item.getId(), ListingStatus.REJECTED, reason, item.getRowVersion()));

            runOnFx(() -> {
                dashboard();
                Node card = cardFor(item.getTitle());
                assertTrue(hasLabel(card, "REJECTED"));
                assertTrue(hasLabel(card, "Rejection reason: " + reason));
                click("Edit");
                var duration = input("Duration");
                assertEquals("5", duration.getText());
                duration.setText("15");
                alert("will return to Pending Review", ButtonType.OK);
                alert("Listing saved!", ButtonType.OK);
                click("Save Changes");
                assertTrue(hasLabel(cardFor(item.getTitle()), "PENDING REVIEW"));
                assertFalse(shows("Rejection reason: " + reason));
            });

            Listing saved = listings.getListing(seller, item.getId());
            assertEquals(ListingStatus.PENDING_REVIEW, saved.getStatus());
            assertEquals(15, saved.getLendingDuration());
            assertEquals(LendingUnit.SECONDS, saved.getLendingUnit());
        }

        @Test
        void cancellingAnEditPreservesListingAndPendingOffer() throws Exception {
            Listing item = live(ListingType.SELL, "cancel-edit");
            var offer = propose(buyer, item, ListingType.SELL);
            runOnFx(() -> {
                dashboard();
                click("Edit");
                input("Price (HK$)").setText("36.00");
                alert("will return to Pending Review", ButtonType.CANCEL);
                click("Save Changes");
                assertNotNull(button("Save Changes"));
            });

            Listing saved = listings.getListing(seller, item.getId());
            assertEquals(item.getPrice(), saved.getPrice());
            assertEquals(ListingStatus.LIVE, saved.getStatus());
            assertEquals(DealStatus.PENDING, buys.getMyDeal(buyer, offer.getId()).getStatus());
            assertTrue(inbox(buyer).stream().noneMatch(n -> "Listing Changed".equals(n.getTitle())));
        }

        @Test
        void listingCardsScopeOwnershipAndDeleteRequiresConfirmation() throws Exception {
            Listing owned = live(ListingType.SELL, "owned");
            createListing(otherSeller, ListingType.GIVEAWAY, "foreign");
            var pending = propose(buyer, owned, ListingType.SELL);
            runOnFx(() -> {
                dashboard();
                assertTrue(shows(owned.getTitle()));
                assertFalse(shows(prefix + "foreign"));
                click("View Details");
                assertTrue(shows(prefix + "Listing description"));
                click("Back to My Listings");

                alert("Delete '" + owned.getTitle(), ButtonType.CANCEL);
                click("Delete");
                assertTrue(shows(owned.getTitle()));
                alert("Delete '" + owned.getTitle(), ButtonType.OK);
                alert("Listing removed.", ButtonType.OK);
                click("Delete");
                assertFalse(shows(owned.getTitle()));
                assertTrue(shows("You have not posted any listings yet."));
            });
            assertEquals(DealStatus.CANCELLED, buys.getMyDeal(buyer, pending.getId()).getStatus());
            assertNotice(buyer, "Listing Removed", owned.getTitle());
        }

        @Test
        void editPersistsAfterConfirmationAndResubmits() throws Exception {
            Listing owned = live(ListingType.SELL, "editable");
            runOnFx(() -> {
                dashboard();
                click("Edit");
                input("Price (HK$)").setText("36.00");
                alert("will return to Pending Review", ButtonType.OK);
                alert("Listing saved!", ButtonType.OK);
                click("Save Changes");
                assertTrue(shows("PENDING REVIEW"));
            });
            Listing saved = listings.getListing(seller, owned.getId());
            assertEquals(36.0, saved.getPrice());
            assertEquals(ListingStatus.PENDING_REVIEW, saved.getStatus());
        }
    }

    @Nested
    class Task25_IncomingOffersTests {
        @Test
        void incomingOfferOpensListingDetailsAndReturnsToListings() throws Exception {
            Listing item = live(ListingType.SELL, "offer-details");
            propose(buyer, item, ListingType.SELL);
            runOnFx(() -> {
                dashboard();
                click("Incoming Offers");
            });
            await(() -> shows(item.getTitle()));
            runOnFx(() -> {
                require(find(cardFor(item.getTitle()), Button.class, button -> "View Listing".equals(button.getText())),
                        "View Listing").fire();
                assertTrue(shows("Listing Details"));
                assertTrue(shows(item.getDescription()));
                click("Back to My Listings");
                assertTrue(shows("My Listings"));
                assertNotNull(cardFor(item.getTitle()));
                click("Incoming Offers");
            });
            await(() -> shows(item.getTitle()) && button("Accept") != null);
        }

        @Test
        void borrowCounterValidatesClockTimesAndMinimumPeriodBeforeSaving() throws Exception {
            Listing item = live(ListingType.LEND, "borrow-counter");
            var offer = propose(buyer, item, ListingType.LEND);
            var start = LocalDateTime.now().plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0);
            runOnFx(() -> {
                dashboard();
                click("Incoming Offers");
            });
            await(() -> shows(item.getTitle()));
            runOnFx(() -> {
                expectDialog("Counter Proposal", pane -> {
                    var dates = nodes(pane).filter(DatePicker.class::isInstance).map(DatePicker.class::cast).toList();
                    var times = nodes(pane).filter(TextField.class::isInstance).map(TextField.class::cast)
                            .filter(field -> "HH:mm:ss".equals(field.getPromptText())).toList();
                    assertEquals(2, dates.size());
                    assertEquals(2, times.size());
                    dates.forEach(date -> date.setValue(start.toLocalDate()));
                    times.get(0).setText("25:00:00");
                    press(pane, "Send Counter");
                    assertTrue(hasLabel(pane, "enter valid times as HH:mm:ss"));
                    times.get(0).setText("12:00:00");
                    times.get(1).setText("12:00:04");
                    press(pane, "Send Counter");
                    assertTrue(hasLabel(pane, "at least 5 seconds long."));
                    times.get(1).setText("12:00:05");
                    press(pane, "Send Counter");
                });
                click("Counter Offer");
            });
            await(() -> button("Revise Counter") != null);
            var saved = buys.getMyDeal(buyer, offer.getId());
            assertEquals(DealStatus.COUNTERED, saved.getStatus());
            assertEquals(start, saved.getCounterBorrowStart());
            assertEquals(start.plusSeconds(5), saved.getCounterBorrowEnd());
        }

        @Test
        void counterofferAndLinkedChatUseTheSellerDashboardActions() throws Exception {
            Listing item = live(ListingType.SELL, "offers");
            var offer = propose(buyer, item, ListingType.SELL);
            runOnFx(() -> {
                dashboard();
                click("Incoming Offers");
            });
            await(() -> shows(item.getTitle()));
            runOnFx(() -> {
                expectDialog("Counter Proposal", pane -> {
                    find(pane, TextField.class, field -> true).setText("38.00");
                    press(pane, "Send Counter");
                });
                click("Counter Offer");
            });
            await(() -> button("Revise Counter") != null);
            runOnFx(() -> {
                assertTrue(button("Accept").isDisabled());
                click("Open Chat");
            });
            await(() -> button("Send") != null && shows("Messages refresh automatically."));
            runOnFx(() -> {
                input("Write a private message (up to 4,000 characters)").setText(prefix + "campus pickup");
                assertFalse(button("Send").isDisabled());
                click("Send");
            });
            await(() -> shows(prefix + "campus pickup"));
            runOnFx(() -> {
                input("Search this conversation").setText("missing-match");
                click("Search");
            });
            await(() -> shows("No matching messages."));
            runOnFx(() -> {
                click("Back to Chats");
                click("My Listings");
            });
            assertEquals(38.0, buys.getMyDeal(buyer, offer.getId()).getCounterOfferPrice());
        }

        @Test
        void acceptsOrDeclinesOnlyAfterTheSellerConfirms() throws Exception {
            Listing gift = live(ListingType.GIVEAWAY, "accept-claim");
            Listing sale = live(ListingType.SELL, "decline-sale");
            var claim = propose(buyer, gift, ListingType.GIVEAWAY);
            var offer = propose(buyer, sale, ListingType.SELL);
            runOnFx(() -> {
                dashboard();
                click("Incoming Offers");
            });
            await(() -> shows(gift.getTitle()) && shows(sale.getTitle()));
            runOnFx(() -> {
                Node claimCard = cardFor(gift.getTitle());
                assertNull(find(claimCard, Button.class, button -> button.getText().equals("Counter Offer")));
                Button accept = find(claimCard, Button.class, button -> button.getText().equals("Accept"));
                alert("Accept proposal?", ButtonType.CANCEL);
                accept.fire();
            });
            assertEquals(DealStatus.PENDING, buys.getMyDeal(buyer, claim.getId()).getStatus());
            runOnFx(() -> {
                alert("Accept proposal?", ButtonType.OK);
                find(cardFor(gift.getTitle()), Button.class, button -> button.getText().equals("Accept")).fire();
            });
            await(() -> shows("Proposal accepted.") && !shows(gift.getTitle()));
            runOnFx(() -> {
                alert("Decline proposal?", ButtonType.OK);
                find(cardFor(sale.getTitle()), Button.class, button -> button.getText().equals("Decline")).fire();
            });
            await(() -> shows("Proposal declined.") && !shows(sale.getTitle()));
            assertEquals(DealStatus.ACCEPTED, buys.getMyDeal(buyer, claim.getId()).getStatus());
            assertEquals(DealStatus.DECLINED, buys.getMyDeal(buyer, offer.getId()).getStatus());
        }
    }

    @Nested
    class Task26_ManageProfileTests {
        @Test
        void weakPasswordFailsAndBlankPasswordPreservesExistingHash() throws Exception {
            String original = seller.getPasswordHash();
            String originalName = seller.getFullName();
            runOnFx(() -> {
                dashboard();
                click("My Profile");
                find(root(), TextField.class, f -> !(f instanceof PasswordField)).setText("New Seller Name");
                find(root(), TextArea.class, area -> true).setText(prefix + "Updated bio");
                ComboBox<?> meetup = find(root(), ComboBox.class, box -> box.getItems().contains("Library"));
                select(meetup, "Library");
                var password = input("New password (optional)");
                password.setText("weak");
                alert("at least 8", ButtonType.OK);
                click("Save Changes");
                assertEquals(originalName, SessionManager.getCurrentUser().getFullName());
                password.clear();
                alert("profile saved", ButtonType.OK);
                click("Save Changes");
                assertTrue(shows("Hello, New Seller Name"));
            });
            User saved = users.findByUsername(seller.getUsername());
            assertEquals("New Seller Name", saved.getFullName());
            assertEquals(prefix + "Updated bio", saved.getBio());
            assertEquals("Library", saved.getPreferredMeetup());
            assertEquals(original, saved.getPasswordHash());
        }
    }

    @Nested
    class Task27_NotificationBoardTests {
        @Test
        void buyerProposalCompletionAndReviewRenderAndUpdateUnreadBadge() throws Exception {
            var review = reviewedSale("notification-events");
            runOnFx(() -> {
                dashboard();
                click("Notifications");
            });
            await(() -> badgeIs("3") && shows("New Buyer Proposal") && shows("Deal Completed")
                    && shows("New Seller Review") && shows(review.getListingTitle()));
            runOnFx(() -> click("Mark as read"));
            await(() -> badgeIs("2"));
            runOnFx(() -> click("Mark all as read"));
            await(() -> badgeIs(null) && shows("No unread notifications."));
        }

        @Test
        void dashboardPollsForNewNotificationsAndCapsTheBadgeAt99() throws Exception {
            notificationRows.create(new Notification(seller.getUsername(), "Initial notice", prefix + "Initial", "Listing"));
            runOnFx(SellerFrontendControllerTest.this::dashboard);
            await(() -> badgeIs("1"));

            for (int i = 0; i < 99; i++) {
                notificationRows.create(new Notification(seller.getUsername(), "New notice", prefix + i, "Listing"));
            }

            await(() -> badgeIs("99+"), 22);
            runOnFx(() -> assertTrue(shows("You have not posted any listings yet.")));
        }

        @Test
        void approvalAndRejectionAppearAndSidebarCountUpdatesOnReadActions() throws Exception {
            Listing approved = createListing(seller, ListingType.SELL, "approved");
            Listing rejected = createListing(seller, ListingType.GIVEAWAY, "rejected");
            runOnFx(() -> {
                AdminDashboardController moderation = new AdminDashboardController();
                invokeModeration(moderation, "approve_listing", new Class<?>[]{Listing.class}, approved);
                invokeModeration(moderation, "reject_listing", new Class<?>[]{Listing.class, String.class},
                        rejected, prefix + "Add more detail");
                dashboard();
                click("Notifications");
            });
            await(() -> shows("Listing Approved") && shows("Listing Rejected") && badgeIs("2"));
            runOnFx(() -> {
                assertTrue(shows(prefix + "Add more detail"));
                click("Mark as read");
            });
            await(() -> badgeIs("1"));
            runOnFx(() -> click("Mark all as read"));
            await(() -> badgeIs(null) && shows("No unread notifications."));
            runOnFx(() -> find(root(), CheckBox.class, box -> box.getText().equals("Show read notifications")).fire());
            await(() -> shows("Listing Approved") && shows("Listing Rejected"));
            assertTrue(inbox(seller).stream().allMatch(n -> n.isRead() && n.getTimestamp() != null
                    && n.getCategory().equals("Listing")));
        }
    }

    @Nested
    class Task28_SellerStatsTests {
        @Test
        void populatedMetricsDisplayViewsCompletedDealsAndFormattedRating() throws Exception {
            Listing item = live(ListingType.SELL, "rated-stats");
            browse.getListing(buyer, item.getId());
            browse.getListing(otherBuyer, item.getId());
            var offer = propose(buyer, item, ListingType.SELL);
            complete(offer);
            reviews.submitReview(buyer, offer.getId(), 5, prefix + "Five stars");
            reviewedSale("also-rated");

            runOnFx(() -> {
                dashboard();
                click("Seller Stats");
            });
            await(() -> shows("Average Rating"));
            runOnFx(() -> {
                assertTrue(hasLabel(cardFor("Listing Views"), "2"));
                assertTrue(hasLabel(cardFor("Offers Received"), "2"));
                assertTrue(hasLabel(cardFor("Completed Deals"), "2"));
                assertTrue(hasLabel(cardFor("Average Rating"), "4.50 / 5"));
            });
        }

        @Test
        void chartSeriesUsePersistedCategoryAndStatusCounts() throws Exception {
            live(ListingType.SELL, "active");
            createListing(seller, ListingType.GIVEAWAY, "pending");
            runOnFx(() -> {
                dashboard();
                click("Seller Stats");
            });
            await(() -> find(root(), PieChart.class, chart -> true) != null);
            runOnFx(() -> {
                var bar = find(root(), BarChart.class, chart -> true);
                assertEquals("Listings by Category", bar.getTitle());
                var series = (XYChart.Series<?, ?>) bar.getData().getFirst();
                assertEquals(1, series.getData().size());
                assertEquals("Textbooks", series.getData().getFirst().getXValue());
                assertEquals(2, ((Number) series.getData().getFirst().getYValue()).intValue());
                PieChart pie = find(root(), PieChart.class, chart -> true);
                assertEquals(2, pie.getData().size());
                assertTrue(pie.getData().stream().anyMatch(slice -> slice.getName().equals("LIVE") && slice.getPieValue() == 1));
                assertTrue(pie.getData().stream().anyMatch(slice -> slice.getName().equals("PENDING REVIEW") && slice.getPieValue() == 1));
                assertTrue(shows("No ratings"));
            });
        }
    }

    @Nested
    class Task29_ReviewAndFeedbackTests {
        @Test
        void reviewPaneShowsEmptyStateAndOmitsHiddenReviews() throws Exception {
            runOnFx(() -> {
                dashboard();
                click("Reviews & Feedback");
            });
            await(() -> shows("No reviews yet."));

            var hidden = reviewedSale("hidden");
            var visible = reviewedSale("visible");
            reviews.setHidden(admin, hidden.getId(), true);
            runOnFx(() -> click("Refresh"));
            await(() -> shows(visible.getComment()));
            runOnFx(() -> {
                assertFalse(shows(hidden.getComment()));
                assertFalse(shows("No reviews yet."));
            });
        }

        @Test
        void savesReplyAndFlagAndDisablesActionsAlreadyTaken() throws Exception {
            var review = reviewedSale("feedback");
            runOnFx(() -> {
                dashboard();
                click("Reviews & Feedback");
            });
            await(() -> button("Reply") != null);
            runOnFx(() -> {
                expectDialog("Reply to Review", pane -> {
                    press(pane, "Send Reply");
                    assertNotNull(find(pane, Label.class, label -> label.getText().startsWith("Enter a reply")));
                    find(pane, TextArea.class, area -> true).setText(prefix + "Thanks");
                    press(pane, "Send Reply");
                });
                click("Reply");
            });
            await(() -> shows(prefix + "Thanks") && button("Reply").isDisabled());
            runOnFx(() -> {
                alert("Flag this review", ButtonType.CANCEL);
                click("Flag");
                assertFalse(button("Flag").isDisabled());
            });
            runOnFx(() -> {
                alert("Flag this review", ButtonType.OK);
                click("Flag");
            });
            await(() -> button("Flagged") != null && button("Flagged").isDisabled());
            assertNotice(buyer, "Seller Replied to Your Review", review.getListingTitle());
            assertNotice(admin, "Seller Flagged a Review", review.getListingTitle());
        }
    }

    @Nested
    class SellerRbacAndEdgeCaseTests {
        @Test
        void reservedListingDisablesEditsAndDeletionAndEmptyOffersShowFeedback() throws Exception {
            Listing item = live(ListingType.SELL, "reserved");
            var offer = propose(buyer, item, ListingType.SELL);
            offers.acceptOffer(seller, offer.getId(), offer.getRowVersion());
            runOnFx(() -> {
                dashboard();
                assertTrue(shows("RESERVED"));
                assertTrue(button("Edit").isDisabled());
                assertTrue(button("Delete").isDisabled());
                click("Incoming Offers");
            });
            await(() -> shows("No pending offers, borrowing requests or claims."));
        }
    }

    private void dashboard() {
        SessionManager.login(seller);
        load("SellerDashboardView.fxml");
    }

    private void load(String file) {
        load(file, null);
    }

    private void load(String file, SellerDashboardController controller) {
        try {
            var view = SellerFrontendControllerTest.class.getResource("/fxml/seller/" + file);
            assertNotNull(view);
            FXMLLoader loader = new FXMLLoader(view);
            if (controller != null) loader.setControllerFactory(type -> controller);
            stage.setScene(new Scene(loader.load(), 1180, 760));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to load " + file, failure);
        }
    }

    private Parent root() {
        return stage.getScene().getRoot();
    }

    private TextField field(String id) {
        return (TextField) root().lookup("#" + id);
    }

    private Label label(String id) {
        return (Label) root().lookup("#" + id);
    }

    private TextInputControl input(String prompt) {
        return require(find(root(), TextInputControl.class, control -> prompt.equals(control.getPromptText())), prompt);
    }

    private ComboBox<?> combo(String prompt) {
        return require(find(root(), ComboBox.class, box -> prompt.equals(box.getPromptText())), prompt);
    }

    private static void select(ComboBox<?> box, String text) {
        for (int i = 0; i < box.getItems().size(); i++) {
            Object item = box.getItems().get(i);
            if (text.equals(String.valueOf(item)) || item instanceof Enum<?> choice && text.equals(choice.name())) {
                box.getSelectionModel().select(i);
                return;
            }
        }
        fail("Missing combo choice: " + text);
    }

    private Button button(String text) {
        return find(root(), Button.class, b -> b.getText() != null && b.getText().strip().equals(text));
    }

    private Node cardFor(String title) {
        return require(nodes(root()).filter(node -> node.getStyleClass().contains("card"))
                .filter(node -> find(node, Label.class, label -> title.equals(label.getText())) != null)
                .findFirst().orElse(null), title);
    }

    private void click(String text) {
        require(button(text), text).fire();
    }

    private void clickLink(String text) {
        require(find(root(), Hyperlink.class, link -> link.getText().endsWith(text)), text).fire();
    }

    private boolean shows(String text) {
        return hasLabel(root(), text);
    }

    private static boolean hasLabel(Node root, String text) {
        return find(root, Label.class, label -> label.isVisible() && label.getText() != null
                && label.getText().contains(text)) != null;
    }

    private static File writePng(Path path, int rgb) throws java.io.IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
        return path.toFile();
    }

    private boolean badgeIs(String value) {
        Node badge = require(button("Notifications"), "Notifications").getGraphic();
        return value == null ? badge == null : badge instanceof Label label && value.equals(label.getText());
    }

    private static <T> T require(T node, String description) {
        assertNotNull(node, () -> "Missing UI control: " + description);
        return node;
    }

    private static <T extends Node> T find(Node root, Class<T> type, java.util.function.Predicate<T> match) {
        return nodes(root).filter(type::isInstance).map(type::cast).filter(match).findFirst().orElse(null);
    }

    private static Stream<Node> nodes(Node root) {
        if (root == null) return Stream.empty();
        Stream<Node> children = root instanceof ScrollPane scroll ? nodes(scroll.getContent())
                : root instanceof Parent parent ? parent.getChildrenUnmodifiable().stream()
                        .flatMap(SellerFrontendControllerTest::nodes) : Stream.empty();
        return Stream.concat(Stream.of(root), children);
    }

    private void alert(String text, ButtonType choice) {
        expectDialog(text, pane -> ((Button) require(pane.lookupButton(choice), choice.getText())).fire());
    }

    private void expectDialog(String text, Consumer<DialogPane> action) {
        dialogs.addLast(new DialogRule(text, action));
    }

    private static void press(DialogPane pane, String text) {
        ButtonType type = pane.getButtonTypes().stream().filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
        ((Button) pane.lookupButton(type)).fire();
    }

    // Schedule the action outside the animation pulse so modal handlers may enter a nested event loop.
    private void watchDialogs() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window == stage || window == handling || !window.isShowing()
                    || window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) continue;
            String title = window instanceof Stage dialogStage ? dialogStage.getTitle() : "";
            String text = title + " " + pane.getHeaderText() + " " + pane.getContentText();
            DialogRule rule = dialogs.peekFirst();
            if (rule != null && text.contains(rule.text())) dialogs.removeFirst();
            handling = window;
            Platform.runLater(() -> {
                try {
                    if (rule == null || !text.contains(rule.text())) {
                        dialogErrors.add(text);
                        window.hide();
                    } else {
                        rule.action().accept(pane);
                    }
                } catch (AssertionError | RuntimeException failure) {
                    dialogErrors.add(failure.toString());
                    window.hide();
                } finally {
                    handling = null;
                }
            });
            return;
        }
    }

    private static void invokeModeration(AdminDashboardController controller, String name, Class<?>[] types, Object... args) {
        try {
            Method method = AdminDashboardController.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(controller, args);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to create the seller's moderation notification", failure);
        }
    }

    private static void runOnFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof AssertionError assertion) throw assertion;
            throw failure;
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, 12);
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            FutureTask<Boolean> check = new FutureTask<>(condition::getAsBoolean);
            Platform.runLater(check);
            if (check.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(75);
        }
        fail("Seller UI did not reach the expected state");
    }
}
