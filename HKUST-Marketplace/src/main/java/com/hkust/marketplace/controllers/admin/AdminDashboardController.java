package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.repositories.ListingRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.ListingService;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private final ListingRepository listingRepository = new ListingRepository();
    private final ListingService listingService = new ListingService();

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

        //List<Listing> pending = DummyDataService.getInstance().getPendingListings();
        List<Listing> pending = List.of();
        try{
            pending = listingRepository.findByStatus(ListingStatus.PENDING_REVIEW);
        }catch(SQLException ex){
            LOGGER.log(Level.SEVERE, "Failed to load listings from the database.", ex);
            Label dbError = new Label("Unable to load listings. Please try again.");
            dbError.setStyle("-fx-text-fill: #c62828; -fx-font-size: 13px;");
            dbError.setWrapText(true);
            root.getChildren().add(dbError);
            contentPane.getChildren().add(new ScrollPane(root));
            return;
        }
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
                    approve_listing(l);
                    /*
                    l.setStatus(ListingStatus.LIVE);
                    l.setReviewedAt(LocalDateTime.now());
                    DummyDataService.getInstance().updateListing(l);
                    DummyDataService.getInstance().addNotification(new Notification(
                            l.getSellerUsername(), "Listing Approved",
                            "Your listing '" + l.getTitle() + "' is now live!", "System"));

                     */
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
                reject_listing(l,reason.trim());
                /*
                l.setStatus(ListingStatus.REJECTED);
                l.setRejectionReason(reason);
                l.setReviewedAt(LocalDateTime.now());
                DummyDataService.getInstance().updateListing(l);
                DummyDataService.getInstance().addNotification(new Notification(
                        l.getSellerUsername(), "Listing Rejected",
                        "Your listing '" + l.getTitle() + "' was rejected. Reason: " + reason, "System"));

                 */
                showReview();
            });
        });

        actions.getChildren().addAll(approve, reject);
        card.getChildren().addAll(title, meta, submitted, actions);
        return card;
    }
    //handle the approval of listing in database
    private void approve_listing(Listing target){
        try{
            if(!listingRepository.updateReview(target.getId(),ListingStatus.LIVE,null)){
                new Alert(Alert.AlertType.ERROR, "This listing no longer exists.").showAndWait();
            }

        }catch(SQLException ex){
            LOGGER.log(Level.SEVERE, "Failed to approve listing " + target.getId(), ex);
            new Alert(Alert.AlertType.ERROR, "Unable to approve this listing. Please try again.").showAndWait();
        }

    }
    private void reject_listing(Listing target, String reason){
        try{
            if(!listingRepository.updateReview(target.getId(),ListingStatus.REJECTED,reason)){
                new Alert(Alert.AlertType.ERROR, "This listing no longer exists.").showAndWait();
            }
        }catch(IllegalArgumentException invalidReason){
            new Alert(Alert.AlertType.WARNING, invalidReason.getMessage()).showAndWait();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to reject listing " + target.getId(), ex);
            new Alert(Alert.AlertType.ERROR, "Unable to reject this listing. Please try again.").showAndWait();
        }

    }
    @FXML
    private void showAllListings() {
        setActive(btnAllListings, "Manage All Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        Button addBtn = new Button("+ Add Listing (on behalf of seller)");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> createListing());
        /*addBtn.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Admin can create a listing on behalf of a seller here.\nSame form fields as Seller Create Listing.\n(UI placeholder)").showAndWait());*/
        root.getChildren().add(addBtn);
        List<Listing> alllists = List.of();
        try{
            alllists = listingRepository.findAllListing();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to load listings from the database.", ex);
            Label dbError = new Label("Unable to load listings. Please try again.");
            dbError.setStyle("-fx-text-fill: #c62828; -fx-font-size: 13px;");
            dbError.setWrapText(true);
            root.getChildren().add(dbError);
            contentPane.getChildren().add(new ScrollPane(root));
            return;
        }
        for (Listing l : alllists) {
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
            //edit.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Edit listing dialog (demo).").showAndWait());
            edit.setOnAction(e ->editListing(l));

            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("btn-danger", "btn-sm");
            remove.setOnAction(e -> {
                Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                        "Remove this listing? It will be marked as REMOVED and hidden from all listing views.");
                conf.showAndWait().ifPresent(a -> {
                    if (a == ButtonType.OK) {
                        TextInputDialog reasonDialog = new TextInputDialog();
                        reasonDialog.setTitle("Removal Reason");
                        reasonDialog.setHeaderText("Please provide a reason for removal");
                        reasonDialog.setContentText("Reason:");
                        reasonDialog.showAndWait().ifPresent(reason -> {
                            if (reason.isBlank()) {
                                new Alert(Alert.AlertType.WARNING, "Reason is required.").showAndWait();
                                return;
                            }

                            removeListing(l,reason.trim());
                            //DummyDataService.getInstance().removeListing(l.getId());
                            showAllListings();
                        });
                    };
                });
            });

            row.getChildren().addAll(info, edit, remove);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }
    //Admin creates a new listing on behalf of an active seller.
    private void createListing() {
        showAdminListingForm(null);
    }

    //Admin edits any listing; saving returns it to Pending Review.
    private void editListing(Listing target) {
        showAdminListingForm(target);
    }

    //One form for both admin actions, modelled on SellerDashboardController.showListingForm().
    private void showAdminListingForm(Listing existing) {
        boolean editing = existing != null;
        setActive(btnAllListings, editing ? "Edit Listing" : "Create Listing");
        contentPane.getChildren().clear();

        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));

        Label title = new Label(editing ? "Edit listing" : "Create a listing for a seller");
        title.getStyleClass().add("section-title");

        //create: pick an active seller from MySQL; edit: the owner is read-only
        final ComboBox<User> sellerCombo;
        if (editing) {
            sellerCombo = null;
        } else {
            sellerCombo = new ComboBox<>();
            sellerCombo.setPromptText("Seller *");
            sellerCombo.setMaxWidth(Double.MAX_VALUE);
            try {
                List<User> sellers = new ArrayList<>();
                for (User u : userRepository.findAll()) {
                    if (u.getRole() == UserRole.SELLER && u.isActive()) sellers.add(u);
                }
                sellerCombo.setItems(FXCollections.observableArrayList(sellers));
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "Failed to load sellers for the listing form.", ex);
                new Alert(Alert.AlertType.ERROR, "Unable to load sellers. Please try again.").showAndWait();
                showAllListings();
                return;
            }
        }

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
        VBox lendingInputs = new VBox(6, new Label("Lending Duration"), durationInputs);
        lendingInputs.visibleProperty().bind(typeCombo.valueProperty().isEqualTo("Lend"));
        lendingInputs.managedProperty().bind(lendingInputs.visibleProperty());
        //discard values that no longer apply when the admin changes the listing type
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

        //set the type first because changing it clears inputs that no longer apply
        if (editing) {
            typeCombo.setValue(switch (existing.getType()) {
                case SELL -> "Sell";
                case LEND -> "Lend";
                case GIVEAWAY -> "Give Away";
            });
            nameField.setText(existing.getTitle());
            catCombo.setValue(existing.getCategory());
            condCombo.setValue(existing.getCondition());
            descArea.setText(existing.getDescription());
            meetupCombo.setValue(existing.getMeetupLocation());
            if (existing.getPrice() != null) {
                priceField.setText(java.math.BigDecimal.valueOf(existing.getPrice()).toPlainString());
            }
            if (existing.getLendingDuration() != null) {
                durationField.setText(existing.getLendingDuration().toString());
                unitCombo.setValue(existing.getLendingUnit());
            }
            if (existing.getPhotos() != null) selectedPhotos.addAll(existing.getPhotos());
            refreshPhotoPreviews(selectedPhotos, photoPreviews, photoNote);
        }

        Button submitBtn = new Button(editing ? "Save Changes" : "Create Listing");
        submitBtn.getStyleClass().add("btn-gold");
        submitBtn.setOnAction(e -> {
            //show all field problems together so one invalid value does not hide the others
            List<String> errors = new ArrayList<>();
            if (!editing && sellerCombo.getValue() == null) errors.add("Choose a seller.");
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
            Listing listing = new Listing();
            listing.setTitle(nameField.getText().trim());
            listing.setDescription(descArea.getText());
            listing.setCategory(catCombo.getValue());
            listing.setCondition(condCombo.getValue());
            listing.setMeetupLocation(meetupCombo.getValue());
            listing.setPhotos(new ArrayList<>(selectedPhotos));
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
            if (editing) {
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                        "Save changes to '" + existing.getTitle() + "'? The listing will return to Pending Review.",
                        ButtonType.OK, ButtonType.CANCEL);
                if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            }
            submitBtn.setDisable(true);
            User admin = SessionManager.getCurrentUser();
            try {
                if (editing) listingService.updateListingAsAdmin(admin, existing.getId(), listing);
                else listingService.createListingAsAdmin(admin, sellerCombo.getValue().getUsername(), listing);
            } catch (IllegalArgumentException invalid) {
                //keep the form values so the admin can fix the input
                new Alert(Alert.AlertType.WARNING, invalid.getMessage()).showAndWait();
                return;
            } catch (SQLException failure) {
                LOGGER.log(Level.SEVERE, "Failed to save a listing from the admin portal.", failure);
                new Alert(Alert.AlertType.ERROR, "Unable to save this listing. Please try again.").showAndWait();
                return;
            } finally {
                submitBtn.setDisable(false);
            }
            new Alert(Alert.AlertType.INFORMATION,
                    editing ? "Listing updated. Status: Pending Review." : "Listing created. Status: Pending Review.").showAndWait();
            showAllListings();
        });

        //choose the listing type first before entering details, selecting photos, or submitting
        VBox listingDetails = new VBox(12);
        listingDetails.disableProperty().bind(typeCombo.valueProperty().isNull());
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn-outline");
        cancel.setOnAction(event -> showAllListings());
        HBox formActions = new HBox(10, submitBtn, cancel);
        listingDetails.getChildren().addAll(
                new Label("Item Name *"), nameField,
                new Label("Category *"), catCombo,
                new Label("Condition *"), condCombo,
                new Label("Description *"), descArea,
                priceInputs, lendingInputs,
                new Label("Meet-up Location *"), meetupCombo,
                new Label("Photos (optional, up to 6)"), photoButtons, photoNote, photoPreviews, formActions);

        if (editing) {
            Label sellerLabel = new Label("Seller: " + existing.getSellerFullName() + " (@" + existing.getSellerUsername() + ")");
            sellerLabel.setStyle("-fx-text-fill: #5a5a7a;");
            form.getChildren().addAll(title, sellerLabel, new Label("Listing Type *"), typeCombo, listingDetails);
        } else {
            form.getChildren().addAll(title, new Label("Seller *"), sellerCombo,
                    new Label("Listing Type *"), typeCombo, listingDetails);
        }

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
    //Soft delete: the row, photos and history stay; the listing is hidden from all views.
    private void removeListing(Listing target, String reason){
        try{
            boolean removed = listingRepository.removeListing(target.getId(), target.getSellerUsername(),reason);
            if (!removed) {
                new Alert(Alert.AlertType.WARNING,
                        "This listing no longer exists or was already removed.").showAndWait();
                return;
            }
            new Alert(Alert.AlertType.INFORMATION, "Listing removed.").showAndWait();
        }catch(IllegalArgumentException invalid){
            LOGGER.log(Level.WARNING, "Invalid listing removal request.", invalid);
            new Alert(Alert.AlertType.WARNING, invalid.getMessage()).showAndWait();
        }catch(SQLException ex){
            LOGGER.log(Level.SEVERE, "Failed to remove listing " + target.getId(), ex);
            new Alert(Alert.AlertType.ERROR, "Unable to remove this listing. Please try again.").showAndWait();
        }
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
            return;
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
