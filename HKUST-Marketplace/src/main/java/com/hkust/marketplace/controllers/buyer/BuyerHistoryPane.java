package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.DealHistoryEntry;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.BuyerDealService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
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
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH);
    private final User buyer;
    private final BuyerDealService service;
    private final DatePicker from = datePicker("From");
    private final DatePicker to = datePicker("To");
    private final TextField seller = new TextField();
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
        seller.setPromptText("Seller name or username");
        category.setPromptText("All categories");
        Button reset = new Button("Clear filters");
        reset.setOnAction(event -> {
            from.setValue(null);
            to.setValue(null);
            seller.clear();
            category.setValue(null);
        });
        getChildren().add(new FlowPane(8, 8, from, to, category, seller, reset));
        column("Item Title", DealHistoryEntry::title);
        column("Seller", DealHistoryEntry::sellerDisplay);
        column("Deal Date", row -> row.closedAt() == null ? "Unknown" : DATE.format(row.closedAt()));
        column("Deal Type", row -> row.type().charAt(0) + row.type().substring(1).toLowerCase(Locale.ROOT));
        column("Outcome", row -> row.outcome().getDisplayName());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No finished deals match these filters."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> showReview(row));
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(status, table, review);
        seller.textProperty().addListener((obs, old, value) -> applyFilters());
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
        picker.setEditable(false);
        return picker;
    }

    private void column(String title, Function<DealHistoryEntry, String> value) {
        TableColumn<DealHistoryEntry, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
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
        String search = seller.getText().strip().toLowerCase(Locale.ROOT);
        List<DealHistoryEntry> matches = history.stream().filter(row -> {
            LocalDate date = row.closedAt() == null ? null : row.closedAt().toLocalDate();
            return !invalid && (start == null || date != null && !date.isBefore(start))
                    && (end == null || date != null && !date.isAfter(end))
                    && (category.getValue() == null || category.getValue().equals(row.category()))
                    && row.sellerDisplay().toLowerCase(Locale.ROOT).contains(search);
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
            rate.setOnAction(event -> writeReview(row));
            review.getChildren().add(rate);
        } else if (row.rating() != null) {
            Label text = new Label("Your review · " + row.rating() + "/5"
                    + (row.hidden() ? " · Hidden from public view" : "") + "\n" + row.comment()
                    + (row.reply() == null ? "" : "\nSeller reply: " + row.reply()));
            text.setWrapText(true);
            ScrollPane scroll = new ScrollPane(text);
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
