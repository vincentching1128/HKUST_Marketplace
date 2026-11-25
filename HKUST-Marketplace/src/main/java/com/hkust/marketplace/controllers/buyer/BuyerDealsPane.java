package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.services.BuyerDealService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Real buyer proposals and deals, including terminal records. */
public class BuyerDealsPane extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private final BuyerDealService service;
    private final Supplier<User> user;
    private final Consumer<String> openListing;
    private final Consumer<String> openChat;
    private final Consumer<String> openHistory;
    private final VBox rows = new VBox(12);
    private final Label feedback = new Label();
    private final Button refresh = new Button("Refresh");
    private boolean active = true;
    private long request;

    public BuyerDealsPane(BuyerDealService service, Supplier<User> user,
                          Consumer<String> openListing, Consumer<String> openChat, Consumer<String> openHistory) {
        super(12);
        this.service = Objects.requireNonNull(service);
        this.user = Objects.requireNonNull(user);
        this.openListing = Objects.requireNonNull(openListing);
        this.openChat = Objects.requireNonNull(openChat);
        this.openHistory = Objects.requireNonNull(openHistory);
        setPadding(new Insets(4));
        refresh.getStyleClass().add("btn-outline");
        refresh.setOnAction(event -> refresh());
        feedback.setWrapText(true);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, spacer, refresh);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(toolbar, feedback, scroll);
    }

    public void deactivate() { active = false; request++; }

    public void refresh() {
        if (!active) return;
        User buyer = user.get();
        long token = ++request;
        refresh.setDisable(true);
        rows.getChildren().setAll(new Label("Loading your deals..."));
        Task<List<Deal>> task = new Task<>() {
            @Override protected List<Deal> call() throws SQLException {
                service.returnDueBorrowings();
                return service.getMyDeals(buyer);
            }
        };
        task.setOnSucceeded(event -> {
            if (!current(token, buyer)) return;
            refresh.setDisable(false);
            rows.getChildren().clear();
            if (task.getValue().isEmpty()) rows.getChildren().add(new Label("You have no proposals or deals yet."));
            for (Deal deal : task.getValue()) rows.getChildren().add(card(deal));
        });
        task.setOnFailed(event -> {
            if (!current(token, buyer)) return;
            refresh.setDisable(false);
            rows.getChildren().setAll(new Label("Unable to load deals. Refresh to try again."));
            feedback.setText(error(task.getException(), "Unable to load your deals."));
        });
        start(task, "buyer-deals-load");
    }

    private HBox card(Deal deal) {
        Label title = new Label(deal.getListingTitle());
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label type = badge(switch (deal.getDealType()) {
            case SELL -> "Sell";
            case LEND -> "Lend";
            case GIVEAWAY -> "Give Away";
        }, "badge-deal-type");
        Label status = badge(deal.getStatus().getDisplayName(), switch (deal.getStatus()) {
            case COMPLETED, RETURNED -> "badge-live";
            case PENDING, COUNTERED -> "badge-deal-pending";
            case ACCEPTED -> "badge-sold";
            case DECLINED, CANCELLED -> "badge-rejected";
        });
        HBox heading = new HBox(8, title, type, status);
        heading.setAlignment(Pos.CENTER_LEFT);
        String seller = deal.getSellerUsername() == null ? "Deleted seller" : "@" + deal.getSellerUsername();
        Label metadata = new Label("Seller: " + seller + " · " + format(deal.getCreatedAt()));
        metadata.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        metadata.setWrapText(true);
        Label terms = new Label(termsSummary(deal));
        terms.setStyle("-fx-text-fill: #003974; -fx-font-weight: bold;");
        terms.setWrapText(true);
        VBox details = new VBox(4, heading, metadata, terms);
        HBox.setHgrow(details, Priority.ALWAYS);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().add(button("View Listing", "btn-outline", () -> openListing.accept(deal.getListingId())));
        actions.getChildren().add(button("Open Chat", "btn-outline", () -> openChat.accept(deal.getId())));
        if (deal.getStatus() == DealStatus.COUNTERED) {
            actions.getChildren().add(button("Accept Counter", "btn-primary",
                    () -> act(deal, service::acceptCounter, "Counteroffer accepted.", this::refresh)));
            actions.getChildren().add(button("Decline Counter", "btn-outline",
                    () -> act(deal, service::declineCounter, "Counteroffer declined.", this::refresh)));
        }
        if (deal.getStatus() == DealStatus.ACCEPTED) {
            if (deal.getDealType() == ListingType.LEND) {
                Button giveBack = button("Return Item", "btn-primary", () -> act(deal, service::returnBorrowed,
                        "Borrowing returned.", () -> openHistory.accept(deal.getId())));
                giveBack.setDisable(deal.getBorrowStart() == null || LocalDateTime.now().isBefore(deal.getBorrowStart()));
                actions.getChildren().add(giveBack);
            } else {
                actions.getChildren().add(button("Mark Complete", "btn-primary", () -> act(deal, service::complete,
                        "Deal completed.", () -> openHistory.accept(deal.getId()))));
            }
        }
        if (deal.getStatus() == DealStatus.RETURNED || deal.getStatus() == DealStatus.COMPLETED) {
            actions.getChildren().add(button("Review in Deal History", "btn-primary", () -> openHistory.accept(deal.getId())));
        }
        HBox card = new HBox(12, details, actions);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 14;");
        return card;
    }

    private static Label badge(String text, String style) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("badge", style);
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    private static String termsSummary(Deal deal) {
        if (deal.getDealType() == ListingType.GIVEAWAY) return "Free";
        DealStatus status = deal.getStatus();
        boolean agreed = status == DealStatus.ACCEPTED || status == DealStatus.COMPLETED || status == DealStatus.RETURNED;
        if (deal.getDealType() == ListingType.SELL) {
            String offer = (agreed ? "Agreed: " : "Your offer: ") + price(deal.getOfferPrice());
            return status == DealStatus.COUNTERED
                    ? offer + " · Seller counteroffer: " + price(deal.getCounterOfferPrice()) : offer;
        }

        String start = format(deal.getBorrowStart());
        String end = format(deal.getBorrowEnd());
        if (status == DealStatus.ACCEPTED) return "Borrowing: " + start + " · Due: " + end;
        String period = (agreed ? "Borrowing: " : "Requested period: ") + start + " – " + end;
        return status == DealStatus.COUNTERED ? period + " · Seller proposed: "
                + format(deal.getCounterBorrowStart()) + " – " + format(deal.getCounterBorrowEnd()) : period;
    }

    private static String price(Double amount) {
        return amount == null ? "—" : String.format(Locale.ENGLISH, "HK$ %.2f", amount);
    }

    @FunctionalInterface private interface Action { void run(User buyer, String id, long version) throws SQLException; }
    private void act(Deal deal, Action action, String success, Runnable onSaved) {
        if (!active) return;
        User buyer = user.get();
        long token = ++request;
        refresh.setDisable(true);
        rows.setDisable(true);
        feedback.setText("Saving deal...");
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws SQLException { action.run(buyer, deal.getId(), deal.getRowVersion()); return null; }
        };
        task.setOnSucceeded(event -> {
            if (!current(token, buyer)) return;
            rows.setDisable(false);
            feedback.setText(success);
            onSaved.run();
        });
        task.setOnFailed(event -> {
            if (!current(token, buyer)) return;
            rows.setDisable(false);
            feedback.setText(error(task.getException(), "Unable to update this deal. Refresh and try again."));
            refresh();
        });
        start(task, "buyer-deal-action");
    }

    private Button button(String label, String style, Runnable action) {
        Button button = new Button(label);
        button.getStyleClass().addAll(style, "btn-sm");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }

    private boolean current(long token, User actor) {
        User now = user.get();
        return active && token == request && actor != null && now != null
                && Objects.equals(actor.getUsername(), now.getUsername());
    }
    private static String format(LocalDateTime value) { return value == null ? "—" : DATE.format(value); }
    private static String error(Throwable failure, String fallback) {
        return failure instanceof IllegalArgumentException ? failure.getMessage() : fallback;
    }
    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
