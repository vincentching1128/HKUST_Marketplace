package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.services.BuyerDealService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Real buyer proposals and deals, including terminal records. */
public class BuyerDealsPane extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");
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
        getChildren().addAll(refresh, feedback, scroll);
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

    private VBox card(Deal deal) {
        Label title = new Label(deal.getListingTitle());
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        VBox card = new VBox(9, title,
                new Label((deal.getSellerUsername() == null ? "Deleted seller" : "Seller: @" + deal.getSellerUsername())
                        + "  |  " + deal.getDealType() + "  |  " + deal.getStatus().getDisplayName()),
                new Label("Submitted: " + format(deal.getCreatedAt())));
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        if (deal.getDealType() == ListingType.SELL) {
            card.getChildren().add(new Label("Your offer: HK$" + deal.getOfferPrice()));
        } else if (deal.getDealType() == ListingType.LEND) {
            card.getChildren().add(new Label("Borrow: " + format(deal.getBorrowStart()) + " to " + format(deal.getBorrowEnd())));
            if (deal.getStatus() == DealStatus.ACCEPTED) card.getChildren().add(new Label("Due: " + format(deal.getBorrowEnd())));
        }
        if (deal.getStatus() == DealStatus.COUNTERED) {
            card.getChildren().add(new Label(deal.getDealType() == ListingType.SELL
                    ? "Seller counteroffer: HK$" + deal.getCounterOfferPrice()
                    : "Seller proposed: " + format(deal.getCounterBorrowStart()) + " to " + format(deal.getCounterBorrowEnd())));
        }
        FlowPane actions = new FlowPane(10, 10);
        actions.getChildren().add(button("View Listing", () -> openListing.accept(deal.getListingId())));
        actions.getChildren().add(button("Open Chat", () -> openChat.accept(deal.getId())));
        if (deal.getStatus() == DealStatus.COUNTERED) {
            actions.getChildren().add(button("Accept Counter", () -> act(deal, service::acceptCounter, "Counteroffer accepted.", this::refresh)));
            actions.getChildren().add(button("Decline Counter", () -> act(deal, service::declineCounter, "Counteroffer declined.", this::refresh)));
        }
        if (deal.getStatus() == DealStatus.ACCEPTED) {
            if (deal.getDealType() == ListingType.LEND) {
                Button giveBack = button("Self Return", () -> act(deal, service::returnBorrowed,
                        "Borrowing returned.", () -> openHistory.accept(deal.getId())));
                giveBack.setDisable(deal.getBorrowStart() == null || LocalDateTime.now().isBefore(deal.getBorrowStart()));
                actions.getChildren().add(giveBack);
            } else {
                actions.getChildren().add(button("Mark Complete", () -> act(deal, service::complete,
                        "Deal completed.", () -> openHistory.accept(deal.getId()))));
            }
        }
        if (deal.getStatus() == DealStatus.RETURNED || deal.getStatus() == DealStatus.COMPLETED) {
            actions.getChildren().add(button("Review in Deal History", () -> openHistory.accept(deal.getId())));
        }
        card.getChildren().add(actions);
        return card;
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

    private Button button(String label, Runnable action) {
        Button button = new Button(label);
        button.getStyleClass().add("btn-outline");
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
