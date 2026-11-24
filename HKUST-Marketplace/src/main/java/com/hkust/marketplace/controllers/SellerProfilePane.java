package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.services.ReviewService;
import com.hkust.marketplace.services.ReviewService.SellerProfile;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.sql.SQLException;

/** Public feedback shared by listing details and seller profiles. */
public final class SellerProfilePane extends VBox {
    private final String username;
    private final boolean profile;

    private SellerProfilePane(String username, boolean profile) {
        super(8);
        this.username = username;
        this.profile = profile;
        load();
    }

    public static SellerProfilePane listingFeedback(String username) {
        return new SellerProfilePane(username, false);
    }

    public static Button profileButton(String username) {
        Button button = new Button("Seller Profile");
        button.getStyleClass().add("btn-outline");
        button.setDisable(username == null);
        button.setOnAction(event -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.initOwner(button.getScene().getWindow());
            dialog.setTitle("Seller Profile");
            ScrollPane scroll = new ScrollPane(new SellerProfilePane(username, true));
            scroll.setFitToWidth(true);
            scroll.setPrefViewportWidth(470);
            scroll.setPrefViewportHeight(380);
            dialog.getDialogPane().setContent(scroll);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.showAndWait();
        });
        return button;
    }

    private void load() {
        getChildren().setAll(new Label("Loading seller feedback…"));
        Task<SellerProfile> task = new Task<>() {
            @Override protected SellerProfile call() throws SQLException {
                return new ReviewService().getPublicProfile(username);
            }
        };
        task.setOnSucceeded(event -> render(task.getValue()));
        task.setOnFailed(event -> {
            Button retry = new Button("Retry");
            retry.setOnAction(click -> load());
            getChildren().setAll(new Label("Unable to load seller feedback."), retry);
        });
        Thread worker = new Thread(task, "seller-public-feedback");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(SellerProfile seller) {
        getChildren().clear();
        if (profile) {
            addText(seller.name() + " (@" + seller.username() + ")");
            if (seller.bio() != null && !seller.bio().isBlank()) addText(seller.bio());
            if (seller.meetup() != null && !seller.meetup().isBlank()) addText("Preferred meet-up: " + seller.meetup());
        } else {
            getChildren().add(profileButton(username));
        }
        var average = seller.average();
        Label summary = new Label(average.isEmpty() ? "No ratings yet"
                : RatingDisplay.average(average.getAsDouble(), seller.reviews().size()));
        summary.setStyle(RatingDisplay.SUMMARY_STYLE);
        getChildren().add(summary);
        for (Review review : seller.reviews()) {
            Label meta = new Label(RatingDisplay.buyerName(review.getBuyerUsername()) + " · " + review.getListingTitle());
            meta.setWrapText(true);
            meta.setStyle(RatingDisplay.META_STYLE);
            Label comment = new Label(review.getComment());
            comment.setWrapText(true);
            VBox card = new VBox(4, RatingDisplay.stars(review.getRating()), meta, comment);
            card.getStyleClass().add("card");
            if (review.getSellerReply() != null) {
                Label reply = new Label("Seller reply: " + review.getSellerReply());
                reply.setWrapText(true);
                reply.setStyle("-fx-text-fill: #5a5a7a;");
                card.getChildren().add(reply);
            }
            getChildren().add(card);
        }
    }

    private void addText(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        getChildren().add(label);
    }
}
