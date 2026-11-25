package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.NotificationService;
import com.hkust.marketplace.utils.SessionManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.util.Duration;

/** Keeps a dashboard's notification button in sync with its unread inbox. */
public final class UnreadNotificationBadge {
    private final Button button;
    private final NotificationService notifications;
    private final User recipient = SessionManager.getCurrentUser();
    private final Label count = new Label();
    private final Timeline polling;
    private Task<Integer> pending;

    public UnreadNotificationBadge(Button button, NotificationService notifications) {
        this.button = button;
        this.notifications = notifications;
        count.getStyleClass().add("notification-count");
        button.setContentDisplay(ContentDisplay.RIGHT);
        button.setGraphicTextGap(8);

        polling = new Timeline(new KeyFrame(Duration.seconds(10), event -> {
            if (pending == null) refresh();
        }));
        polling.setCycleCount(Timeline.INDEFINITE);
        button.sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene == null) stop();
            else start();
        });
        if (button.getScene() != null) start();
    }

    private void start() {
        if (recipient == null || SessionManager.getCurrentUser() != recipient) return;
        refresh();
        polling.playFromStart();
    }

    public void refresh() {
        if (recipient == null || SessionManager.getCurrentUser() != recipient || button.getScene() == null) {
            stop();
            return;
        }
        if (pending != null) pending.cancel();
        Task<Integer> load = new Task<>() {
            @Override protected Integer call() throws Exception {
                return notifications.getNotifications(recipient, false).size();
            }
        };
        pending = load;
        load.setOnSucceeded(event -> {
            if (pending != load) return;
            pending = null;
            if (SessionManager.getCurrentUser() != recipient || button.getScene() == null) return;
            int unread = load.getValue();
            count.setText(unread > 99 ? "99+" : Integer.toString(unread));
            button.setGraphic(unread > 0 ? count : null);
            button.setAccessibleText(unread > 0 ? "Notifications, " + unread + " unread" : null);
        });
        load.setOnFailed(event -> {
            if (pending == load) pending = null;
        });
        Thread worker = new Thread(load, "notification-unread-count");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        polling.stop();
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        button.setGraphic(null);
        button.setAccessibleText(null);
    }
}
