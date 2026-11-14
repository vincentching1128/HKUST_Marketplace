package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.BuyerListingService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Buyer-only listing screens. Database work runs off the JavaFX application thread. */
public class BuyerListingsPane extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);
    private final BuyerListingService service;
    private final Supplier<User> currentUser;
    private final Consumer<String> setTitle;
    private final TextField search = new TextField();
    private final TextField minimum = new TextField();
    private final TextField maximum = new TextField();
    private final ComboBox<String> category = new ComboBox<>();
    private final ComboBox<String> type = new ComboBox<>();
    private final ComboBox<String> condition = new ComboBox<>();
    private final ComboBox<String> sort = new ComboBox<>();
    private final VBox results = new VBox(12);
    private final Label validation = new Label();
    private final VBox browse = new VBox(14);
    private List<Listing> listings = List.of();
    private boolean updatingControls;
    private boolean loaded;
    private long requestVersion;

    public BuyerListingsPane(BuyerListingService service, Supplier<User> currentUser, Consumer<String> setTitle) {
        this.service = service;
        this.currentUser = currentUser;
        this.setTitle = setTitle;
        setSpacing(14);
        setPadding(new Insets(4));
        buildBrowse();
        // Ignore late query results after the buyer leaves this part of the dashboard.
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) requestVersion++;
        });
    }

    private void buildBrowse() {
        search.setPromptText("Search title or description...");
        search.setPrefWidth(260);
        search.setAccessibleText("Search listings by title or description");
        minimum.setPromptText("Min HK$");
        maximum.setPromptText("Max HK$");
        minimum.setPrefWidth(100);
        maximum.setPrefWidth(100);
        minimum.setAccessibleText("Minimum sale price");
        maximum.setAccessibleText("Maximum sale price");
        category.getItems().setAll("All Categories");
        type.getItems().setAll("All Types", "Sell", "Lend", "Giveaway");
        condition.getItems().setAll("All Conditions", "New", "Like New", "Used", "For Parts");
        sort.getItems().setAll("Newest", "Price: low to high", "Price: high to low");
        for (ComboBox<String> combo : List.of(category, type, condition, sort)) combo.getSelectionModel().selectFirst();
        category.setAccessibleText("Category");
        type.setAccessibleText("Listing type");
        condition.setAccessibleText("Condition");
        sort.setAccessibleText("Sort listings");
        Button apply = button("Search", "btn-primary", this::applyFilters);
        Button reset = button("Reset", "btn-outline", this::resetFilters);
        Button refresh = button("Refresh", "btn-outline", this::showBrowse);
        FlowPane firstRow = new FlowPane(10, 10, search, apply, refresh, reset);
        FlowPane filters = new FlowPane(10, 10, new Label("Category:"), category,
                new Label("Type:"), type, new Label("Condition:"), condition);
        FlowPane prices = new FlowPane(10, 10, new Label("Sale price:"), minimum, maximum,
                new Label("Sort:"), sort);
        Label hint = new Label("Price ranges apply to sale listings. Unpriced items appear last when sorting by price.");
        hint.setWrapText(true);
        validation.setStyle("-fx-text-fill: #b3261e;");
        validation.setWrapText(true);
        validation.setVisible(false);
        validation.managedProperty().bind(validation.visibleProperty());
        browse.getChildren().addAll(firstRow, filters, prices, hint, validation, results);
        search.setOnAction(event -> applyFilters());
        minimum.setOnAction(event -> applyFilters());
        maximum.setOnAction(event -> applyFilters());
        for (ComboBox<String> combo : List.of(category, type, condition, sort)) {
            combo.setOnAction(event -> { if (!updatingControls) applyFilters(); });
        }
    }

    public void showBrowse() {
        setTitle.accept("Browse Marketplace");
        showContent(browse);
        loaded = false;
        results.getChildren().setAll(new Label("Loading listings..."), new ProgressIndicator());
        User user = currentUser.get();
        runQuery(() -> service.getLiveListings(user), saved -> {
            listings = saved;
            loaded = true;
            String previousCategory = category.getValue();
            TreeSet<String> categories = new TreeSet<>();
            for (Listing listing : saved) categories.add(listing.getCategory());
            // Keep a selected category even when its last listing was removed.
            if (previousCategory != null && !previousCategory.equals("All Categories")) categories.add(previousCategory);
            updatingControls = true;
            category.getItems().setAll("All Categories");
            category.getItems().addAll(categories);
            category.setValue(previousCategory == null ? "All Categories" : previousCategory);
            updatingControls = false;
            applyFilters();
        }, failure -> {
            loaded = false;
            results.getChildren().setAll(wrapped(message(failure, "Unable to load listings. Please try again.")),
                    button("Retry", "btn-outline", this::showBrowse));
        });
    }

    private void applyFilters() {
        if (!loaded) return;
        try {
            BigDecimal min = parsePrice(minimum.getText());
            BigDecimal max = parsePrice(maximum.getText());
            ListingType selectedType = switch (type.getValue()) {
                case "Sell" -> ListingType.SELL;
                case "Lend" -> ListingType.LEND;
                case "Giveaway" -> ListingType.GIVEAWAY;
                default -> null;
            };
            BuyerListingService.SortOrder order = switch (sort.getSelectionModel().getSelectedIndex()) {
                case 1 -> BuyerListingService.SortOrder.PRICE_ASC;
                case 2 -> BuyerListingService.SortOrder.PRICE_DESC;
                default -> BuyerListingService.SortOrder.NEWEST;
            };
            List<Listing> matches = service.filterListings(listings, search.getText(),
                    category.getSelectionModel().getSelectedIndex() == 0 ? null : category.getValue(), selectedType,
                    condition.getSelectionModel().getSelectedIndex() == 0 ? null : condition.getValue(), min, max, order);
            validation.setVisible(false);
            results.getChildren().setAll(new Label(matches.size() + (matches.size() == 1 ? " listing" : " listings")));
            if (matches.isEmpty()) {
                results.getChildren().add(new Label(listings.isEmpty()
                        ? "No live listings at the moment." : "No listings match these filters."));
                return;
            }
            FlowPane grid = new FlowPane(16, 16);
            for (Listing listing : matches) grid.getChildren().add(createCard(listing));
            results.getChildren().add(grid);
        } catch (IllegalArgumentException invalid) {
            validation.setText(invalid.getMessage());
            validation.setVisible(true);
            results.getChildren().setAll(new Label("Correct the filters to view listings."));
        }
    }

    private BigDecimal parsePrice(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            BigDecimal value = new BigDecimal(input.strip());
            if (value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0
                    || value.stripTrailingZeros().scale() > 2) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter prices from 0 to 99,999,999.99 with at most two decimal places.");
        }
    }

    private void resetFilters() {
        updatingControls = true;
        search.clear();
        minimum.clear();
        maximum.clear();
        for (ComboBox<String> combo : List.of(category, type, condition, sort)) combo.getSelectionModel().selectFirst();
        updatingControls = false;
        applyFilters();
    }

    private VBox createCard(Listing listing) {
        VBox card = new VBox(8);
        card.getStyleClass().add("listing-card");
        card.setPrefWidth(260);
        card.setMaxWidth(260);
        byte[] first = listing.getPhotos().isEmpty() ? null : listing.getPhotos().get(0);
        Image image = decode(first, 230, 150);
        StackPane thumbnail = new StackPane(image == null ? new Label("No photo available") : new ImageView(image));
        thumbnail.setMinHeight(150);
        Label title = wrapped(listing.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        card.getChildren().addAll(thumbnail, title, wrapped(listing.getTypeDisplay() + " · " + terms(listing)),
                wrapped(listing.getCategory() + " · " + listing.getCondition()),
                wrapped("Seller: " + sellerName(listing)), wrapped("Meet-up: " + listing.getMeetupLocation()),
                wrapped("Posted: " + postedDate(listing)), statusLabel(listing),
                button("View Details", "btn-primary", () -> showListingDetails(listing.getId())));
        return card;
    }

    /** Entry point for future real My Deals rows, using the saved listing ID. */
    public void showListingDetails(String listingId) {
        User user = currentUser.get();
        showContent(new VBox(12, new Label("Loading listing details..."), new ProgressIndicator()));
        runQuery(() -> service.getListing(user, listingId), listing -> {
            setTitle.accept("Listing Details");
            VBox root = new VBox(14, button("Back to Browse", "btn-outline", this::showBrowse), createDetails(listing));
            showContent(root);
        }, failure -> {
            showBrowse();
            new Alert(Alert.AlertType.WARNING,
                    message(failure, "Unable to load this listing. Please try again.")).showAndWait();
        });
    }

    private VBox createDetails(Listing listing) {
        VBox details = new VBox(12);
        details.getStyleClass().add("card");
        details.setPadding(new Insets(20));
        Label title = wrapped(listing.getTitle());
        title.getStyleClass().add("section-title");
        details.getChildren().addAll(title, gallery(listing.getPhotos()), statusLabel(listing));
        String[][] fields = {
                {"Seller", sellerName(listing)}, {"Seller Username", listing.getSellerUsername()},
                {"Seller Rating", "Ratings are not available yet."},
                {"Category", listing.getCategory()}, {"Condition", listing.getCondition()},
                {"Listing Type", listing.getTypeDisplay()}, {"Terms", terms(listing)},
                {"Meet-up Location", listing.getMeetupLocation()}, {"Posted", postedDate(listing)},
                {"Views", String.valueOf(listing.getViewCount())}
        };
        for (String[] field : fields) details.getChildren().add(wrapped(field[0] + ": " + field[1]));
        Label descriptionTitle = new Label("Description");
        descriptionTitle.setStyle("-fx-font-weight: bold;");
        details.getChildren().addAll(descriptionTitle, wrapped(listing.getDescription()));
        String actionText = switch (listing.getType()) {
            case SELL -> "Make Offer";
            case LEND -> "Request Borrow";
            case GIVEAWAY -> "Claim Item";
        };
        Button action = new Button(actionText);
        action.getStyleClass().add("btn-primary");
        action.setDisable(true);
        Button chat = new Button("Chat");
        chat.getStyleClass().add("btn-outline");
        chat.setDisable(true);
        Label unavailable = wrapped(listing.getStatus() == ListingStatus.LIVE
                ? "Offers, borrow requests, claims and chat are not available yet."
                : "This item is currently unavailable. Chat is not available yet.");
        Button report = button("Report Listing", "btn-danger", () -> reportListing(listing));
        details.getChildren().addAll(new FlowPane(10, 10, action, chat, report), unavailable,
                button("Refresh Details", "btn-outline", () -> showListingDetails(listing.getId())));
        return details;
    }

    private void reportListing(Listing listing) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Report Listing");
        dialog.setHeaderText("Report: " + listing.getTitle());
        TextArea reason = new TextArea();
        reason.setPromptText("Describe the problem with this listing (1–2,000 characters).");
        reason.setWrapText(true);
        reason.setPrefRowCount(5);
        Label error = wrapped("A reason is required (maximum 2,000 characters).");
        VBox content = new VBox(10, reason, error);
        dialog.getDialogPane().setContent(content);
        ButtonType submit = new ButtonType("Submit Report", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL);
        Node submitButton = dialog.getDialogPane().lookupButton(submit);
        submitButton.setDisable(true);
        reason.textProperty().addListener((observable, old, text) -> {
            String trimmed = text.strip();
            boolean invalid = trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 2000;
            submitButton.setDisable(invalid);
            error.setVisible(invalid);
        });
        dialog.setResultConverter(selected -> selected == submit ? reason.getText().strip() : null);
        dialog.showAndWait().ifPresent(text -> {
            User user = currentUser.get();
            // Replace the form while saving so double clicks cannot submit the same report twice.
            showContent(new VBox(12, new Label("Submitting report..."), new ProgressIndicator()));
            runQuery(() -> { service.reportListing(user, listing.getId(), text); return listing; }, saved -> {
                showContent(new VBox(14, button("Back to Browse", "btn-outline", this::showBrowse), createDetails(saved)));
                new Alert(Alert.AlertType.INFORMATION, "Your listing report has been saved.").showAndWait();
            }, failure -> {
                showContent(new VBox(14, button("Back to Browse", "btn-outline", this::showBrowse), createDetails(listing)));
                new Alert(Alert.AlertType.ERROR, message(failure, "Unable to save your report. Please try again.")).showAndWait();
            });
        });
    }

    // Adapted from the seller's read-only gallery; no seller code needs to change.
    private VBox gallery(List<byte[]> photos) {
        VBox gallery = new VBox(10, new Label("Photos"));
        if (photos == null || photos.isEmpty()) {
            gallery.getChildren().add(new Label("No photos for this listing."));
            return gallery;
        }
        ImageView selected = new ImageView();
        selected.setPreserveRatio(true);
        selected.setSmooth(true);
        selected.setFitHeight(420);
        selected.visibleProperty().bind(selected.imageProperty().isNotNull());
        selected.managedProperty().bind(selected.visibleProperty());
        selected.fitWidthProperty().bind(javafx.beans.binding.Bindings.max(1,
                javafx.beans.binding.Bindings.min(640, gallery.widthProperty())));
        Label position = new Label();
        Label error = new Label("This photo could not be displayed.");
        error.managedProperty().bind(error.visibleProperty());
        FlowPane thumbnails = new FlowPane(8, 8);
        ToggleGroup group = new ToggleGroup();
        gallery.getChildren().addAll(selected, error, position, thumbnails);
        int count = Math.min(6, photos.size());
        for (int i = 0; i < count; i++) {
            int index = i;
            byte[] bytes = photos.get(i);
            ToggleButton thumbnail = new ToggleButton();
            thumbnail.setToggleGroup(group);
            thumbnail.setAccessibleText("Show photo " + (i + 1));
            thumbnail.setTooltip(new Tooltip("Photo " + (i + 1)));
            Image small = decode(bytes, 96, 72);
            if (small == null) thumbnail.setText("Photo " + (i + 1) + " unavailable");
            else thumbnail.setGraphic(new ImageView(small));
            thumbnail.setOnAction(event -> {
                thumbnail.setSelected(true);
                Image large = decode(bytes, 640, 420);
                selected.setImage(large);
                error.setVisible(large == null);
                position.setText("Photo " + (index + 1) + " of " + count);
            });
            thumbnails.getChildren().add(thumbnail);
            if (i == 0) thumbnail.fire();
        }
        return gallery;
    }

    private Image decode(byte[] bytes, int width, int height) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Image image = new Image(new ByteArrayInputStream(bytes), width, height, true, true);
            return image.isError() ? null : image;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private Label statusLabel(Listing listing) {
        Label label = new Label("Availability: " + switch (listing.getStatus()) {
            case LIVE -> "Available";
            case RESERVED -> "Reserved";
            case SOLD -> "Sold";
            case LENT -> "On loan";
            case GIVEN -> "Given away";
            default -> "Unavailable";
        });
        label.getStyleClass().addAll("badge", listing.getStatus() == ListingStatus.LIVE ? "badge-live" : "badge-sold");
        return label;
    }

    private String sellerName(Listing listing) {
        String name = listing.getSellerFullName();
        return name == null || name.isBlank() ? listing.getSellerUsername() : name;
    }

    private String terms(Listing listing) {
        return switch (listing.getType()) {
            case SELL -> String.format(Locale.ENGLISH, "HK$ %.2f", listing.getPrice());
            case LEND -> "Lending duration: " + listing.getLendingDurationDisplay();
            case GIVEAWAY -> "Free";
        };
    }

    private String postedDate(Listing listing) {
        return listing.getPostedAt() == null ? "Unavailable" : listing.getPostedAt().format(DATE);
    }

    private Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    private Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void showContent(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().setAll(scroll);
    }

    private String message(Throwable failure, String fallback) {
        return failure instanceof IllegalArgumentException ? failure.getMessage() : fallback;
    }

    private <T> void runQuery(Callable<T> query, Consumer<T> success, Consumer<Throwable> failure) {
        long version = ++requestVersion;
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return query.call(); }
        };
        task.setOnSucceeded(event -> { if (version == requestVersion) success.accept(task.getValue()); });
        task.setOnFailed(event -> { if (version == requestVersion) failure.accept(task.getException()); });
        Thread worker = new Thread(task, "buyer-listing-query");
        worker.setDaemon(true);
        worker.start();
    }
}
