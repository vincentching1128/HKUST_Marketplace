package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.controllers.NotificationBoard;
import com.hkust.marketplace.controllers.UnreadNotificationBadge;
import com.hkust.marketplace.models.ChatThread;
import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.controllers.ChatInboxPane;
import com.hkust.marketplace.controllers.ChatPane;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.NotificationService;
import com.hkust.marketplace.services.BuyerListingService;
import com.hkust.marketplace.services.BuyerDealService;
import com.hkust.marketplace.services.ChatService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class BuyerDashboardController {

    private final UserRepository userRepository = new UserRepository();
    private final AuthService authService = new AuthService(userRepository);
    private final NotificationRepository notificationRepository = new NotificationRepository();

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnBrowse, btnDeals, btnChats, btnHistory, btnNotifications, btnProfile;

    private Button currentActiveBtn;
    private NotificationBoard notificationBoard;
    private UnreadNotificationBadge unreadNotificationBadge;
    private BuyerListingsPane listingsPane;
    private BuyerDealsPane dealsPane;
    private ChatInboxPane chatInbox;
    private ChatPane chatPane;
    private final BuyerDealService buyerDeals = new BuyerDealService();
    private final BuyerListingService buyerListings = new BuyerListingService();
    private final ChatService chatService = new ChatService();
    private long navigationRequest;

    @FXML
    public void initialize() {
        unreadNotificationBadge = new UnreadNotificationBadge(btnNotifications,
                new NotificationService(userRepository, notificationRepository, UserRole.BUYER));
        User user = SessionManager.getCurrentUser();
        if (user != null) {
            welcomeLabel.setText("Hello, " + user.getFullName());
        }
        currentActiveBtn = btnBrowse;
        showBrowse();
    }

    private void setActive(Button btn, String title) {
        if (notificationBoard != null && btn != btnNotifications) {
            notificationBoard.deactivate();
        }
        navigationRequest++;
        if (dealsPane != null) dealsPane.deactivate();
        if (chatInbox != null) chatInbox.deactivate();
        if (chatPane != null) chatPane.deactivate();
        if (currentActiveBtn != null) {
            currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        }
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showBrowse() {
        setActive(btnBrowse, "Browse Marketplace");
        if (listingsPane == null) {
            listingsPane = new BuyerListingsPane(buyerListings,
                    buyerDeals, chatService, SessionManager::getCurrentUser,
                    pageTitle::setText, this::openChatForListing);
        }
        contentPane.getChildren().setAll(listingsPane);
        listingsPane.showBrowse();
    }

    @FXML
    private void showMyDeals() {
        setActive(btnDeals, "My Deals");
        dealsPane = new BuyerDealsPane(buyerDeals, SessionManager::getCurrentUser,
                this::showDealListing, this::openChatForDeal, this::openHistory);
        contentPane.getChildren().setAll(dealsPane);
        dealsPane.refresh();
    }

    private void showDealListing(String listingId) {
        setActive(btnBrowse, "Listing Details");
        if (listingsPane == null) {
            listingsPane = new BuyerListingsPane(buyerListings, buyerDeals, chatService,
                    SessionManager::getCurrentUser, pageTitle::setText, this::openChatForListing);
        }
        contentPane.getChildren().setAll(listingsPane);
        listingsPane.showListingDetails(listingId, this::showMyDeals);
    }

    @FXML
    private void showChats() {
        setActive(btnChats, "Chats");
        chatInbox = new ChatInboxPane(chatService, SessionManager::getCurrentUser,
                thread -> showChatThread(thread.id(), thread.listingId()));
        contentPane.getChildren().setAll(chatInbox);
        chatInbox.refresh();
    }

    private void showChatThread(String threadId, String listingId) {
        setActive(btnChats, "Private Chat");
        chatPane = new ChatPane(chatService, SessionManager::getCurrentUser, threadId,
                this::showChats, listingId == null ? null : () -> proposeInChat(threadId, listingId));
        contentPane.getChildren().setAll(chatPane);
        chatPane.refresh();
    }

    private void proposeInChat(String threadId, String listingId) {
        ChatPane pane = chatPane;
        User buyer = SessionManager.getCurrentUser();
        long token = navigationRequest;
        pane.setDisable(true);
        runChatProposalTask(pane, buyer, token, () -> {
            var listing = buyerListings.getListing(buyer, listingId);
            if (listing.getStatus() != ListingStatus.LIVE) {
                throw new IllegalArgumentException("This item is currently unavailable for new proposals.");
            }
            return listing;
        }, listing -> {
            var terms = BuyerListingsPane.proposalDialog(listing);
            if (terms == null || !currentNavigation(token, buyer)) return;
            pane.setDisable(true);
            runChatProposalTask(pane, buyer, token,
                    () -> BuyerListingsPane.saveProposal(buyerDeals, chatService, buyer, listing, terms, threadId),
                    shared -> {
                        pane.refresh();
                        BuyerListingsPane.showSubmissionConfirmation(shared);
                    }, "Unable to submit. Check My Deals before retrying.");
        }, "Unable to load the listing. Please try again.");
    }

    private <T> void runChatProposalTask(ChatPane pane, User buyer, long token, Callable<T> query,
                                         Consumer<T> success, String fallbackError) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return query.call(); }
        };
        task.setOnSucceeded(event -> {
            pane.setDisable(false);
            if (currentNavigation(token, buyer)) success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            pane.setDisable(false);
            if (!currentNavigation(token, buyer)) return;
            Throwable failure = task.getException();
            new Alert(Alert.AlertType.ERROR, failure instanceof IllegalArgumentException
                    ? failure.getMessage() : fallbackError).showAndWait();
        });
        Thread worker = new Thread(task, "buyer-chat-proposal");
        worker.setDaemon(true);
        worker.start();
    }

    private void openChatForListing(String listingId) {
        openChat(() -> chatService.openForListing(SessionManager.getCurrentUser(), listingId), listingId);
    }

    private void openChatForDeal(String dealId) {
        openChat(() -> {
            User buyer = SessionManager.getCurrentUser();
            ChatThread thread = chatService.openForDeal(buyer, dealId);
            Deal deal = buyerDeals.getMyDeal(buyer, dealId);
            if (deal.getStatus() == DealStatus.PENDING) {
                try { chatService.sendProposal(buyer, thread.id(), dealId); }
                catch (SQLException | IllegalArgumentException failure) {
                    System.err.println("Formal proposal could not be shared in chat: " + failure.getMessage());
                }
            }
            return thread;
        }, null);
    }

    private void openChat(Callable<ChatThread> query, String fallbackListingId) {
        setActive(btnChats, "Private Chat");
        long token = navigationRequest;
        User actor = SessionManager.getCurrentUser();
        contentPane.getChildren().setAll(new VBox(12, new Label("Opening conversation..."), new ProgressIndicator()));
        Task<ChatThread> task = new Task<>() {
            @Override protected ChatThread call() throws Exception { return query.call(); }
        };
        task.setOnSucceeded(event -> {
            if (currentNavigation(token, actor))
                showChatThread(task.getValue().id(), task.getValue().listingId() == null
                        ? fallbackListingId : task.getValue().listingId());
        });
        task.setOnFailed(event -> {
            if (!currentNavigation(token, actor)) return;
            Label error = new Label(task.getException() instanceof IllegalArgumentException
                    ? task.getException().getMessage() : "Unable to open this conversation.");
            error.setWrapText(true);
            Button back = new Button("Back to Chats");
            back.setOnAction(click -> showChats());
            contentPane.getChildren().setAll(new VBox(12, error, back));
        });
        Thread worker = new Thread(task, "buyer-open-chat");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean currentNavigation(long token, User actor) {
        User current = SessionManager.getCurrentUser();
        return token == navigationRequest && actor != null && current != null
                && Objects.equals(actor.getUsername(), current.getUsername());
    }

    @FXML
    private void showHistory() {
        openHistory(null);
    }

    private void openHistory(String reviewDealId) {
        setActive(btnHistory, "Deal History");
        contentPane.getChildren().setAll(new BuyerHistoryPane(SessionManager.getCurrentUser(), buyerDeals, reviewDealId));
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notifications");
        User recipient = SessionManager.getCurrentUser();
        if (recipient == null) {
            SceneManager.showBuyerLogin();
            return;
        }
        if (notificationBoard == null) {
            notificationBoard = new NotificationBoard(
                    new NotificationService(userRepository, notificationRepository, UserRole.BUYER),
                    unreadNotificationBadge::refresh);
        }
        contentPane.getChildren().setAll(notificationBoard);
        notificationBoard.show(recipient);
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "Manage Profile");
        contentPane.getChildren().clear();

        User sessionUser = SessionManager.getCurrentUser();
        if (sessionUser == null) {
            SceneManager.showBuyerLogin();
            return;
        }
        User user;
        try {
            user = userRepository.findByUsername(sessionUser.getUsername());
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, "Unable to load your profile. Please try again.").showAndWait();
            return;
        }
        if (user == null) {
            unreadNotificationBadge.stop();
            SessionManager.logout();
            SceneManager.showBuyerLogin();
            return;
        }
        VBox form = new VBox(14);
        form.setPadding(new Insets(8));
        form.setMaxWidth(420);
        form.getStyleClass().add("card");

        Label title = new Label("Edit your profile");
        title.getStyleClass().add("section-title");

        TextField nameField = new TextField(user.getFullName());
        nameField.setPromptText("Full Name");
        PasswordField newPass = new PasswordField();
        newPass.setPromptText("New password (leave blank to keep)");
        PasswordField confirmPass = new PasswordField();
        confirmPass.setPromptText("Confirm new password");
        ComboBox<String> residence = new ComboBox<>(FXCollections.observableArrayList(
                "Hall 1", "Hall 2", "Hall 3", "Hall 4", "Hall 5",
                "Hall 6", "Hall 7", "Hall 8", "Hall 9", "UG Hall 1", "UG Hall 2", "Off-campus"));
        if (user.getResidenceHall() != null) {
            residence.setValue(user.getResidenceHall());
        }

        Button saveBtn = new Button("Save Changes");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {
            String fullName = nameField.getText().trim();
            if (fullName.isEmpty() || fullName.codePointCount(0, fullName.length()) > 100) {
                new Alert(Alert.AlertType.WARNING, "Full Name must contain 1 to 100 characters.").showAndWait();
                return;
            }
            String password = newPass.getText();
            if (!password.equals(confirmPass.getText())) {
                new Alert(Alert.AlertType.WARNING, "Passwords do not match.").showAndWait();
                return;
            }
            String selectedResidence = residence.getValue();
            if (selectedResidence != null && selectedResidence.codePointCount(0, selectedResidence.length()) > 100) {
                new Alert(Alert.AlertType.WARNING, "Residence must be at most 100 characters.").showAndWait();
                return;
            }
            User profileUpdate = new User();
            profileUpdate.setUsername(user.getUsername());
            profileUpdate.setFullName(fullName);
            profileUpdate.setResidenceHall(selectedResidence);
            profileUpdate.setBio(user.getBio());
            profileUpdate.setPreferredMeetup(user.getPreferredMeetup());
            try {
                if (!authService.updateProfile(profileUpdate, password)) {
                    new Alert(Alert.AlertType.ERROR, "Profile not found.").showAndWait();
                    return;
                }
                sessionUser.setFullName(fullName);
                sessionUser.setResidenceHall(selectedResidence);
                if (profileUpdate.getPasswordHash() != null) {
                    sessionUser.setPasswordHash(profileUpdate.getPasswordHash());
                }
                newPass.clear();
                confirmPass.clear();
                welcomeLabel.setText("Hello, " + fullName);
                notifyAccountChange("Buyer Profile Updated",
                        "@" + sessionUser.getUsername() + " updated their buyer profile.");
                new Alert(Alert.AlertType.INFORMATION, "Profile updated successfully.").showAndWait();
            } catch (IllegalArgumentException invalidPassword) {
                new Alert(Alert.AlertType.WARNING, invalidPassword.getMessage()).showAndWait();
            } catch (SQLException databaseError) {
                new Alert(Alert.AlertType.ERROR, "Unable to save your profile. Please try again.").showAndWait();
            }
        });

        form.getChildren().addAll(title,
                new Label("Full Name"), nameField,
                new Label("New Password"), newPass,
                new Label("Confirm Password"), confirmPass,
                new Label("Residence"), residence,
                saveBtn);

        contentPane.getChildren().add(form);
    }

    /** Best-effort admin audit notification for account changes. */
    private void notifyAccountChange(String title, String message) {
        try {
            notificationRepository.notifyAllAdmins("Account", title, message);
        } catch (SQLException | IllegalArgumentException ex) {
            System.err.println("Failed to create account-change notification: " + ex.getMessage());
        }
    }

    @FXML
    private void logout() {
        unreadNotificationBadge.stop();
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
