package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AdminDashboardController {

    private static final Logger LOGGER = Logger.getLogger(AdminDashboardController.class.getName());

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnReview, btnAllListings, btnUsers, btnDeals, btnRatings, btnNotifications, btnProfile;

    private Button currentActiveBtn;
    private final UserRepository userRepository = new UserRepository();

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Admin: " + user.getFullName());
        currentActiveBtn = btnReview;
        showReview();
    }

    private void setActive(Button btn, String title) {
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showReview() {
        setActive(btnReview, "Listing Review & Approval");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        List<Listing> pending = DummyDataService.getInstance().getPendingListings();
        if (pending.isEmpty()) {
            Label empty = new Label("No listings awaiting review.");
            empty.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 15px;");
            root.getChildren().add(empty);
        } else {
            for (Listing l : pending) {
                root.getChildren().add(createPendingCard(l));
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private VBox createPendingCard(Listing l) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        Label title = new Label(l.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label meta = new Label(String.format("Seller: %s (@%s)  ·  %s  ·  %s  ·  %s",
                l.getSellerFullName(), l.getSellerUsername(),
                l.getCategory(), l.getTypeDisplay(), l.getMeetupLocation()));
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        Label submitted = new Label("Submitted: " +
                l.getPostedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));
        submitted.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");

        HBox actions = new HBox(10);
        Button approve = new Button("Approve");
        approve.getStyleClass().addAll("btn-success", "btn-sm");
        approve.setOnAction(e -> {
            Alert conf = new Alert(Alert.AlertType.CONFIRMATION, "Approve this listing?");
            conf.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    l.setStatus(ListingStatus.LIVE);
                    l.setReviewedAt(LocalDateTime.now());
                    DummyDataService.getInstance().updateListing(l);
                    DummyDataService.getInstance().addNotification(new Notification(
                            l.getSellerUsername(), "Listing Approved",
                            "Your listing '" + l.getTitle() + "' is now live!", "System"));
                    showReview();
                }
            });
        });

        Button reject = new Button("Reject");
        reject.getStyleClass().addAll("btn-danger", "btn-sm");
        reject.setOnAction(e -> {
            TextInputDialog reasonDialog = new TextInputDialog();
            reasonDialog.setTitle("Rejection Reason");
            reasonDialog.setHeaderText("Please provide a reason for rejection");
            reasonDialog.setContentText("Reason:");
            reasonDialog.showAndWait().ifPresent(reason -> {
                if (reason.isBlank()) {
                    new Alert(Alert.AlertType.WARNING, "Reason is required.").showAndWait();
                    return;
                }
                l.setStatus(ListingStatus.REJECTED);
                l.setRejectionReason(reason);
                l.setReviewedAt(LocalDateTime.now());
                DummyDataService.getInstance().updateListing(l);
                DummyDataService.getInstance().addNotification(new Notification(
                        l.getSellerUsername(), "Listing Rejected",
                        "Your listing '" + l.getTitle() + "' was rejected. Reason: " + reason, "System"));
                showReview();
            });
        });

        actions.getChildren().addAll(approve, reject);
        card.getChildren().addAll(title, meta, submitted, actions);
        return card;
    }

    @FXML
    private void showAllListings() {
        setActive(btnAllListings, "Manage All Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        Button addBtn = new Button("+ Add Listing (on behalf of seller)");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Admin can create a listing on behalf of a seller here.\nSame form fields as Seller Create Listing.\n(UI placeholder)").showAndWait());
        root.getChildren().add(addBtn);

        for (Listing l : DummyDataService.getInstance().getAllListings()) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(3);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label t = new Label(l.getTitle());
            t.setStyle("-fx-font-weight: bold;");
            Label m = new Label(l.getSellerFullName() + " · " + l.getTypeDisplay() + " · " + l.getStatus());
            m.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
            info.getChildren().addAll(t, m);

            Button edit = new Button("Edit");
            edit.getStyleClass().addAll("btn-outline", "btn-sm");
            edit.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Edit listing dialog (demo).").showAndWait());

            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("btn-danger", "btn-sm");
            remove.setOnAction(e -> {
                Alert conf = new Alert(Alert.AlertType.CONFIRMATION, "Permanently remove this listing?");
                conf.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        DummyDataService.getInstance().removeListing(l.getId());
                        showAllListings();
                    }
                });
            });

            row.getChildren().addAll(info, edit, remove);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showUsers() {
        setActive(btnUsers, "Manage All Users");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        // Admin now manages the real MySQL users list (empty + error label on failure)
        List<User> allUsers = List.of();
        try {
            allUsers = userRepository.findAll();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to load users from the database.", ex);
            Label dbError = new Label("Unable to load users. Please try again.");
            dbError.setStyle("-fx-text-fill: #c62828; -fx-font-size: 13px;");
            dbError.setWrapText(true);
            root.getChildren().add(dbError);
        }

        for (User u : allUsers) {
            if (u.getRole() == UserRole.ADMIN) continue;

            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(3);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label name = new Label(u.getFullName() + " (@" + u.getUsername() + ")");
            name.setStyle("-fx-font-weight: bold;");
            Label role = new Label(u.getRole().name() + (u.isActive() ? " · Active" : " · Deactivated"));
            role.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
            info.getChildren().addAll(name, role);

            Button edit = new Button("Edit");
            edit.getStyleClass().addAll("btn-outline", "btn-sm");
            edit.setOnAction(e -> openEditUserDialog(u.getUsername()));
            //Alert(Alert.AlertType.INFORMATION, "Edit user dialog (demo).").showAndWait());

            Button toggle = new Button(u.isActive() ? "Deactivate" : "Activate");
            toggle.getStyleClass().addAll(u.isActive() ? "btn-danger" : "btn-success", "btn-sm");
            toggle.setOnAction(e -> toggleUserActive(u));

            Button delete = new Button("Delete");
            delete.getStyleClass().addAll("btn-danger", "btn-sm");
            delete.setOnAction(e -> {
                Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                        "Permanently delete user @" + u.getUsername() + "?");
                conf.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        deactivateUser(u);
                    }
                });
            });

            row.getChildren().addAll(info, edit, toggle, delete);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    /** Persist the activate/deactivate toggle to MySQL, then reload the list from the database. */
    private void toggleUserActive(User u) {
        boolean newState = !u.isActive();
        try {
            userRepository.setActive(u.getUsername(), newState);
            System.out.println("Toggled @" + u.getUsername() + " -> active=" + newState);
            showUsers(); //re-read from DB so the badge reflects the stored value
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to update account status.", ex);
            new Alert(Alert.AlertType.ERROR,
                    "Unable to update the account status. Please try again.").showAndWait();
        } catch (IllegalArgumentException ex) {
            new Alert(Alert.AlertType.ERROR, "User not found in database: " + u.getUsername()).showAndWait();
            showUsers(); //refresh in case the row was removed externally
        }
    }

    /** "Delete" is a safe deactivation in this demo, now persisted to MySQL. */
    private void deactivateUser(User u) {
        try {
            userRepository.setActive(u.getUsername(), false);
            new Alert(Alert.AlertType.INFORMATION, "User deactivated (demo safety).").showAndWait();
            showUsers();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to deactivate account.", ex);
            new Alert(Alert.AlertType.ERROR,
                    "Unable to update the account status. Please try again.").showAndWait();
        } catch (IllegalArgumentException ex) {
            new Alert(Alert.AlertType.ERROR, "User not found in database: " + u.getUsername()).showAndWait();
            showUsers();
        }
    }
    private void openEditUserDialog(String username) {
        // 1. Load the target account fresh from the database (the row in the list may be stale).
        User target;
        try {
            target = userRepository.findByUsername(username);
        } catch (SQLException ex) {
            new Alert(Alert.AlertType.ERROR,
                    "Unable to load user @" + username + ". Please try again.").showAndWait();
            return;
        }
        if (target == null) {
            new Alert(Alert.AlertType.WARNING, "User @" + username + " no longer exists.").showAndWait();
            showUsers();
            return;
        }

        // 2. Build the edit form (profile fields only; username/role are read-only).
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(SceneManager.getPrimaryStage());
        dialog.setTitle("Edit User");
        dialog.setHeaderText("Editing @" + target.getUsername() + " (" + target.getRole().name() + ")");
        ButtonType saveType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(440);

        VBox form = new VBox(8);
        form.setPadding(new Insets(10));

        Label accountLabel = new Label("@" + target.getUsername() + " · " + target.getRole().name());
        accountLabel.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        TextField fullName = new TextField(target.getFullName() != null ? target.getFullName() : "");
        fullName.setPromptText("Full Name *");
        fullName.setMaxWidth(Double.MAX_VALUE);

        final ComboBox<String> residenceCombo;
        final TextArea bioArea;
        final ComboBox<String> meetupCombo;
        if (target.getRole() == UserRole.BUYER) {
            residenceCombo = new ComboBox<>(FXCollections.observableArrayList(
                    "Hall 1", "Hall 2", "Hall 3", "Hall 4", "Hall 5",
                    "Hall 6", "Hall 7", "Hall 8", "Hall 9",
                    "UG Hall 1", "UG Hall 2", "Off-campus", "None"));
            residenceCombo.setMaxWidth(Double.MAX_VALUE);
            residenceCombo.setValue(target.getResidenceHall() != null ? target.getResidenceHall() : "None");
            bioArea = null;
            meetupCombo = null;
            form.getChildren().addAll(accountLabel, new Label("Full Name *"), fullName,
                    new Label("Residence Hall"), residenceCombo);
        } else if (target.getRole() == UserRole.SELLER) {
            bioArea = new TextArea(target.getBio() != null ? target.getBio() : "");
            bioArea.setPrefRowCount(3);
            bioArea.setWrapText(true);
            meetupCombo = new ComboBox<>(FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
            meetupCombo.getItems().add(0, "None");
            meetupCombo.setMaxWidth(Double.MAX_VALUE);
            meetupCombo.setValue(target.getPreferredMeetup() != null ? target.getPreferredMeetup() : "None");
            residenceCombo = null;
            form.getChildren().addAll(accountLabel, new Label("Full Name *"), fullName,
                    new Label("Bio"), bioArea,
                    new Label("Preferred Meet-up"), meetupCombo);
        } else {
            residenceCombo = null;
            bioArea = null;
            meetupCombo = null;
            form.getChildren().addAll(accountLabel, new Label("Full Name *"), fullName);
        }

        dialog.getDialogPane().setContent(form);

        // 3. Validate on "Save Changes" without closing the dialog; same rules as the seller profile.
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            String fullNameValue = fullName.getText() == null ? "" : fullName.getText().trim();
            if (fullNameValue.isEmpty()) {
                new Alert(Alert.AlertType.WARNING, "Full Name cannot be empty.").showAndWait();
                event.consume();
                return;
            }
            if (fullNameValue.codePointCount(0, fullNameValue.length()) > 100) {
                new Alert(Alert.AlertType.WARNING, "Full Name must be at most 100 characters.").showAndWait();
                event.consume();
                return;
            }

            String bioValue = bioArea != null ? bioArea.getText().trim() : target.getBio();
            if (bioValue != null && bioValue.getBytes(StandardCharsets.UTF_8).length > 65_535) {
                new Alert(Alert.AlertType.WARNING, "Your bio is too long. Please shorten it.").showAndWait();
                event.consume();
                return;
            }

            String meetupValue = meetupCombo != null ? meetupCombo.getValue() : target.getPreferredMeetup();
            if ("None".equals(meetupValue)) meetupValue = null;
            if (meetupValue != null && meetupValue.codePointCount(0, meetupValue.length()) > 100) {
                new Alert(Alert.AlertType.WARNING,
                        "Preferred Meet-up Location must be at most 100 characters.").showAndWait();
                event.consume();
                return;
            }

            String residenceValue = residenceCombo != null ? residenceCombo.getValue() : target.getResidenceHall();
            if ("None".equals(residenceValue)) residenceValue = null;

            // 4. Save. Copy the fields that were not shown from the loaded target so they are not blanked.
            User update = new User();//set the updated information
            update.setUsername(target.getUsername());
            update.setFullName(fullNameValue);
            update.setBio(bioValue);
            update.setPreferredMeetup(meetupValue);
            update.setResidenceHall(residenceValue);
            try {//update info in database
                if (!userRepository.updateProfile(update)) {
                    new Alert(Alert.AlertType.ERROR, "User no longer exists.").showAndWait();
                    event.consume();
                    dialog.close();
                    return;
                }
                new Alert(Alert.AlertType.INFORMATION, "Profile saved.").showAndWait();
            } catch (SQLException ex) {
                new Alert(Alert.AlertType.ERROR,
                        "Unable to save changes. Please try again.").showAndWait();
                event.consume();
            }
        });

        dialog.showAndWait();
        showUsers(); // refresh so the edited row shows the persisted values
    }

    @FXML
    private void showDeals() {
        setActive(btnDeals, "All Deals Record");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        HBox filters = new HBox(10);
        filters.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setPromptText("Search by item / user...");
        ComboBox<String> typeFilter = new ComboBox<>(FXCollections.observableArrayList("All Types", "Sell", "Lend", "Giveaway"));
        typeFilter.getSelectionModel().selectFirst();
        filters.getChildren().addAll(search, typeFilter);
        root.getChildren().add(filters);

        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        String[] headers = {"Item", "Buyer", "Seller", "Type", "Status", "Date"};
        for (int i = 0; i < headers.length; i++) {
            final int idx = i;
            TableColumn<String[], String> col = new TableColumn<>(headers[i]);
            col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[idx]));
            table.getColumns().add(col);
        }
        table.setItems(FXCollections.observableArrayList(
                new String[]{"Calculus Textbook", "alice", "charlie", "Sell", "Accepted", "2026-01-15"},
                new String[]{"USB Hub", "bob", "diana", "Giveaway", "Completed", "2025-12-01"}
        ));
        root.getChildren().add(table);
        contentPane.getChildren().add(root);
    }

    @FXML
    private void showRatings() {
        setActive(btnRatings, "Seller Ratings Overview");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        HBox filters = new HBox(10);
        TextField userFilter = new TextField();
        userFilter.setPromptText("Filter by seller...");
        ComboBox<String> scoreFilter = new ComboBox<>(FXCollections.observableArrayList(
                "All scores", "5 stars", "4 stars", "3 stars", "2 stars", "1 star"));
        scoreFilter.getSelectionModel().selectFirst();
        filters.getChildren().addAll(userFilter, scoreFilter);
        root.getChildren().add(filters);

        for (Review r : DummyDataService.getInstance().getAllReviews()) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(4);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label stars = new Label("★".repeat(r.getRating()) + "☆".repeat(5 - r.getRating()) +
                    "  @" + r.getSellerUsername() + " by @" + r.getBuyerUsername());
            stars.setStyle("-fx-font-weight: bold;");
            Label comment = new Label(r.getComment());
            comment.setWrapText(true);
            comment.setStyle("-fx-text-fill: #5a5a7a;");
            info.getChildren().addAll(stars, comment);

            Button hide = new Button(r.isHidden() ? "Unhide" : "Hide");
            hide.getStyleClass().addAll("btn-outline", "btn-sm");
            hide.setOnAction(e -> {
                r.setHidden(!r.isHidden());
                showRatings();
            });
            row.getChildren().addAll(info, hide);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Admin Notifications");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        List<Notification> notifs = DummyDataService.getInstance().getNotificationsFor("admin");
        if (notifs.isEmpty()) {
            root.getChildren().add(new Label("No notifications."));
        } else {
            for (Notification n : notifs) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                Label t = new Label(n.getTitle());
                t.setStyle("-fx-font-weight: bold;");
                Label m = new Label(n.getMessage());
                m.setWrapText(true);
                m.setStyle("-fx-text-fill: #5a5a7a;");
                Label meta = new Label(n.getCategory() + " · " +
                        n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm")));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                item.getChildren().addAll(t, m, meta);
                root.getChildren().add(item);
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "My Profile");
        contentPane.getChildren().clear();
        User sessionUser = SessionManager.getCurrentUser();
        if (sessionUser == null) {
            SceneManager.showAdminLogin();
            return;
        }
        User user;//get user account in database
        try{
            user = userRepository.findByUsername((sessionUser.getUsername()));
        }catch(SQLException e){
            new Alert(Alert.AlertType.ERROR,
                    "Unable to load your profile. Please try again. ").showAndWait();
            return;
        }
        if (user == null) {
            SessionManager.logout();
            SceneManager.showAdminLogin();
            return;
        }
        VBox form = new VBox(12);
        form.setMaxWidth(400);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        Label titleLbl = new Label("Admin Profile");
        titleLbl.getStyleClass().add("section-title");
        form.getChildren().add(titleLbl);

        TextField name = new TextField(user.getFullName());
        PasswordField pass = new PasswordField();
        pass.setPromptText("New password (optional)");

        Button save = new Button("Save");
        save.getStyleClass().add("btn-success");

        save.setOnAction(e -> {

            String fullname = name.getText().trim();
            if (fullname.isEmpty()){
                new Alert(Alert.AlertType.WARNING, "Full Name cannot be empty.").showAndWait();
                return;
            }
            if (fullname.codePointCount(0, fullname.length()) > 100) {
                new Alert(Alert.AlertType.WARNING, "Full Name must be at most 100 characters.").showAndWait();
                return;
            }
            User profileUpdate = new User();
            profileUpdate.setUsername(user.getUsername());
            profileUpdate.setFullName(fullname);//get modified
            profileUpdate.setResidenceHall(user.getResidenceHall());
            profileUpdate.setBio(user.getBio());
            profileUpdate.setPreferredMeetup(user.getPreferredMeetup());
            try{
                boolean updated = new AuthService(userRepository).updateProfile(profileUpdate, pass.getText());
                if (!updated){
                    new Alert(Alert.AlertType.ERROR, "Profile not found").showAndWait();
                    return;
                } //no username found and creates no updates
                sessionUser.setFullName(fullname);
                if (profileUpdate.getPasswordHash() != null) {
                    sessionUser.setPasswordHash(profileUpdate.getPasswordHash());
                }
                pass.clear();
                welcomeLabel.setText("Admin: " + fullname);
                new Alert(Alert.AlertType.INFORMATION, "Profile updated.").showAndWait();
            }catch(IllegalArgumentException invalidPassword){
                new Alert(Alert.AlertType.WARNING, invalidPassword.getMessage()).showAndWait();
            }catch(SQLException exc){
                new Alert(Alert.AlertType.ERROR, "Unable to save your profile. please try again. ").showAndWait();
            }

        });
        form.getChildren().addAll(new Label("Full Name"), name, new Label("New Password"), pass, save);
        contentPane.getChildren().add(form);
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
