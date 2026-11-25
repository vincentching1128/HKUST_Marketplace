package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.DealService;
import com.hkust.marketplace.services.ChatService;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Real seller proposals; database work runs off the JavaFX application thread. */
public class SellerOffersPane extends VBox {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final DealService service;
    private final ChatService chats = new ChatService();
    private final Supplier<User> currentUser;
    private final Consumer<String> openListing;
    private final Consumer<String> openChat;
    private final Button refresh = button("Refresh", "btn-outline");
    private final VBox items = new VBox(14);
    private final Label feedback = new Label();
    private boolean active = true;
    private boolean busy;
    private long request;

    public SellerOffersPane(DealService service, Supplier<User> currentUser, Consumer<String> openListing) {
        this(service,currentUser,openListing,null);
    }

    public SellerOffersPane(DealService service, Supplier<User> currentUser, Consumer<String> openListing, Consumer<String> openChat) {
        super(12);
        this.service = Objects.requireNonNull(service);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.openListing = Objects.requireNonNull(openListing);
        this.openChat = openChat;
        setPadding(new Insets(4));
        Label help = new Label("Review incoming sale offers, borrowing requests and giveaway claims. Counteroffers await the buyer's response.");
        help.setWrapText(true);
        help.setStyle("-fx-text-fill: #5a5a7a;");
        refresh.setOnAction(event -> refresh());
        feedback.setWrapText(true);
        feedback.setVisible(false);
        feedback.setManaged(false);
        ScrollPane scroll = new ScrollPane(items);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(new HBox(12, refresh), help, feedback, scroll);
    }

    /** Ignore delayed UI callbacks after navigating away; never interrupt a database write. */
    public void deactivate() { active = false; request++; }

    public void refresh() { refresh("", false); }

    private void refresh(String message, boolean error) {
        if (!active || busy) return;
        User seller = currentUser.get();
        showFeedback(message, error);
        items.getChildren().setAll(new VBox(10, new Label("Loading incoming offers..."), new ProgressIndicator()));
        setBusy(true);
        long token = ++request;
        Task<List<Deal>> load = new Task<>() {
            @Override protected List<Deal> call() throws SQLException { return service.getIncomingOffers(seller); }
        };
        load.setOnSucceeded(event -> {
            if (!isCurrent(token, seller)) return;
            setBusy(false);
            items.getChildren().clear();
            if (load.getValue().isEmpty()) items.getChildren().add(new Label("No pending offers, borrowing requests or claims."));
            else for (Deal deal : load.getValue()) items.getChildren().add(card(deal));
        });
        load.setOnFailed(event -> {
            if (!isCurrent(token, seller)) return;
            setBusy(false);
            items.getChildren().setAll(new Label("Use Refresh to try loading your incoming offers again."));
            showFeedback(errorMessage(load.getException(), "Unable to load incoming offers."), true);
        });
        start(load, "seller-offer-load");
    }

    private VBox card(Deal deal) {
        Label title = new Label(deal.getListingTitle());
        title.setWrapText(true);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label status = new Label(deal.getStatus().getDisplayName());
        status.getStyleClass().addAll("badge", "badge-pending");
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox heading = new HBox(12, title, space, status);
        heading.setAlignment(Pos.CENTER_LEFT);
        Label buyer = new Label("Buyer: @" + deal.getBuyerUsername() + " · " + typeName(deal.getDealType()));
        buyer.setWrapText(true);
        Label original = new Label(originalTerms(deal));
        original.setWrapText(true);
        VBox card = new VBox(10, heading, buyer, original);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        if (deal.getStatus() == DealStatus.COUNTERED) {
            Label counter = new Label("Your counter: " + (deal.getDealType() == ListingType.SELL
                    ? money(deal.getCounterOfferPrice())
                    : period(deal.getCounterBorrowStart(), deal.getCounterBorrowEnd())));
            counter.setWrapText(true);
            card.getChildren().addAll(counter, new Label("Waiting for the buyer to accept or decline."));
        }
        card.getChildren().add(new Label("Submitted: " + dateTime(deal.getCreatedAt())));
        Button view = button("View Listing", "btn-outline");
        view.setOnAction(event -> openListing.accept(deal.getListingId()));
        Button accept = button("Accept", "btn-success");
        accept.setDisable(deal.getStatus() != DealStatus.PENDING);
        if (accept.isDisabled()) accept.setTooltip(new Tooltip("The buyer must respond to your counteroffer."));
        accept.setOnAction(event -> {
            if (confirm("Accept proposal?", "Accept @" + deal.getBuyerUsername() + "'s proposal for '"
                    + deal.getListingTitle() + "'? The listing will be reserved and competing open proposals cancelled.")) {
                runAction(seller -> service.acceptOffer(seller, deal.getId(), deal.getRowVersion()), "Proposal accepted.");
            }
        });
        Button decline = button("Decline", "btn-danger");
        decline.setOnAction(event -> {
            if (confirm("Decline proposal?", "Decline @" + deal.getBuyerUsername() + "'s proposal for '"
                    + deal.getListingTitle() + "'? The buyer will be notified.")) {
                runAction(seller -> service.declineOffer(seller, deal.getId(), deal.getRowVersion()), "Proposal declined.");
            }
        });
        FlowPane actions = new FlowPane(10, 10, view, accept, decline);
        if (openChat != null) {
            Button chat = button("Open Chat", "btn-outline");
            chat.setOnAction(event -> openChat.accept(deal.getId()));
            actions.getChildren().add(chat);
        }
        if (deal.getDealType() != ListingType.GIVEAWAY) {
            Button counter = button(deal.getStatus() == DealStatus.COUNTERED ? "Revise Counter" : "Counter Offer", "btn-outline");
            counter.setOnAction(event -> counterDialog(deal).showAndWait().ifPresent(terms ->
                    runAction(seller -> {
                        service.counterOffer(seller, deal.getId(), deal.getRowVersion(),
                                terms.price(), terms.start(), terms.end());
                        try {
                            var thread = chats.openForDeal(seller, deal.getId());
                            chats.sendProposal(seller, thread.id(), deal.getId());
                        } catch (SQLException | IllegalArgumentException chatFailure) {
                            // The counteroffer is already committed; its status remains visible to the buyer.
                            System.err.println("Counteroffer chat message could not be sent: " + chatFailure.getMessage());
                        }
                    }, "Counteroffer sent. Waiting for the buyer's response.")));
            actions.getChildren().add(counter);
        }
        card.getChildren().add(actions);
        return card;
    }

    private record CounterTerms(Double price, LocalDateTime start, LocalDateTime end) {}

    private Dialog<CounterTerms> counterDialog(Deal deal) {
        Dialog<CounterTerms> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Counter Proposal");
        dialog.setHeaderText("Propose new terms for " + deal.getListingTitle());
        dialog.getDialogPane().setPrefWidth(520);
        ButtonType send = new ButtonType("Send Counter", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        VBox form = new VBox(10);
        form.setPadding(new Insets(10));
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #c62828;");
        TextField price = new TextField();
        LocalDateTime originalStart = deal.getCounterBorrowStart() != null ? deal.getCounterBorrowStart() : deal.getBorrowStart();
        LocalDateTime originalEnd = deal.getCounterBorrowEnd() != null ? deal.getCounterBorrowEnd() : deal.getBorrowEnd();
        LocalDateTime defaultStart = originalStart == null ? LocalDateTime.now().plusMinutes(1).withNano(0) : originalStart;
        LocalDateTime defaultEnd = originalEnd == null ? defaultStart.plusSeconds(5) : originalEnd;
        DatePicker startDate = new DatePicker(defaultStart.toLocalDate());
        DatePicker endDate = new DatePicker(defaultEnd.toLocalDate());
        TextField startTime = new TextField(defaultStart.format(TIME));
        TextField endTime = new TextField(defaultEnd.format(TIME));
        if (deal.getDealType() == ListingType.SELL) {
            Double initial = deal.getCounterOfferPrice() != null ? deal.getCounterOfferPrice() : deal.getOfferPrice();
            price.setText(initial == null ? "" : BigDecimal.valueOf(initial).toPlainString());
            form.getChildren().addAll(new Label("Counter price (HK$)"), price);
        } else {
            startTime.setPromptText("HH:mm:ss"); endTime.setPromptText("HH:mm:ss");
            form.getChildren().addAll(new Label("Start date and time (HH:mm:ss)"), new HBox(10, startDate, startTime),
                    new Label("End date and time (HH:mm:ss)"), new HBox(10, endDate, endTime),
                    new Label("The borrowing period must be at least 5 seconds."));
        }
        form.getChildren().add(error);
        dialog.getDialogPane().setContent(form);
        CounterTerms[] chosen = new CounterTerms[1];
        dialog.getDialogPane().lookupButton(send).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (deal.getDealType() == ListingType.SELL) {
                    BigDecimal amount = new BigDecimal(price.getText().strip());
                    if (amount.signum() < 0 || amount.compareTo(new BigDecimal("99999999.99")) > 0
                            || amount.stripTrailingZeros().scale() > 2) {
                        throw new IllegalArgumentException("Enter a nonnegative price up to HK$99,999,999.99 with at most two decimal places.");
                    }
                    chosen[0] = new CounterTerms(amount.doubleValue(), null, null);
                } else {
                    LocalDateTime start = readDateTime(startDate, startTime);
                    LocalDateTime end = readDateTime(endDate, endTime);
                    if (Duration.between(start, end).compareTo(Duration.ofSeconds(5)) < 0 || !end.isAfter(LocalDateTime.now())) {
                        throw new IllegalArgumentException("Choose an unexpired borrowing period at least 5 seconds long.");
                    }
                    chosen[0] = new CounterTerms(null, start, end);
                }
            } catch (RuntimeException invalid) {
                error.setText(invalid instanceof NumberFormatException ? "Enter a valid counter price." : invalid.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(selected -> selected == send ? chosen[0] : null);
        return dialog;
    }

    private static LocalDateTime readDateTime(DatePicker date, TextField time) {
        try {
            if (date.getValue() == null || !time.getText().strip().matches("\\d{2}:\\d{2}:\\d{2}")) throw new IllegalArgumentException();
            return LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().strip()));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Choose both dates and enter valid times as HH:mm:ss.");
        }
    }

    @FunctionalInterface private interface OfferAction { void run(User seller) throws SQLException; }

    private void runAction(OfferAction action, String success) {
        if (!active || busy) return;
        User seller = currentUser.get();
        setBusy(true);
        showFeedback("Saving proposal...", false);
        long token = ++request;
        Task<Void> save = new Task<>() {
            @Override protected Void call() throws SQLException { action.run(seller); return null; }
        };
        save.setOnSucceeded(event -> {
            if (!isCurrent(token, seller)) return;
            setBusy(false);
            refresh(success, false);
        });
        save.setOnFailed(event -> {
            if (!isCurrent(token, seller)) return;
            setBusy(false);
            refresh(errorMessage(save.getException(), "Unable to save the proposal. Refresh and check its current status before retrying."), true);
        });
        start(save, "seller-offer-action");
    }

    private boolean isCurrent(long token, User seller) {
        User current = currentUser.get();
        return active && token == request && seller != null && current != null
                && Objects.equals(seller.getUsername(), current.getUsername());
    }

    private void setBusy(boolean value) { busy = value; refresh.setDisable(value); items.setDisable(value); }

    private void showFeedback(String message, boolean error) {
        feedback.setText(message);
        feedback.setVisible(!message.isBlank());
        feedback.setManaged(!message.isBlank());
        feedback.setStyle("-fx-text-fill: " + (error ? "#c62828" : "#2e7d32") + ";");
    }

    private boolean confirm(String title, String message) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        confirmation.setTitle(title);
        if (getScene() != null) confirmation.initOwner(getScene().getWindow());
        return confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start();
    }

    private static String errorMessage(Throwable error, String fallback) {
        return error instanceof IllegalArgumentException ? error.getMessage() : fallback;
    }

    private static Button button(String title, String style) {
        Button button = new Button(title); button.getStyleClass().add(style); return button;
    }

    private static String typeName(ListingType type) {
        return switch (type) { case SELL -> "Sale offer"; case LEND -> "Borrow request"; case GIVEAWAY -> "Giveaway claim"; };
    }

    private static String originalTerms(Deal deal) {
        return switch (deal.getDealType()) {
            case SELL -> "Buyer offered: " + money(deal.getOfferPrice());
            case LEND -> "Requested period: " + period(deal.getBorrowStart(), deal.getBorrowEnd());
            case GIVEAWAY -> "Buyer requested this giveaway item.";
        };
    }

    private static String money(Double amount) { return amount == null ? "Not specified" : String.format(java.util.Locale.ENGLISH, "HK$ %.2f", amount); }
    private static String dateTime(LocalDateTime value) { return value == null ? "Not specified" : value.format(DATE_TIME); }
    private static String period(LocalDateTime start, LocalDateTime end) { return dateTime(start) + " – " + dateTime(end); }
}
