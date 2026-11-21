package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.controllers.NotificationBoard;
import com.hkust.marketplace.models.ChatThread;
import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealStatus;
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
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Callable;

public class BuyerDashboardController {

    private final UserRepository userRepository = new UserRepository();
    private final AuthService authService = new AuthService(userRepository);
    private final NotificationRepository notificationRepository = new NotificationRepository();

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnBrowse, btnDeals, btnChats, btnHistory, btnFavourites, btnNotifications, btnProfile;

    private Button currentActiveBtn;
    private NotificationBoard notificationBoard;
    private BuyerListingsPane listingsPane;
    private BuyerDealsPane dealsPane;
    private ChatInboxPane chatInbox;
    private ChatPane chatPane;
    private final BuyerDealService buyerDeals = new BuyerDealService();
    private final ChatService chatService = new ChatService();
    private long navigationRequest;

    @FXML
    public void initialize() {
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
            listingsPane = new BuyerListingsPane(new BuyerListingService(),
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
            listingsPane = new BuyerListingsPane(new BuyerListingService(), buyerDeals, chatService,
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
                this::showChats, listingId == null ? null : () -> showDealListing(listingId));
        contentPane.getChildren().setAll(chatPane);
        chatPane.refresh();
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
    private void showFavourites() {
        setActive(btnFavourites, "Favourites & Following");
        contentPane.getChildren().clear();
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));
        root.setAlignment(Pos.CENTER);
        Label icon = new Label("♥");
        icon.setStyle("-fx-font-size: 48px; -fx-text-fill: #A68137;");
        Label title = new Label("Favourites, Bookmarks & Following");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #003974;");
        Label desc = new Label("Save listings you like and follow favourite sellers.\nThis section is a UI placeholder for students to implement.");
        desc.setStyle("-fx-text-fill: #5a5a7a; -fx-text-alignment: center;");
        desc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        root.getChildren().addAll(icon, title, desc);
        contentPane.getChildren().add(root);
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
                    new NotificationService(userRepository, notificationRepository, UserRole.BUYER));
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
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
