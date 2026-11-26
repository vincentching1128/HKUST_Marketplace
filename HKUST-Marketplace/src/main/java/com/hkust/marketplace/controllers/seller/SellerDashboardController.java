package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.controllers.NotificationBoard;
import com.hkust.marketplace.controllers.UnreadNotificationBadge;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.SellerStatsRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.NotificationService;
import com.hkust.marketplace.services.ListingService;
import com.hkust.marketplace.services.DealService;
import com.hkust.marketplace.services.ChatService;
import com.hkust.marketplace.services.ReviewService;
import com.hkust.marketplace.controllers.SellerProfilePane;
import com.hkust.marketplace.controllers.ChatInboxPane;
import com.hkust.marketplace.controllers.ChatPane;
import javafx.concurrent.Task;
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
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import com.hkust.marketplace.controllers.RatingDisplay;

/**
 * FXML controller for the Seller Portal dashboard.
 *
 * <p>Coordinates MySQL-backed listing creation and management, incoming proposals,
 * buyer/seller chat, ratings, statistics, notifications, and seller profile editing for
 * the signed-in seller.
 */
public class SellerDashboardController {
    private final UserRepository userRepository = new UserRepository();
    private final ListingService listingService = new ListingService();
    private final NotificationRepository notificationRepository = new NotificationRepository();
    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnListings, btnCreate, btnOffers, btnChats, btnStats, btnReviews, btnNotifications, btnProfile;

    private Button currentActiveBtn;
    private NotificationBoard notificationBoard;
    private UnreadNotificationBadge unreadNotificationBadge;
    private SellerOffersPane offersPane;
    private final ChatService chatService = new ChatService();
    private ChatInboxPane chatInbox;
    private ChatPane chatPane;
    private long chatRequest;

    @FXML
    public void initialize() {
        unreadNotificationBadge = new UnreadNotificationBadge(btnNotifications,
                new NotificationService(userRepository, notificationRepository, UserRole.SELLER));
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Hello, " + user.getFullName());
        currentActiveBtn = btnListings;
        showMyListings();
    }

    private void setActive(Button btn, String title) {
        if (notificationBoard != null && btn != btnNotifications) {
            notificationBoard.deactivate();
        }
        if (offersPane != null) offersPane.deactivate();
        if (chatInbox != null) chatInbox.deactivate();
        if (chatPane != null) chatPane.deactivate();
        chatRequest++;
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showIncomingOffers() {
        setActive(btnOffers, "Incoming Offers");
        offersPane = new SellerOffersPane(new DealService(), SessionManager::getCurrentUser, this::showListingDetails, this::openChatForDeal);
        contentPane.getChildren().setAll(offersPane);
        offersPane.refresh();
    }

    @FXML
    private void showChats() {
        setActive(btnChats,"Chats");
        chatInbox = new ChatInboxPane(chatService,SessionManager::getCurrentUser,thread -> showChatThread(thread.id()));
        contentPane.getChildren().setAll(chatInbox);
        chatInbox.refresh();
    }

    private void showChatThread(String threadId) {
        setActive(btnChats,"Private Chat");
        chatPane = new ChatPane(chatService,SessionManager::getCurrentUser,threadId,this::showChats);
        contentPane.getChildren().setAll(chatPane);
        chatPane.refresh();
    }

    private void openChatForDeal(String dealId) {
        setActive(btnChats,"Private Chat");
        long request = chatRequest;
        User seller = SessionManager.getCurrentUser();
        contentPane.getChildren().setAll(new VBox(12,new Label("Opening conversation..."),new ProgressIndicator()));
        Task<ChatThread> open = new Task<>() {
            @Override protected ChatThread call() throws SQLException {
                ChatThread thread = chatService.openForDeal(seller, dealId);
                Deal deal = new DealService().getSellerDeal(seller, dealId);
                if (deal.getStatus() == DealStatus.COUNTERED) {
                    try { chatService.sendProposal(seller, thread.id(), dealId); }
                    catch (SQLException | IllegalArgumentException failure) {
                        System.err.println("Counteroffer chat message could not be shared: " + failure.getMessage());
                    }
                }
                return thread;
            }
        };
        open.setOnSucceeded(event -> { if (currentChatRequest(request,seller)) showChatThread(open.getValue().id()); });
        open.setOnFailed(event -> {
            if (!currentChatRequest(request,seller)) return;
            Label error = new Label(open.getException() instanceof IllegalArgumentException ? open.getException().getMessage()
                    : "Unable to open this conversation. Please try again.");
            error.setWrapText(true);
            Button retry = new Button("Retry");retry.setOnAction(e -> openChatForDeal(dealId));
            Button back = new Button("Back to Chats");back.setOnAction(e -> showChats());
            contentPane.getChildren().setAll(new VBox(12,error,retry,back));
        });
        Thread thread = new Thread(open,"seller-open-chat");thread.setDaemon(true);thread.start();
    }

    private boolean currentChatRequest(long request,User seller) {
        User current=SessionManager.getCurrentUser();
        return request==chatRequest&&currentActiveBtn==btnChats&&seller!=null&&current!=null
                &&java.util.Objects.equals(seller.getUsername(),current.getUsername());
    }

    @FXML
    private void showMyListings() {
        setActive(btnListings, "My Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));
        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn-outline");
        refresh.setOnAction(event -> showMyListings());
        root.getChildren().add(refresh);

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
        Label terms = new Label(l.termsDisplay());
        terms.setWrapText(true);
        info.getChildren().addAll(title, meta, terms);
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
        editBtn.setOnAction(event -> {
            try {
                Listing saved = listingService.getListing(SessionManager.getCurrentUser(), l.getId());
                listingService.checkCanChange(saved);
                showListingForm(saved);
            } catch (IllegalArgumentException unavailable) {
                new Alert(Alert.AlertType.WARNING, unavailable.getMessage()).showAndWait();
            } catch (SQLException failure) {
                new Alert(Alert.AlertType.ERROR, "Unable to load this listing. Please try again.").showAndWait();
            }
        });
        Button delBtn = new Button("Delete");
        delBtn.getStyleClass().addAll("btn-danger", "btn-sm");
        delBtn.setOnAction(event -> {
            try {
                ListingService.ListingChangePreview preview = listingService.previewListingChange(SessionManager.getCurrentUser(), l.getId());
                if (preview.listingVersion() != l.getRowVersion()) {
                    throw new IllegalArgumentException("This listing changed. Refresh My Listings before deleting it.");
                }
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete '" + l.getTitle() + "'? It will no longer be available in the marketplace."
                                + affectedProposalWarning(preview), ButtonType.OK, ButtonType.CANCEL);
                if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                listingService.deleteListing(SessionManager.getCurrentUser(), l.getId(), preview);
                new Alert(Alert.AlertType.INFORMATION, "Listing removed.").showAndWait();
                showMyListings();
            } catch (IllegalArgumentException unavailable) {
                new Alert(Alert.AlertType.WARNING, unavailable.getMessage()).showAndWait();
            } catch (SQLException failure) {
                new Alert(Alert.AlertType.ERROR, "Unable to delete this listing. Please try again.").showAndWait();
            }
        });
        //the service and repository repeat this check against current data when an action runs
        boolean protectedStatus = l.getStatus() != ListingStatus.PENDING_REVIEW
                && l.getStatus() != ListingStatus.LIVE && l.getStatus() != ListingStatus.REJECTED;
        editBtn.setDisable(protectedStatus);
        delBtn.setDisable(protectedStatus);
        if (protectedStatus) {
            editBtn.setTooltip(new Tooltip("This listing's status does not allow editing."));
            delBtn.setTooltip(new Tooltip("This listing's status does not allow deletion."));
        }

        Button viewBtn = new Button("View Details");
        viewBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        viewBtn.setOnAction(event -> showListingDetails(l.getId()));
        row.getChildren().addAll(info, status, viewBtn, editBtn, delBtn);
        return row;
    }

    //open an owned listing without requiring it to be editable
    private void showListingDetails(String listingId) {
        try {
            //load a fresh copy so the screen shows the latest saved status and details
            Listing listing = listingService.getListing(SessionManager.getCurrentUser(), listingId);
            VBox details = createReadOnlyListingDetails(listing);
            Button back = new Button("Back to My Listings");
            back.getStyleClass().add("btn-outline");
            back.setOnAction(event -> showMyListings());
            VBox root = new VBox(14, back, details);
            root.setPadding(new Insets(4));
            ScrollPane scroll = new ScrollPane(root);
            scroll.setFitToWidth(true);
            setActive(btnListings, "Listing Details");
            contentPane.getChildren().setAll(scroll);
        } catch (IllegalArgumentException unavailable) {
            new Alert(Alert.AlertType.WARNING, unavailable.getMessage()).showAndWait();
        } catch (SQLException failure) {
            //leave the current screen in place if the listing could not be loaded
            new Alert(Alert.AlertType.ERROR, "Unable to load this listing. Please try again.").showAndWait();
        }
    }

    //show saved fields as labels so opening details cannot change the listing
    private VBox createReadOnlyListingDetails(Listing listing) {
        VBox details = new VBox(12);
        details.getStyleClass().add("card");
        details.setPadding(new Insets(20));
        Label title = new Label(listing.getTitle());
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        details.getChildren().addAll(title, createListingPhotoGallery(listing.getPhotos()));

        String seller = listing.getSellerFullName();
        if (seller == null || seller.isBlank()) seller = listing.getSellerUsername();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", java.util.Locale.ENGLISH);
        String[][] fields = {
                {"Category", listing.getCategory()},
                {"Condition", listing.getCondition()},
                {"Listing Type", listing.getTypeDisplay()},
                {"Status", listing.getStatus().name().replace('_', ' ')},
                {"Seller", seller},
                {"Seller Username", listing.getSellerUsername()},
                {"Meet-up Location", listing.getMeetupLocation()},
                {"Posted", listing.getPostedAt() == null ? "Unavailable" : listing.getPostedAt().format(dateFormat)}
        };
        for (String[] field : fields) {
            Label label = new Label(field[0] + ": " + field[1]);
            label.setWrapText(true);
            details.getChildren().add(label);
        }
        //only show the terms that apply to this listing type
        details.getChildren().add(new Label(listing.termsDisplay()));
        Label descriptionTitle = new Label("Description");
        descriptionTitle.setStyle("-fx-font-weight: bold;");
        Label description = new Label(listing.getDescription());
        description.setWrapText(true);
        details.getChildren().addAll(descriptionTitle, description);
        if (listing.getStatus() == ListingStatus.REJECTED && listing.getRejectionReason() != null
                && !listing.getRejectionReason().isBlank()) {
            Label reason = new Label("Rejection reason: " + listing.getRejectionReason());
            reason.setWrapText(true);
            details.getChildren().add(reason);
        }
        details.getChildren().add(SellerProfilePane.listingFeedback(listing.getSellerUsername()));
        return details;
    }

    //display all saved photos without upload, remove, or other editing controls
    private VBox createListingPhotoGallery(List<byte[]> photos) {
        VBox gallery = new VBox(10);
        Label heading = new Label("Photos");
        heading.setStyle("-fx-font-weight: bold;");
        gallery.getChildren().add(heading);
        if (photos == null || photos.isEmpty()) {
            gallery.getChildren().add(new Label("No photos for this listing."));
            return gallery;
        }

        ImageView selectedPhoto = new ImageView();
        selectedPhoto.setPreserveRatio(true);
        selectedPhoto.setSmooth(true);
        selectedPhoto.setFitHeight(420);
        //shrink the main image on narrow windows while keeping its original proportions
        selectedPhoto.fitWidthProperty().bind(javafx.beans.binding.Bindings.max(1,
                javafx.beans.binding.Bindings.min(640, gallery.widthProperty())));
        Label position = new Label();
        Label photoError = new Label("This photo could not be displayed.");
        photoError.setWrapText(true);
        photoError.setVisible(false);
        photoError.managedProperty().bind(photoError.visibleProperty());
        FlowPane thumbnails = new FlowPane(8, 8);
        ToggleGroup selection = new ToggleGroup();
        gallery.getChildren().addAll(selectedPhoto, photoError, position, thumbnails);

        for (int i = 0; i < photos.size(); i++) {
            int index = i;
            byte[] bytes = photos.get(i);
            ToggleButton thumbnail = new ToggleButton();
            thumbnail.setToggleGroup(selection);
            thumbnail.setAccessibleText("Show photo " + (i + 1));
            thumbnail.setTooltip(new Tooltip("Photo " + (i + 1)));
            //decode thumbnails at their display size rather than loading six full-size images
            Image small = bytes == null || bytes.length == 0 ? null
                    : new Image(new ByteArrayInputStream(bytes), 96, 72, true, true);
            if (small == null || small.isError()) {
                thumbnail.setText("Photo " + (i + 1) + " unavailable");
            } else {
                ImageView preview = new ImageView(small);
                thumbnail.setGraphic(preview);
            }
            thumbnail.setOnAction(event -> {
                thumbnail.setSelected(true);
                //only decode the selected large image; corrupt data should not hide the details
                Image large = bytes == null || bytes.length == 0 ? null
                        : new Image(new ByteArrayInputStream(bytes), 640, 420, true, true);
                boolean unreadable = large == null || large.isError();
                selectedPhoto.setImage(unreadable ? null : large);
                photoError.setVisible(unreadable);
                position.setText("Photo " + (index + 1) + " of " + photos.size());
            });
            thumbnails.getChildren().add(thumbnail);
            if (i == 0) thumbnail.fire();
        }
        return gallery;
    }

    @FXML
    private void showCreateListing() {
        showListingForm(null);
    }

    protected List<File> choosePhotoFiles(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose up to 6 listing photos");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images (PNG, JPEG, GIF)", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        return chooser.showOpenMultipleDialog(owner);
    }

    //use one form for both actions so validation and photo handling stay consistent
    private void showListingForm(Listing existing) {
        boolean editing = existing != null;
        setActive(editing ? btnListings : btnCreate, editing ? "Edit Listing" : "Create New Listing");
        contentPane.getChildren().clear();

        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));

        Label title = new Label(editing ? "Edit your item" : "Post a new item");
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
            List<File> files = choosePhotoFiles(contentPane.getScene().getWindow());
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
            selectedPhotos.addAll(existing.getPhotos());
            refreshPhotoPreviews(selectedPhotos, photoPreviews, photoNote);
        }

        Button submitBtn = new Button(editing ? "Save Changes" : "Submit for Review");
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
                                    "Enter a positive whole-number lending duration and select a unit.").showAndWait();
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
            ListingService.ListingChangePreview confirmedChange = null;
            if (editing) {
                try {
                    confirmedChange = listingService.previewListingChange(user, existing.getId());
                    if (confirmedChange.listingVersion() != existing.getRowVersion()) {
                        throw new IllegalArgumentException("This listing changed while you were editing. Reopen it to review the latest details.");
                    }
                    Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                            "Save changes to '" + existing.getTitle() + "'? The listing will return to Pending Review."
                                    + affectedProposalWarning(confirmedChange), ButtonType.OK, ButtonType.CANCEL);
                    if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                } catch (IllegalArgumentException unavailable) {
                    new Alert(Alert.AlertType.WARNING, unavailable.getMessage()).showAndWait();
                    return;
                } catch (SQLException failure) {
                    new Alert(Alert.AlertType.ERROR, "Unable to check affected proposals. Please try again.").showAndWait();
                    return;
                }
            }
            //keep submission disabled while the service validates and saves the listing
            submitBtn.setDisable(true);
            try {
                if (editing) listingService.updateListing(user, existing.getId(), listing, confirmedChange);
                else listingService.createListing(user, listing);
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
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn-outline");
        cancel.setOnAction(event -> showMyListings());
        HBox formActions = new HBox(10, submitBtn);
        if (editing) formActions.getChildren().add(cancel);
        listingDetails.getChildren().addAll(
                new Label("Item Name *"), nameField,
                new Label("Category *"), catCombo,
                new Label("Condition *"), condCombo,
                new Label("Description *"), descArea,
                priceInputs, lendingInputs,
                new Label("Meet-up Location *"), meetupCombo,
                new Label("Photos (optional, up to 6)"), photoButtons, photoNote, photoPreviews, formActions);
        form.getChildren().addAll(title, new Label("Listing Type *"), typeCombo, listingDetails);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private String affectedProposalWarning(ListingService.ListingChangePreview preview) {
        int count = preview.affectedVersions().size();
        return count == 0 ? "" : "\n\nThis will cancel " + count
                + " pending or countered proposal(s) and notify the affected buyers.";
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
        User seller = SessionManager.getCurrentUser();
        if (seller == null) {
            SceneManager.showSellerLogin();
            return;
        }

        VBox loading = new VBox(12, new Label("Loading seller statistics..."), new ProgressIndicator());
        loading.setAlignment(Pos.CENTER);
        contentPane.getChildren().setAll(loading);
        Task<SellerStatsRepository.Stats> task = new Task<>() {
            @Override
            protected SellerStatsRepository.Stats call() throws SQLException {
                return listingService.getSellerStats(seller);
            }
        };
        task.setOnSucceeded(event -> {
            if (contentPane.getChildren().contains(loading) && SessionManager.getCurrentUser() == seller) {
                renderStats(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            if (contentPane.getChildren().contains(loading) && SessionManager.getCurrentUser() == seller) {
                Button retry = new Button("Retry");
                retry.getStyleClass().add("btn-outline");
                retry.setOnAction(click -> showStats());
                loading.getChildren().setAll(new Label("Unable to load seller statistics."), retry);
            }
        });
        Thread worker = new Thread(task, "seller-stats-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderStats(SellerStatsRepository.Stats stats) {
        VBox root = new VBox(20);
        root.setPadding(new Insets(4));

        FlowPane metrics = new FlowPane(16, 16);
        metrics.getChildren().addAll(
                metricCard("Listing Views", Long.toString(stats.views()), "#1565c0"),
                metricCard("Offers Received", Long.toString(stats.offersReceived()), "#003974"),
                metricCard("Completed Deals", Long.toString(stats.completedDeals()), "#2e7d32"),
                metricCard("Average Rating", stats.averageRating() == null ? "No ratings"
                        : RatingDisplay.average(stats.averageRating()), "#A68137")
        );
        root.getChildren().add(metrics);

        if (stats.listingsByCategory().isEmpty()) {
            root.getChildren().add(new Label("No listings to chart."));
        }
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> barChart = new BarChart<>(xAxis, yAxis);
        barChart.setTitle("Listings by Category");
        barChart.setLegendVisible(false);
        barChart.setPrefHeight(280);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        stats.listingsByCategory().forEach((category, count) ->
                series.getData().add(new XYChart.Data<>(category, count)));
        barChart.getData().add(series);

        PieChart pie = new PieChart();
        pie.setTitle("Listing Status Distribution");
        pie.setPrefHeight(280);
        stats.listingsByStatus().forEach((status, count) ->
                pie.getData().add(new PieChart.Data(status.name().replace('_', ' '), count)));

        HBox charts = new HBox(20, barChart, pie);
        root.getChildren().add(charts);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().setAll(scroll);
    }

    private VBox metricCard(String label, String value, String color) {
        VBox card = new VBox(4);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setMinWidth(140);
        card.setPadding(new Insets(16));
        Label val = new Label(value);
        val.setMinWidth(Region.USE_PREF_SIZE);
        val.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        card.getChildren().addAll(val, lbl);
        return card;
    }

    @FXML
    private void showReviews() {
        setActive(btnReviews, "Reviews & Feedback");
        User seller = SessionManager.getCurrentUser();
        if (seller == null) {
            SceneManager.showSellerLogin();
            return;
        }
        SellerReviewsPane reviews = new SellerReviewsPane(new ReviewService(), seller);
        contentPane.getChildren().setAll(reviews);
        reviews.refreshReviews();
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notifications");
        User recipient = SessionManager.getCurrentUser();
        if (recipient == null) {
            SceneManager.showSellerLogin();
            return;
        }
        if (notificationBoard == null) {
            notificationBoard = new NotificationBoard(
                    new NotificationService(userRepository, notificationRepository, UserRole.SELLER),
                    unreadNotificationBadge::refresh);
        }
        contentPane.getChildren().setAll(notificationBoard);
        notificationBoard.show(recipient);
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
            unreadNotificationBadge.stop();
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
                notifyAccountChange("Seller Profile Updated",
                        "@" + sessionUser.getUsername() + " updated their seller profile.");
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
        form.getChildren().add(SellerProfilePane.profileButton(user.getUsername()));
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
