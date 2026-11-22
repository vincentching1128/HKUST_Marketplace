package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.DealHistoryEntry;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.ReviewService;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.sql.SQLException;

final class BuyerReviewDialog extends Dialog<Void> {
    BuyerReviewDialog(User buyer, DealHistoryEntry deal, Window owner) {
        initOwner(owner);
        setTitle("Rate Seller");
        setHeaderText(deal.title() + " · " + deal.sellerDisplay());
        ComboBox<Integer> stars = new ComboBox<>();
        stars.getItems().addAll(1, 2, 3, 4, 5);
        stars.setPromptText("Choose 1–5 stars");
        TextArea comment = new TextArea();
        comment.setWrapText(true);
        comment.setPrefRowCount(4);
        comment.setPromptText("Write your review");
        Label message = new Label();
        message.setWrapText(true);
        VBox form = new VBox(8, stars, comment, message);
        getDialogPane().setContent(form);
        ButtonType submit = new ButtonType("Submit Review", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL);
        Button save = (Button) getDialogPane().lookupButton(submit);
        save.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Integer rating = stars.getValue();
            String text = comment.getText().strip();
            if (rating == null || text.isEmpty() || text.codePointCount(0, text.length()) > ReviewService.MAX_TEXT_LENGTH) {
                message.setText("Choose a rating and write a review of 1–" + ReviewService.MAX_TEXT_LENGTH + " characters.");
                return;
            }
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws SQLException {
                    new ReviewService().submitReview(buyer, deal.dealId(), rating, text);
                    return null;
                }
            };
            getDialogPane().setDisable(true);
            setOnCloseRequest(close -> { if (getDialogPane().isDisabled()) close.consume(); });
            task.setOnSucceeded(done -> {
                getDialogPane().setDisable(false);
                close();
            });
            task.setOnFailed(failed -> {
                getDialogPane().setDisable(false);
                message.setText(task.getException() instanceof IllegalArgumentException
                        ? task.getException().getMessage() : "Unable to save your review. Please try again.");
            });
            Thread worker = new Thread(task, "buyer-submit-review");
            worker.setDaemon(true);
            worker.start();
        });
    }
}
