package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.services.ReviewService;
import com.hkust.marketplace.services.ReviewService.SellerProfile;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.sql.SQLException;
import java.util.Locale;

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
        addText(average.isEmpty() ? "No seller ratings yet." : String.format(Locale.ENGLISH,
                "Seller rating: %.1f / 5 (%d)", average.getAsDouble(), seller.reviews().size()));
        for (Review review : seller.reviews()) {
            String author = review.getBuyerUsername() == null ? "Deleted buyer" : "@" + review.getBuyerUsername();
            addText(review.getRating() + "/5 · " + author + " · " + review.getListingTitle());
            addText(review.getComment());
            if (review.getSellerReply() != null) addText("Seller reply: " + review.getSellerReply());
        }
    }

    private void addText(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        getChildren().add(label);
    }
}
