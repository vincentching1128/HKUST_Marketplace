package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.ReviewService;
import com.hkust.marketplace.utils.SessionManager;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SellerReviewsPane extends ScrollPane {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private static final Logger LOGGER = Logger.getLogger(SellerReviewsPane.class.getName());

    private final ReviewService service;
    private final User seller;
    private final Button refresh = new Button("Refresh");
    private final Label summary = new Label();
    private final Label feedback = new Label();
    private final VBox items = new VBox(12);

    SellerReviewsPane(ReviewService service, User seller) {
        this.service = service;
        this.seller = seller;
        refresh.getStyleClass().add("btn-outline");
        refresh.setOnAction(event -> {
            feedback.setText("");
            refreshReviews();
        });
        feedback.setWrapText(true);
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
        VBox root = new VBox(12, new HBox(12, refresh, summary), feedback, items);
        root.setPadding(new Insets(4));
        setContent(root);
        setFitToWidth(true);
    }

    void refreshReviews() {
        summary.setText("");
        items.getChildren().setAll(new Label("Loading reviews..."), new ProgressIndicator());
        run(() -> service.getSellerReviews(seller), this::render, "Unable to load reviews. Use Refresh to try again.");
    }

    private void render(List<Review> reviews) {
        var average = reviews.stream().filter(review -> !review.isHidden()).mapToInt(Review::getRating).average();
        summary.setText(average.isPresent()
                ? String.format(Locale.ENGLISH, "Average rating: %.1f / 5", average.getAsDouble())
                : "No visible ratings yet");
        items.getChildren().clear();
        if (reviews.isEmpty()) items.getChildren().add(new Label("No reviews yet."));
        reviews.forEach(review -> items.getChildren().add(reviewCard(review)));
    }

    private VBox reviewCard(Review review) {
        Label stars = new Label("\u2605".repeat(review.getRating()) + "\u2606".repeat(5 - review.getRating())
                + "  " + review.getRating() + "/5");
        stars.setStyle("-fx-text-fill: #A68137; -fx-font-size: 16px;");
        Label meta = new Label("@" + review.getBuyerUsername() + " · " + review.getListingTitle()
                + " · " + review.getCreatedAt().format(DATE));
        meta.setWrapText(true);
        Label comment = new Label(review.getComment());
        comment.setWrapText(true);
        VBox item = new VBox(6, stars, meta, comment);
        item.getStyleClass().add("card");
        if (review.getSellerReply() != null) {
            Label reply = new Label("Your reply\n" + review.getSellerReply());
            reply.setWrapText(true);
            item.getChildren().add(reply);
        }

        Button reply = new Button("Reply");
        reply.getStyleClass().addAll("btn-outline", "btn-sm");
        reply.setDisable(review.isHidden() || review.getSellerReply() != null);
        reply.setOnAction(event -> openReply(review));
        Button flag = new Button(review.isFlagged() ? "Flagged" : "Flag");
        flag.getStyleClass().addAll("btn-danger", "btn-sm");
        flag.setDisable(review.isHidden() || review.isFlagged());
        flag.setOnAction(event -> new Alert(Alert.AlertType.CONFIRMATION, "Flag this review for admin moderation?")
                .showAndWait().filter(ButtonType.OK::equals).ifPresent(ignored -> run(() -> {
                    service.flag(seller, review.getId());
                    return "Review flagged for admin moderation.";
                }, this::saved, "Unable to flag this review.")));
        if (review.isHidden()) item.getChildren().add(new Label("Hidden by an administrator"));
        item.getChildren().add(new HBox(8, reply, flag));
        return item;
    }

    private void openReply(Review review) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Reply to Review");
        dialog.setHeaderText("Reply to @" + review.getBuyerUsername());
        TextArea reply = new TextArea();
        reply.setPromptText("Write your reply");
        reply.setWrapText(true);
        reply.setPrefRowCount(4);
        Label error = new Label();
        error.setWrapText(true);
        ButtonType send = new ButtonType("Send Reply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8, reply, error));
        dialog.getDialogPane().lookupButton(send).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String text = reply.getText().strip();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > ReviewService.MAX_TEXT_LENGTH) {
                error.setText("Enter a reply of 1 to " + ReviewService.MAX_TEXT_LENGTH + " characters.");
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == send ? reply.getText() : null);
        dialog.showAndWait().ifPresent(text -> run(() -> {
            service.reply(seller, review.getId(), text);
            return "Reply sent to the buyer.";
        }, this::saved, "Unable to send this reply."));
    }

    private void saved(String message) {
        feedback.setText(message);
        refreshReviews();
    }

    private <T> void run(Callable<T> work, Consumer<T> onSuccess, String fallback) {
        refresh.setDisable(true);
        items.setDisable(true);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> {
            if (!isCurrent()) return;
            refresh.setDisable(false);
            items.setDisable(false);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            if (!(failure instanceof IllegalArgumentException)) LOGGER.log(Level.WARNING, fallback, failure);
            if (!isCurrent()) return;
            refresh.setDisable(false);
            items.setDisable(false);
            if (items.getChildren().stream().anyMatch(ProgressIndicator.class::isInstance)) {
                items.getChildren().clear();
            }
            feedback.setText(failure instanceof IllegalArgumentException ? failure.getMessage() : fallback);
        });
        Thread worker = new Thread(task, "seller-reviews");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean isCurrent() {
        return getParent() != null && SessionManager.getCurrentUser() == seller;
    }
}
