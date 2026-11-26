package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.services.NotificationService;
import com.hkust.marketplace.utils.SessionManager;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Asynchronous, recipient-scoped notification inbox used by all three portals.
 *
 * <p>Loads notifications through {@link NotificationService} on a background thread,
 * renders read and unread history, and marks individual or all notifications as read.
 * Callers deactivate the board when leaving a portal screen so stale background results
 * and notifications for the previous recipient are ignored.
 */
public class NotificationBoard extends ScrollPane {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
    private final NotificationService notifications;
    private final Runnable onNotificationsChanged;
    private boolean includeReadNotifications;
    private boolean active;
    private long notificationRequest;

    public NotificationBoard(NotificationService notifications) {
        this(notifications, () -> {});
    }

    public NotificationBoard(NotificationService notifications, Runnable onNotificationsChanged) {
        this.notifications = notifications;
        this.onNotificationsChanged = onNotificationsChanged;
        setFitToWidth(true);
    }

    public void show(User recipient) {
        active = true;
        long request = ++notificationRequest;
        boolean includeRead = includeReadNotifications;
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        CheckBox history = new CheckBox("Show read notifications");
        history.setSelected(includeRead);
        history.setOnAction(event -> {
            includeReadNotifications = history.isSelected();
            show(recipient);
        });
        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn-outline");
        refresh.setOnAction(event -> show(recipient));
        Button readAll = new Button("Mark all as read");
        readAll.getStyleClass().add("btn-outline");
        readAll.setDisable(true);
        readAll.setOnAction(event -> saveRead(recipient, readAll, () -> notifications.markAllAsRead(recipient)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, history, spacer, readAll, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(toolbar);
        VBox inbox = new VBox(12, new Label("Loading notifications..."), new ProgressIndicator());
        root.getChildren().add(inbox);
        setContent(root);
        Task<List<Notification>> load = new Task<>() {
            @Override protected List<Notification> call() throws SQLException {
                return notifications.getNotifications(recipient, includeRead);
            }
        };
        load.setOnSucceeded(event -> {
            if (!currentNotificationRequest(request, recipient)) return;
            inbox.getChildren().clear();
            readAll.setDisable(load.getValue().stream().allMatch(Notification::isRead));
            if (load.getValue().isEmpty()) {
                inbox.getChildren().add(new Label(includeRead ? "No notifications." : "No unread notifications."));
            }
            for (Notification notification : load.getValue()) {
                inbox.getChildren().add(notificationCard(notification, recipient));
            }
            onNotificationsChanged.run();
        });
        load.setOnFailed(event -> {
            if (!currentNotificationRequest(request, recipient)) return;
            String message = load.getException() instanceof IllegalArgumentException
                    ? load.getException().getMessage() : "Unable to load notifications. Use Refresh to try again.";
            Label error = new Label(message);
            error.setWrapText(true);
            error.setStyle("-fx-text-fill: #c62828;");
            inbox.getChildren().setAll(error);
        });
        Thread thread = new Thread(load, "notification-load");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean currentNotificationRequest(long request, User recipient) {
        return request == notificationRequest && isActiveFor(recipient);
    }

    public void deactivate() {
        active = false;
        notificationRequest++;
    }

    private boolean isActiveFor(User recipient) {
        return active && SessionManager.getCurrentUser() == recipient;
    }

    private HBox notificationCard(Notification notification, User recipient) {
        boolean isRead = notification.isRead();
        Label title = new Label(notification.getTitle());
        title.setWrapText(true);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: "
                + (isRead ? "#3a3a5a" : "#1a1a2e") + ";");
        Label category = new Label(notification.getCategory());
        category.getStyleClass().addAll("badge", "notif-category");
        category.setMinWidth(Region.USE_PREF_SIZE);
        Label timestamp = new Label(notification.getTimestamp() == null
                ? "Unknown time" : DATE.format(notification.getTimestamp()));
        timestamp.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 11px;");
        timestamp.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(8, title, category, timestamp);
        header.setAlignment(Pos.CENTER_LEFT);
        Label message = new Label(notification.getMessage());
        message.setWrapText(true);
        VBox details = new VBox(4, header, message);
        HBox.setHgrow(details, Priority.ALWAYS);
        Button read = new Button(isRead ? "Read" : "Mark as read");
        read.getStyleClass().addAll("btn-outline", "btn-sm");
        read.setMinWidth(Region.USE_PREF_SIZE);
        read.setDisable(isRead);
        read.setOnAction(event -> saveRead(recipient, read, () -> notifications.markAsRead(recipient, notification.getId())));
        HBox item = new HBox(12, details, read);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("notif-item");
        if (isRead) item.getStyleClass().add("notif-item-read");
        return item;
    }

    @FunctionalInterface
    private interface ReadAction { void run() throws SQLException; }

    private void saveRead(User recipient, Button read, ReadAction action) {
        long request = notificationRequest;
        read.setDisable(true);
        Task<Void> save = new Task<>() {
            @Override protected Void call() throws SQLException {
                action.run();
                return null;
            }
        };
        save.setOnSucceeded(done -> {
            if (SessionManager.getCurrentUser() != recipient) return;
            onNotificationsChanged.run();
            if (isActiveFor(recipient)) show(recipient);
        });
        save.setOnFailed(done -> {
            if (!currentNotificationRequest(request, recipient)) return;
            read.setDisable(false);
            new Alert(Alert.AlertType.ERROR, "Unable to update notifications. Please try again.").showAndWait();
        });
        Thread thread = new Thread(save, "notification-read");
        thread.setDaemon(true);
        thread.start();
    }
}
