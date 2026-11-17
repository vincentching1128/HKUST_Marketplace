package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.services.ChatService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.*;

/** Shared private-conversation inbox for the buyer and seller portals. */
public class ChatInboxPane extends VBox {
    private final ChatService service;
    private final Supplier<User> user;
    private final Consumer<ChatThread> open;
    private final VBox rows=new VBox(12);
    private final Button refresh=new Button("Refresh");
    private boolean active=true;
    private long request;

    public ChatInboxPane(ChatService service,Supplier<User> user,Consumer<ChatThread> open) {
        super(12);this.service=Objects.requireNonNull(service);this.user=Objects.requireNonNull(user);this.open=Objects.requireNonNull(open);
        setPadding(new Insets(4));refresh.getStyleClass().add("btn-outline");refresh.setOnAction(e->refresh());
        ScrollPane scroll=new ScrollPane(rows);scroll.setFitToWidth(true);VBox.setVgrow(scroll,Priority.ALWAYS);
        getChildren().addAll(refresh,scroll);
    }

    public void deactivate(){active=false;request++;}

    public void refresh(){
        if(!active)return;User actor=user.get();long token=++request;refresh.setDisable(true);
        rows.getChildren().setAll(new Label("Loading conversations..."));
        Task<List<ChatThread>> task=new Task<>(){protected List<ChatThread> call()throws SQLException{return service.getInbox(actor);}};
        task.setOnSucceeded(e->{if(!current(token,actor))return;refresh.setDisable(false);rows.getChildren().clear();
            if(task.getValue().isEmpty())rows.getChildren().add(new Label("No conversations yet. Open Chat from an incoming proposal to begin."));
            for(ChatThread thread:task.getValue()){
                Label title=new Label(thread.listingTitle());title.setWrapText(true);title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
                String other=actor.getRole()==UserRole.SELLER?thread.buyerName():thread.sellerName();
                Button button=new Button("Open Chat");button.getStyleClass().add("btn-outline");button.setOnAction(click->open.accept(thread));
                VBox card=new VBox(10,title,new Label("Conversation with @"+other),button);card.getStyleClass().add("card");card.setPadding(new Insets(16));rows.getChildren().add(card);
            }
        });
        task.setOnFailed(e->{if(!current(token,actor))return;refresh.setDisable(false);Label error=new Label(task.getException() instanceof IllegalArgumentException
                ?task.getException().getMessage():"Unable to load conversations. Refresh to try again.");error.setWrapText(true);rows.getChildren().setAll(error);});
        Thread thread=new Thread(task,"chat-inbox-load");thread.setDaemon(true);thread.start();
    }

    private boolean current(long token,User actor){User now=user.get();return active&&request==token&&actor!=null&&now!=null&&Objects.equals(now.getUsername(),actor.getUsername());}
}
