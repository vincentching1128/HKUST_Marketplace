package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.ListingService;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;

public class SellerDashboardController {
    private final UserRepository userRepository = new UserRepository();
    private final ListingService listingService = new ListingService();
    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnListings, btnCreate, btnStats, btnReviews, btnNotifications, btnProfile;

    private Button currentActiveBtn;

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Hello, " + user.getFullName());
        currentActiveBtn = btnListings;
        showMyListings();
    }

    private void setActive(Button btn, String title) {
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showMyListings() {
        setActive(btnListings, "My Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        //the service checks the account and SQL only returns this seller's listings
        try {
            List<Listing> myListings = listingService.getMyListings(SessionManager.getCurrentUser());
            if (myListings.isEmpty()) {
                Label empty = new Label("You have not posted any listings yet.");
                empty.setStyle("-fx-text-fill: #5a5a7a;");
                root.getChildren().add(empty);
            } else {
                for (Listing listing : myListings) root.getChildren().add(createListingRow(listing));
            }
        } catch (IllegalArgumentException unavailable) {
            root.getChildren().add(new Label(unavailable.getMessage()));
        } catch (SQLException failure) {
            //a failed query should not look like a seller with no listings
            Label error = new Label("Unable to load your listings. Please try again.");
            Button retry = new Button("Retry");
            retry.setOnAction(event -> showMyListings());
            root.getChildren().addAll(error, retry);
        }

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private HBox createListingRow(Listing l) {
        HBox row = new HBox(16);
        row.getStyleClass().add("card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14));

        VBox info = new VBox(4);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label title = new Label(l.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label meta = new Label(l.getCategory() + " | " + l.getTypeDisplay() + " | " + l.getMeetupLocation());
        meta.setWrapText(true);
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        String terms = switch (l.getType()) {
            case SELL -> String.format("HK$ %.2f", l.getPrice());
            case LEND -> "Lending duration: " + l.getLendingDurationDisplay();
            case GIVEAWAY -> "Free";
        };
        info.getChildren().addAll(title, meta, new Label(terms));
        //keep the reason next to the listing so the seller knows why it was rejected
        if (l.getStatus() == ListingStatus.REJECTED && l.getRejectionReason() != null
                && !l.getRejectionReason().isBlank()) {
            Label reason = new Label("Rejection reason: " + l.getRejectionReason());
            reason.setWrapText(true);
            info.getChildren().add(reason);
        }

        Label status = new Label(l.getStatus().name().replace('_', ' '));
        status.getStyleClass().add("badge");
        switch (l.getStatus()) {
            case LIVE -> status.getStyleClass().add("badge-live");
            case PENDING_REVIEW -> status.getStyleClass().add("badge-pending");
            case REJECTED -> status.getStyleClass().add("badge-rejected");
            default -> status.getStyleClass().add("badge-sold");
        }

        Button editBtn = new Button("Edit");
        editBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        //keep these unavailable until database edits and deletes have ownership checks
        editBtn.setDisable(true);
        Button delBtn = new Button("Delete");
        delBtn.getStyleClass().addAll("btn-danger", "btn-sm");
        delBtn.setDisable(true);

        row.getChildren().addAll(info, status, editBtn, delBtn);
        return row;
    }

    @FXML
    private void showCreateListing() {
        setActive(btnCreate, "Create New Listing");
        contentPane.getChildren().clear();

        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));

        Label title = new Label("Post a new item");
        title.getStyleClass().add("section-title");

        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList("Sell", "Lend", "Give Away"));
        typeCombo.setPromptText("Listing type *");
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        TextField nameField = new TextField();
        nameField.setPromptText("Item name *");
        ComboBox<String> catCombo = new ComboBox<>(FXCollections.observableArrayList(DummyDataService.CATEGORIES));
        catCombo.setPromptText("Category *");
        catCombo.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> condCombo = new ComboBox<>(FXCollections.observableArrayList(DummyDataService.CONDITIONS));
        condCombo.setPromptText("Condition *");
        condCombo.setMaxWidth(Double.MAX_VALUE);
        TextArea descArea = new TextArea();
        descArea.setPromptText("Description *");
        descArea.setPrefRowCount(3);

        TextField priceField = new TextField();
        priceField.setPromptText("Price (HK$)");
        //show the price label and input only when the seller chooses Sell
        VBox priceInputs = new VBox(6, new Label("Price"), priceField);
        priceInputs.visibleProperty().bind(typeCombo.valueProperty().isEqualTo("Sell"));
        priceInputs.managedProperty().bind(priceInputs.visibleProperty());
        priceField.disableProperty().bind(typeCombo.valueProperty().isNotEqualTo("Sell"));
        TextField durationField = new TextField();
        durationField.setPromptText("Duration");
        ComboBox<LendingUnit> unitCombo = new ComboBox<>(
                FXCollections.observableArrayList(LendingUnit.values()));
        unitCombo.setValue(LendingUnit.DAYS);
        HBox durationInputs = new HBox(10, durationField, unitCombo);
        HBox.setHgrow(durationField, Priority.ALWAYS);
        durationInputs.disableProperty().bind(typeCombo.valueProperty().isNotEqualTo("Lend"));
        //hide the lending label, duration, and unit together for other listing types
        VBox lendingInputs = new VBox(6, new Label("Lending Duration"), durationInputs);
        lendingInputs.visibleProperty().bind(typeCombo.valueProperty().isEqualTo("Lend"));
        lendingInputs.managedProperty().bind(lendingInputs.visibleProperty());
        //discard values that no longer apply when the seller changes the listing type
        typeCombo.valueProperty().addListener((observable, oldType, newType) -> {
            if (!"Sell".equals(newType)) priceField.clear();
            if (!"Lend".equals(newType)) {
                durationField.clear();
                unitCombo.setValue(LendingUnit.DAYS);
            }
        });

        ComboBox<String> meetupCombo = new ComboBox<>(FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
        meetupCombo.setPromptText("Meet-up location *");
        meetupCombo.setMaxWidth(Double.MAX_VALUE);

        List<byte[]> selectedPhotos = new ArrayList<>();
        Label photoNote = new Label("0 of 6 photos selected");
        photoNote.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
        FlowPane photoPreviews = new FlowPane(10, 10);
        Button choosePhotos = new Button("Choose Photos");
        choosePhotos.getStyleClass().add("btn-outline");
        choosePhotos.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose up to 6 listing photos");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Images (PNG, JPEG, GIF)", "*.png", "*.jpg", "*.jpeg", "*.gif"));
            List<File> files = chooser.showOpenMultipleDialog(contentPane.getScene().getWindow());
            if (files == null) return;
            //new selections are added to the photos already chosen
            if (selectedPhotos.size() + files.size() > 6) {
                new Alert(Alert.AlertType.WARNING, "A listing can have at most six photos. Remove a photo before adding more.").showAndWait();
                return;
            }
            List<byte[]> images = new ArrayList<>();
            try {
                for (File file : files) {
                    long size = Files.size(file.toPath());
                    if (size == 0 || size > Listing.MAX_PHOTO_BYTES) {
                        throw new IOException(file.getName() + ": each photo must be smaller than 16 MiB and not empty.");
                    }
                    byte[] imageData = Files.readAllBytes(file.toPath());
                    if (imageData.length == 0 || imageData.length > Listing.MAX_PHOTO_BYTES
                            || new Image(new ByteArrayInputStream(imageData), 160, 120, true, true).isError()) {
                        throw new IOException(file.getName() + ": unable to read a supported image.");
                    }
                    images.add(imageData);
                }
            } catch (IOException | SecurityException error) {
                new Alert(Alert.AlertType.ERROR, "Unable to select photos. " + error.getMessage()).showAndWait();
                return;
            }
            //only add this selection after every chosen file has passed validation
            selectedPhotos.addAll(images);
            refreshPhotoPreviews(selectedPhotos, photoPreviews, photoNote);
        });
        Button clearPhotos = new Button("Clear Photos");
        clearPhotos.getStyleClass().add("btn-outline");
        clearPhotos.setOnAction(event -> {
            selectedPhotos.clear();
            refreshPhotoPreviews(selectedPhotos, photoPreviews, photoNote);
        });
        HBox photoButtons = new HBox(10, choosePhotos, clearPhotos);

        Button submitBtn = new Button("Submit for Review");
        submitBtn.getStyleClass().add("btn-gold");
        submitBtn.setOnAction(e -> {
            //show all field problems together so an invalid price is not hidden by a missing field
            List<String> errors = new ArrayList<>();
            if (typeCombo.getValue() == null) errors.add("Choose a listing type.");
            if (nameField.getText().isBlank()) errors.add("Item name is required.");
            if (catCombo.getValue() == null) errors.add("Choose a category.");
            if (condCombo.getValue() == null) errors.add("Choose an item condition.");
            if (descArea.getText().isBlank()) errors.add("Description is required.");
            if (meetupCombo.getValue() == null) errors.add("Choose a meet-up location.");
            if ("Sell".equals(typeCombo.getValue())) {
                if (priceField.getText().isBlank()) {
                    errors.add("Price is required.");
                } else {
                    try {
                        double price = Double.parseDouble(priceField.getText().trim());
                        if (!Double.isFinite(price) || price <= 0) {
                            errors.add("Price must be a positive number.");
                        } else if (price > 99_999_999.99) {
                            errors.add("Price must not exceed HK$99,999,999.99.");
                        } else if (java.math.BigDecimal.valueOf(price).stripTrailingZeros().scale() > 2) {
                            errors.add("Price must have at most two decimal places.");
                        }
                    } catch (NumberFormatException invalidPrice) {
                        errors.add("Enter a valid number for price.");
                    }
                }
            }
            if ("Lend".equals(typeCombo.getValue())) {
                try {
                    if (Integer.parseInt(durationField.getText().trim()) <= 0) {
                        errors.add("Lending duration must be a positive whole number.");
                    }
                } catch (NumberFormatException invalidDuration) {
                    errors.add("Enter a positive whole number for lending duration.");
                }
                if (unitCombo.getValue() == null) errors.add("Choose a lending unit.");
            }
            if (!errors.isEmpty()) {
                new Alert(Alert.AlertType.WARNING, String.join("\n", errors)).showAndWait();
                return;
            }
            //collect the form values; the service assigns the seller and review status
            User user = SessionManager.getCurrentUser();
            Listing listing = new Listing();
            listing.setTitle(nameField.getText().trim());
            listing.setDescription(descArea.getText());
            listing.setCategory(catCombo.getValue());
            listing.setCondition(condCombo.getValue());
            listing.setMeetupLocation(meetupCombo.getValue());
            listing.setPhotos(new ArrayList<>(selectedPhotos));

            //convert text inputs to numbers before passing them to the service
            switch (typeCombo.getValue()) {
                case "Sell" -> {
                    listing.setType(ListingType.SELL);
                    try { listing.setPrice(Double.parseDouble(priceField.getText().trim())); }
                    catch (NumberFormatException ex) {
                        new Alert(Alert.AlertType.WARNING, "Valid price required for Sell listings.").showAndWait();
                        return;
                    }
                }
                case "Lend" -> {
                    listing.setType(ListingType.LEND);
                    try {
                        int duration = Integer.parseInt(durationField.getText().trim());
                        if (duration <= 0 || unitCombo.getValue() == null) {
                            new Alert(Alert.AlertType.WARNING,
                                    "Enter a positive whole-number lending duration and select Days, Weeks, or Months.").showAndWait();
                            return;
                        }
                        listing.setLendingDuration(duration);
                        listing.setLendingUnit(unitCombo.getValue());
                    } catch (NumberFormatException ex) {
                        new Alert(Alert.AlertType.WARNING, "Enter a positive whole-number lending duration.").showAndWait();
                        return;
                    }
                }
                default -> listing.setType(ListingType.GIVEAWAY);
            }
            //keep submission disabled while the service validates and saves the listing
            submitBtn.setDisable(true);
            try {
                listingService.createListing(user, listing);
            } catch (IllegalArgumentException invalid) {
                //show the validation message and keep the form values so the seller can fix them
                new Alert(Alert.AlertType.WARNING, invalid.getMessage()).showAndWait();
                return;
            } catch (SQLException failure) {
                //keep the form filled in when the database save fails
                new Alert(Alert.AlertType.ERROR,
                        "Unable to save your listing. Please try again.").showAndWait();
                return;
            } finally {
                //allow another attempt even if validation or saving failed
                submitBtn.setDisable(false);
            }
            //only report success after the repository has committed the save
            new Alert(Alert.AlertType.INFORMATION,
                    "Listing saved! Status: Pending Review.").showAndWait();
            showMyListings(); //show the saved listing from MySQL after submission
        });

        //choose the listing type first before entering details, selecting photos, or submitting
        VBox listingDetails = new VBox(12);
        listingDetails.disableProperty().bind(typeCombo.valueProperty().isNull());
        listingDetails.getChildren().addAll(
                new Label("Item Name *"), nameField,
                new Label("Category *"), catCombo,
                new Label("Condition *"), condCombo,
                new Label("Description *"), descArea,
                priceInputs, lendingInputs,
                new Label("Meet-up Location *"), meetupCombo,
                new Label("Photos (optional, up to 6)"), photoButtons, photoNote, photoPreviews, submitBtn);
        form.getChildren().addAll(title, new Label("Listing Type *"), typeCombo, listingDetails);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    //rebuild the thumbnails after adding or removing photos, keeping their saved order
    private void refreshPhotoPreviews(List<byte[]> photos, FlowPane previews, Label note) {
        previews.getChildren().clear();
        note.setText(photos.size() + " of 6 photos selected");
        for (int i = 0; i < photos.size(); i++) {
            int photoIndex = i;
            Image image = new Image(new ByteArrayInputStream(photos.get(i)), 160, 120, true, true);
            ImageView thumbnail = new ImageView(image);
            thumbnail.setFitWidth(160);
            thumbnail.setFitHeight(120);
            thumbnail.setPreserveRatio(true);
            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("btn-outline", "btn-sm");
            remove.setOnAction(event -> {
                photos.remove(photoIndex);
                refreshPhotoPreviews(photos, previews, note);
            });
            VBox preview = new VBox(6, new Label("Photo " + (i + 1)), thumbnail, remove);
            preview.setAlignment(Pos.CENTER);
            previews.getChildren().add(preview);
        }
    }

    @FXML
    private void showStats() {
        setActive(btnStats, "Seller Stats");
        contentPane.getChildren().clear();

        VBox root = new VBox(20);
        root.setPadding(new Insets(4));

        User user = SessionManager.getCurrentUser();
        List<Listing> myListings = DummyDataService.getInstance()
                .getListingsBySeller(user != null ? user.getUsername() : "");

        // Metric cards
        HBox metrics = new HBox(16);
        metrics.getChildren().addAll(
                metricCard("Listings", String.valueOf(myListings.size()), "#003974"),
                metricCard("Avg Rating", String.format("%.1f", user != null ? user.getAverageRating() : 0), "#A68137"),
                metricCard("Reviews", String.valueOf(user != null ? user.getRatingCount() : 0), "#2e7d32"),
                metricCard("Views (demo)", "128", "#1565c0")
        );
        root.getChildren().add(metrics);

        // Bar chart
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> barChart = new BarChart<>(xAxis, yAxis);
        barChart.setTitle("Listings by Category (demo data)");
        barChart.setLegendVisible(false);
        barChart.setPrefHeight(280);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.getData().add(new XYChart.Data<>("Textbooks", 3));
        series.getData().add(new XYChart.Data<>("Electronics", 5));
        series.getData().add(new XYChart.Data<>("Furniture", 2));
        series.getData().add(new XYChart.Data<>("Others", 1));
        barChart.getData().add(series);

        // Pie chart
        PieChart pie = new PieChart();
        pie.setTitle("Listing Status Distribution");
        pie.setPrefHeight(280);
        pie.getData().add(new PieChart.Data("Live", 4));
        pie.getData().add(new PieChart.Data("Pending", 1));
        pie.getData().add(new PieChart.Data("Sold", 2));

        HBox charts = new HBox(20, barChart, pie);
        root.getChildren().add(charts);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private VBox metricCard(String label, String value, String color) {
        VBox card = new VBox(4);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(140);
        card.setPadding(new Insets(16));
        Label val = new Label(value);
        val.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        card.getChildren().addAll(val, lbl);
        return card;
    }

    @FXML
    private void showReviews() {
        setActive(btnReviews, "Reviews & Feedback");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        User user = SessionManager.getCurrentUser();
        List<Review> reviews = DummyDataService.getInstance()
                .getReviewsForSeller(user != null ? user.getUsername() : "");

        if (reviews.isEmpty()) {
            root.getChildren().add(new Label("No reviews yet."));
        } else {
            for (Review r : reviews) {
                VBox item = new VBox(6);
                item.getStyleClass().add("card");
                Label stars = new Label("★".repeat(r.getRating()) + "☆".repeat(5 - r.getRating()));
                stars.setStyle("-fx-text-fill: #A68137; -fx-font-size: 16px;");
                Label comment = new Label(r.getComment());
                comment.setWrapText(true);
                Label meta = new Label("by @" + r.getBuyerUsername() + " · " + r.getListingTitle());
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
                HBox actions = new HBox(8);
                Button reply = new Button("Reply");
                reply.getStyleClass().addAll("btn-outline", "btn-sm");
                reply.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Reply sent to buyer (demo).").showAndWait());
                Button flag = new Button("Flag");
                flag.getStyleClass().addAll("btn-danger", "btn-sm");
                flag.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Review flagged for admin (demo).").showAndWait());
                actions.getChildren().addAll(reply, flag);
                item.getChildren().addAll(stars, comment, meta, actions);
                root.getChildren().add(item);
            }
        }
        contentPane.getChildren().add(new ScrollPane(root));
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notifications");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        User user = SessionManager.getCurrentUser();
        List<Notification> notifs = DummyDataService.getInstance()
                .getNotificationsFor(user != null ? user.getUsername() : "");
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
                m.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 13px;");
                Label meta = new Label(n.getCategory() + " · " +
                        n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm")));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                item.getChildren().addAll(t, m, meta);
                root.getChildren().add(item);
            }
        }
        contentPane.getChildren().add(new ScrollPane(root));
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "My Profile");
        contentPane.getChildren().clear();
        User sessionUser = SessionManager.getCurrentUser();
        if (sessionUser == null) {
            SceneManager.showSellerLogin();
            return;
        }
        User user;
        try{
            user = userRepository.findByUsername((sessionUser.getUsername()));
        }catch(SQLException e){
            new Alert(Alert.AlertType.ERROR,
            "Unable to load your profile. Please try again. ").showAndWait();
            return;
        }

        if (user == null) {
            SessionManager.logout();
            SceneManager.showSellerLogin();
            return;
        }

        VBox form = new VBox(12);
        form.setMaxWidth(420);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        form.getChildren().add(new Label("Edit Seller Profile") {{ getStyleClass().add("section-title"); }});

        TextField name = new TextField(user != null ? user.getFullName() : "");
        PasswordField pass = new PasswordField();
        pass.setPromptText("New password (optional)");
        TextArea bio = new TextArea(user != null && user.getBio() != null ? user.getBio() : "");
        bio.setPrefRowCount(3);
        ComboBox<String> meetup = new ComboBox<>(FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
        meetup.getItems().add(0, "None");
        meetup.setValue("None");
        if (user != null && user.getPreferredMeetup() != null) meetup.setValue(user.getPreferredMeetup());
        meetup.setMaxWidth(Double.MAX_VALUE);

        Button save = new Button("Save Changes");
        save.getStyleClass().add("btn-gold");
        save.setOnAction(e -> {
            String fullname = name.getText().trim();
            String bioInfo = bio.getText().trim();
            String PreferredMeetupLocation = meetup.getValue();
            if ("None".equals(PreferredMeetupLocation)) PreferredMeetupLocation = null;
            //validation logic
            if (fullname.isEmpty()){
                new Alert(Alert.AlertType.WARNING, "Full Name cannot be empty.").showAndWait();
                return;
            }
            if (fullname.codePointCount(0, fullname.length()) > 100) {
                new Alert(Alert.AlertType.WARNING, "Full Name must be at most 100 characters.").showAndWait();
                return;
            }
            if (bioInfo.getBytes(StandardCharsets.UTF_8).length > 65_535) {
                new Alert(Alert.AlertType.WARNING, "Your bio is too long. Please shorten it.").showAndWait();
                return;
            }
            if (PreferredMeetupLocation != null &&
                    PreferredMeetupLocation.codePointCount(0, PreferredMeetupLocation.length()) > 100) {
                new Alert(Alert.AlertType.WARNING,
                        "Preferred Meet-up Location must be at most 100 characters.").showAndWait();
                return;
            }
            // Keep edits separate until the database confirms the save.
            User profileUpdate = new User();
            profileUpdate.setUsername(user.getUsername());
            profileUpdate.setFullName(fullname);
            profileUpdate.setResidenceHall(user.getResidenceHall());
            profileUpdate.setBio(bioInfo);
            profileUpdate.setPreferredMeetup(PreferredMeetupLocation);
            try{
                boolean updated = new AuthService(userRepository).updateProfile(profileUpdate, pass.getText());
                if (!updated){
                    new Alert(Alert.AlertType.ERROR, "Profile not found").showAndWait();
                    return;
                } //no username found and creates no updates

                // Update only profile fields, preserving the session's other account data.
                sessionUser.setFullName(fullname);
                sessionUser.setBio(bioInfo);
                sessionUser.setPreferredMeetup(PreferredMeetupLocation);
                if (profileUpdate.getPasswordHash() != null) {
                    sessionUser.setPasswordHash(profileUpdate.getPasswordHash());
                }
                pass.clear();
                welcomeLabel.setText("Hello, " + sessionUser.getFullName());
                new Alert(Alert.AlertType.INFORMATION, "profile saved").showAndWait();
            } catch (IllegalArgumentException invalidPassword){
                new Alert(Alert.AlertType.WARNING, invalidPassword.getMessage()).showAndWait();
            } catch (SQLException ex){
                new Alert(Alert.AlertType.ERROR, "Unable to save your profile. please try again. ").showAndWait();
            }
        });

        form.getChildren().addAll(
                new Label("Full Name"), name,
                new Label("New Password"), pass,
                new Label("Bio"), bio,
                new Label("Preferred Meet-up"), meetup,
                save);
        contentPane.getChildren().add(form);
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
