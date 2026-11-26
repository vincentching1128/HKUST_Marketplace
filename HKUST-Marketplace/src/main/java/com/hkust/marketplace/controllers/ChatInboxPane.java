package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.services.ChatService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.*;

/** Shared private-conversation inbox for the buyer and seller portals. */
public class ChatInboxPane extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final ChatService service;
    private final Supplier<User> user;
    private final Consumer<ChatThread> open;
    private final VBox rows=new VBox(8);
    private final Button refresh=new Button("Refresh");
    private boolean active=true;
    private long request;

    /**
     * Creates an empty private-conversation inbox.
     *
     * <p>The inbox is populated by {@link #refresh()}. Threads are read asynchronously through
     * {@link ChatService#getInbox(com.hkust.marketplace.models.User)}; this pane never modifies
     * the database and delegates opening a conversation to the supplied callback. Must be
     * created on the JavaFX Application Thread.
     *
     * @param service chat service used to load the inbox; must not be {@code null}
     * @param user supplier of the currently signed-in user, evaluated on each refresh; must
     *        not be {@code null}
     * @param open callback invoked when "Open Chat" is clicked for a thread, typically routing
     *        the portal to a {@link ChatPane}; must not be {@code null}
     */
    public ChatInboxPane(ChatService service,Supplier<User> user,Consumer<ChatThread> open) {
        super(12);this.service=Objects.requireNonNull(service);this.user=Objects.requireNonNull(user);this.open=Objects.requireNonNull(open);
        setPadding(new Insets(4));refresh.getStyleClass().addAll("btn-outline", "btn-sm");refresh.setOnAction(e->refresh());
        HBox topBar = new HBox(refresh);
        topBar.setAlignment(Pos.CENTER_RIGHT);
        rows.setPadding(new Insets(10));
        ScrollPane scroll=new ScrollPane(rows);scroll.setFitToWidth(true);VBox.setVgrow(scroll,Priority.ALWAYS);
        getChildren().addAll(topBar,scroll);
    }

    /**
     * Deactivates the inbox and invalidates any in-flight load.
     *
     * <p>Call when navigating away so results for the previous screen are ignored. The inbox
     * cannot be reactivated.
     */
    public void deactivate(){active=false;request++;}

    /**
     * Loads the current user's conversations asynchronously and renders them.
     *
     * <p>No-op after {@link #deactivate()}. The refresh button is disabled while a load runs,
     * and failures are shown in place of the list. Read-only: threads are fetched through
     * {@link ChatService}.
     */
    public void refresh(){
        if(!active)return;User actor=user.get();long token=++request;refresh.setDisable(true);
        rows.getChildren().setAll(new Label("Loading conversations..."));
        Task<List<ChatThread>> task=new Task<>(){protected List<ChatThread> call()throws SQLException{return service.getInbox(actor);}};
        task.setOnSucceeded(e->{if(!current(token,actor))return;refresh.setDisable(false);rows.getChildren().clear();
            if(task.getValue().isEmpty())rows.getChildren().add(new Label("No conversations yet."));
            for (ChatThread thread : task.getValue()) {
                rows.getChildren().add(threadCard(thread, actor));
            }
        });
        task.setOnFailed(e->{if(!current(token,actor))return;refresh.setDisable(false);Label error=new Label(task.getException() instanceof IllegalArgumentException
                ?task.getException().getMessage():"Unable to load conversations. Refresh to try again.");error.setWrapText(true);rows.getChildren().setAll(error);});
        Thread thread=new Thread(task,"chat-inbox-load");thread.setDaemon(true);thread.start();
    }

    private HBox threadCard(ChatThread thread, User actor) {
        Label title = new Label(thread.listingTitle());
        title.setWrapText(true);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

        Label badge = new Label(switch (thread.listingType()) {
            case SELL -> "Sell";
            case LEND -> "Lend";
            case GIVEAWAY -> "Give Away";
        });
        badge.getStyleClass().add("badge");
        badge.setStyle("-fx-background-color: #f4ebd8; -fx-text-fill: #795b21;");
        badge.setMinWidth(Region.USE_PREF_SIZE);
        HBox titleLine = new HBox(8, title, badge);
        titleLine.setAlignment(Pos.CENTER_LEFT);

        String other = actor.getRole() == UserRole.SELLER ? thread.buyerUsername() : thread.sellerUsername();
        String participant = other == null ? "Deleted user" : "with @" + other;
        Label meta = new Label(participant + " · " + thread.createdAt().format(DATE));
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        meta.setWrapText(true);
        VBox details = new VBox(4, titleLine, meta);
        HBox.setHgrow(details, Priority.ALWAYS);

        Button button = new Button("Open Chat");
        button.getStyleClass().addAll("btn-outline", "btn-sm");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(click -> open.accept(thread));

        HBox card = new HBox(12, details, button);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 12 14;");
        return card;
    }

    private boolean current(long token,User actor){User now=user.get();return active&&request==token&&actor!=null&&now!=null&&Objects.equals(now.getUsername(),actor.getUsername());}
}
