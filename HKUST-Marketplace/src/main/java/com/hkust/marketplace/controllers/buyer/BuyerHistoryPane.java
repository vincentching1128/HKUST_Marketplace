package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.controllers.RatingDisplay;

import com.hkust.marketplace.models.DealHistoryEntry;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.BuyerDealService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

final class BuyerHistoryPane extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private final User buyer;
    private final BuyerDealService service;
    private final DatePicker from = datePicker("From");
    private final DatePicker to = datePicker("To");
    private final TextField searchField = new TextField();
    private final ComboBox<String> category = new ComboBox<>();
    private final TableView<DealHistoryEntry> table = new TableView<>();
    private final VBox review = new VBox(6);
    private final Label status = new Label();
    private List<DealHistoryEntry> history = List.of();
    private String requestedDeal;
    private boolean loading;
    private boolean editing;

    BuyerHistoryPane(User buyer, BuyerDealService service, String requestedDeal) {
        super(10);
        this.buyer = buyer;
        this.service = service;
        this.requestedDeal = requestedDeal;
        searchField.setPromptText("Search item title or seller...");
        searchField.setAccessibleText("Search by item title, seller name, or seller username");
        searchField.setPrefWidth(225);
        category.setPromptText("All categories");
        Button reset = new Button("Clear filters");
        reset.getStyleClass().addAll("btn-outline", "btn-sm");
        reset.setOnAction(event -> {
            from.setValue(null);
            to.setValue(null);
            searchField.clear();
            category.setValue(null);
        });
        HBox filters = new HBox(8, from, new Label("to"), to, category, searchField, reset);
        filters.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(filters);
        var title = column("Item Title", DealHistoryEntry::title);
        title.setPrefWidth(135);
        title.setCellFactory(ignored -> cellWithTooltip());
        var seller = column("Seller", DealHistoryEntry::sellerDisplay);
        seller.setPrefWidth(125);
        seller.setCellFactory(ignored -> cellWithTooltip());
        column("Deal Date", row -> row.closedAt() == null ? "Unknown" : DATE.format(row.closedAt())).setPrefWidth(145);
        column("Deal Type", BuyerHistoryPane::dealType);
        var terms = column("Agreed terms", BuyerHistoryPane::termsText);
        terms.setPrefWidth(185);
        terms.setCellFactory(ignored -> cellWithTooltip());
        column("Outcome", row -> row.outcome().getDisplayName());
        table.getStyleClass().add("deal-history-table");
        table.setFixedCellSize(30);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No finished deals match these filters."));
        table.setRowFactory(ignored -> {
            TableRow<DealHistoryEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (row.isEmpty()) table.getSelectionModel().clearSelection();
            });
            return row;
        });
        VBox details = createDetailStrip();
        setOnMouseClicked(event -> {
            Object target = event.getTarget();
            if (target == this || target == filters || target == details || target == review) {
                table.getSelectionModel().clearSelection();
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> showReview(row));
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(status, table, details, review);
        searchField.textProperty().addListener((obs, old, value) -> applyFilters());
        category.valueProperty().addListener((obs, old, value) -> applyFilters());
        from.valueProperty().addListener((obs, old, value) -> applyFilters());
        to.valueProperty().addListener((obs, old, value) -> applyFilters());

        // A loan may close in the scheduler or in another app while history is open.
        Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(4), event -> reload()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) refresh.stop();
            else {
                reload();
                refresh.playFromStart();
            }
        });
    }

    private static DatePicker datePicker(String prompt) {
        DatePicker picker = new DatePicker();
        picker.setPromptText(prompt);
        picker.setAccessibleText(prompt + " deal date");
        picker.setPrefWidth(135);
        picker.setEditable(false);
        return picker;
    }

    private TableColumn<DealHistoryEntry, String> column(String title, Function<DealHistoryEntry, String> value) {
        TableColumn<DealHistoryEntry, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
        return column;
    }

    private VBox createDetailStrip() {
        var selected = table.getSelectionModel().selectedItemProperty();
        Label heading = new Label();
        heading.setStyle("-fx-font-weight: bold;");
        heading.setWrapText(true);
        heading.textProperty().bind(selected.map(row -> row.title() + " · " + row.category() + " · "
                + dealType(row) + " (" + row.outcome().getDisplayName() + ")"));
        Label summary = new Label();
        summary.setStyle("-fx-text-fill: #5a5a7a;");
        summary.setWrapText(true);
        summary.textProperty().bind(selected.map(row -> "Seller: " + row.sellerDisplay()
                + " · Date: " + (row.closedAt() == null ? "Unknown" : DATE.format(row.closedAt()))
                + " · Agreed terms: " + termsText(row)));
        VBox details = new VBox(4, heading, summary);
        details.getStyleClass().add("card");
        details.setStyle("-fx-padding: 8 12;");
        var hasSelection = selected.isNotNull();
        details.visibleProperty().bind(hasSelection);
        details.managedProperty().bind(hasSelection);
        return details;
    }

    private static TableCell<DealHistoryEntry, String> cellWithTooltip() {
        return new TableCell<>() {
            private final Tooltip fullText = new Tooltip();

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                String text = empty ? null : value;
                setText(text == null ? null : text.replace('\r', ' ').replace('\n', ' '));
                fullText.setText(text);
                setTooltip(text == null ? null : fullText);
            }
        };
    }

    private static String dealType(DealHistoryEntry row) {
        return row.type().charAt(0) + row.type().substring(1).toLowerCase(Locale.ROOT);
    }

    private static String termsText(DealHistoryEntry row) {
        if ("RECEIVED".equals(row.type())) return "Free";
        var terms = row.agreedTerms();
        if (terms.price() != null) return String.format(Locale.ENGLISH, "HK$ %.2f", terms.price());
        if (terms.start() != null && terms.due() != null) {
            return DATE.format(terms.start()) + " – " + DATE.format(terms.due());
        }
        return "Terms unavailable";
    }

    private void reload() {
        if (loading || editing || getScene() == null) return;
        loading = true;
        Task<List<DealHistoryEntry>> task = new Task<>() {
            @Override protected List<DealHistoryEntry> call() throws SQLException { return service.getHistory(buyer); }
        };
        task.setOnFailed(event -> {
            loading = false;
            if (getScene() != null) status.setText("Unable to refresh deal history. Retrying…");
        });
        task.setOnSucceeded(event -> {
            loading = false;
            if (getScene() == null) return;
            status.setText("");
            List<DealHistoryEntry> fresh = task.getValue();
            if (!history.equals(fresh)) {
                history = fresh;
                String selected = category.getValue();
                category.getItems().setAll(history.stream().map(DealHistoryEntry::category).distinct().sorted().toList());
                category.setValue(category.getItems().contains(selected) ? selected : null);
            }
            applyFilters();
            if (requestedDeal != null) {
                String id = requestedDeal;
                requestedDeal = null;
                history.stream().filter(row -> row.dealId().equals(id)).findFirst().ifPresent(row -> {
                    table.getSelectionModel().select(row);
                    table.scrollTo(row);
                    if (row.canReview()) writeReview(row);
                });
            }
        });
        Thread worker = new Thread(task, "buyer-history");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyFilters() {
        LocalDate start = from.getValue(), end = to.getValue();
        boolean invalid = start != null && end != null && start.isAfter(end);
        String search = searchField.getText().strip().toLowerCase(Locale.ROOT);
        List<DealHistoryEntry> matches = history.stream().filter(row -> {
            LocalDate date = row.closedAt() == null ? null : row.closedAt().toLocalDate();
            return !invalid && (start == null || date != null && !date.isBefore(start))
                    && (end == null || date != null && !date.isAfter(end))
                    && (category.getValue() == null || category.getValue().equals(row.category()))
                    && (row.title().toLowerCase(Locale.ROOT).contains(search)
                        || row.sellerDisplay().toLowerCase(Locale.ROOT).contains(search));
        }).toList();
        table.setPlaceholder(new Label(invalid ? "Choose an end date on or after the start date."
                : "No finished deals match these filters."));
        if (table.getItems().equals(matches)) return;
        DealHistoryEntry previous = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(matches);
        if (previous != null) matches.stream().filter(row -> Objects.equals(row.dealId(), previous.dealId()))
                .findFirst().ifPresent(table.getSelectionModel()::select);
        if (table.getSelectionModel().isEmpty()) table.getSelectionModel().selectFirst();
    }

    private void showReview(DealHistoryEntry row) {
        review.getChildren().clear();
        if (row == null) return;
        if (row.canReview()) {
            Button rate = new Button("Rate Seller");
            rate.getStyleClass().add("btn-primary");
            rate.setOnAction(event -> writeReview(row));
            review.getChildren().add(rate);
        } else if (row.rating() != null) {
            Label heading = new Label("Your review" + (row.hidden() ? " · Hidden from public view" : ""));
            Label text = new Label(row.comment()
                    + (row.reply() == null ? "" : "\nSeller reply: " + row.reply()));
            text.setWrapText(true);
            ScrollPane scroll = new ScrollPane(new VBox(4, heading, RatingDisplay.stars(row.rating()), text));
            scroll.setFitToWidth(true);
            scroll.setMaxHeight(135);
            review.getChildren().add(scroll);
        }
    }

    private void writeReview(DealHistoryEntry row) {
        editing = true;
        try {
            new BuyerReviewDialog(buyer, row, getScene().getWindow()).showAndWait();
        } finally {
            editing = false;
            reload();
        }
    }
}
