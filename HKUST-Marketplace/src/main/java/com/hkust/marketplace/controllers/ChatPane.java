package com.hkust.marketplace.controllers;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.services.ChatService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared conversation view. Polling stops on navigation; history/search remain server-authorized. */
public class ChatPane extends VBox {
    private final ChatService service;
    private final Supplier<User> user;
    private final String threadId;
    private final VBox messages=new VBox(10);
    private final ScrollPane scroll=new ScrollPane(messages);
    private final Label heading=new Label("Private Chat"), feedback=new Label();
    private final TextArea compose=new TextArea();
    private final TextField search=new TextField();
    private final Button send=new Button("Send"), refresh=new Button("Refresh"), find=new Button("Search"), clear=new Button("Clear");
    private final Timeline polling;
    private boolean active=true,loading,saving,canSend;
    private long request;
    private String activeSearch="";
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");

    public ChatPane(ChatService service,Supplier<User> user,String threadId,Runnable back) {
        this(service,user,threadId,back,null);
    }

    public ChatPane(ChatService service,Supplier<User> user,String threadId,Runnable back,Runnable propose) {
        super(12);this.service=Objects.requireNonNull(service);this.user=Objects.requireNonNull(user);this.threadId=Objects.requireNonNull(threadId);
        setPadding(new Insets(4));heading.setWrapText(true);heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Button leave=new Button("Back to Chats");leave.getStyleClass().add("btn-outline");leave.setOnAction(e->back.run());
        refresh.getStyleClass().add("btn-outline");refresh.setOnAction(e->refresh());
        search.setPromptText("Search this conversation");HBox.setHgrow(search,Priority.ALWAYS);
        find.setOnAction(e->{activeSearch=search.getText().strip();refresh();});search.setOnAction(e->find.fire());
        clear.setOnAction(e->{search.clear();activeSearch="";refresh();});
        feedback.setWrapText(true);feedback.setStyle("-fx-text-fill: #5a5a7a;");
        scroll.setFitToWidth(true);VBox.setVgrow(scroll,Priority.ALWAYS);
        compose.setWrapText(true);compose.setPrefRowCount(3);compose.setPromptText("Write a private message (up to 4,000 characters)");
        send.getStyleClass().add("btn-primary");send.setOnAction(e->send());
        compose.textProperty().addListener((obs,old,value)->updateControls());
        HBox navigation=new HBox(12,leave,refresh);
        if(propose!=null && user.get()!=null && user.get().getRole()==UserRole.BUYER) {
            Button formal=new Button("Make Formal Proposal");
            formal.getStyleClass().add("btn-primary");
            formal.setOnAction(e->propose.run());
            navigation.getChildren().add(formal);
        }
        getChildren().addAll(navigation,heading,new HBox(10,search,find,clear),feedback,scroll,compose,send);
        updateControls();
        polling=new Timeline(new KeyFrame(Duration.seconds(4),e->{if(!loading&&!saving)load(false);}));polling.setCycleCount(Timeline.INDEFINITE);polling.play();
    }

    public void deactivate(){active=false;request++;polling.stop();}
    public void refresh(){load(true);}

    private void load(boolean explicit) {
        if(!active||loading||saving)return;loading=true;User actor=user.get();long token=++request;String filter=activeSearch;
        boolean bottom=scroll.getVvalue()>0.95||messages.getChildren().isEmpty();updateControls();
        if(explicit)feedback.setText("Loading conversation...");
        Task<ChatConversation> task=new Task<>(){protected ChatConversation call()throws SQLException{return service.getConversation(actor,threadId,filter);}};
        task.setOnSucceeded(e->{if(!current(token,actor))return;loading=false;ChatConversation value=task.getValue();canSend=value.canSend();
            ChatThread thread=value.thread();String other=actor.getRole()==UserRole.SELLER?thread.buyerName():thread.sellerName();heading.setText(thread.listingTitle()+" — @"+other);
            messages.getChildren().clear();
            if(value.messages().isEmpty())messages.getChildren().add(new Label(filter.isBlank()?"No messages yet. Start the conversation below.":"No matching messages."));
            for(ChatMessage message:value.messages())messages.getChildren().add(messageCard(message,actor));
            feedback.setText(canSend?(filter.isBlank()?"Messages refresh automatically.":"Showing matching messages."):"The other account is unavailable. Saved history is read-only.");
            updateControls();if(bottom)Platform.runLater(()->{if(current(token,actor))scroll.setVvalue(1);});
        });
        task.setOnFailed(e->{if(!current(token,actor))return;loading=false;feedback.setText(error(task.getException(),"Unable to load chat. Use Refresh to try again."));updateControls();});
        start(task,"chat-message-load");
    }

    private VBox messageCard(ChatMessage message,User actor) {
        boolean mine=message.senderRole()==actor.getRole();
        Label meta=new Label((mine?"You":"@"+message.senderName())+" · "+message.createdAt().format(DATE));
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 11px;");meta.setWrapText(true);
        Label body=new Label(message.body());body.setWrapText(true);
        VBox card=new VBox(6,meta);
        if(message.kind()!=ChatMessage.Kind.TEXT){Label kind=new Label(message.kind()==ChatMessage.Kind.COUNTER?"Formal counteroffer":"Formal proposal");kind.setStyle("-fx-font-weight: bold;");card.getChildren().add(kind);}
        card.getChildren().add(body);card.setPadding(new Insets(12));card.setStyle("-fx-background-radius: 10; -fx-background-color: "+(mine?"#e8f0f8":"#ffffff")+";");return card;
    }

    private void send() {
        if(!active||saving||loading||!canSend)return;
        String body=compose.getText().strip();if(body.isBlank()||body.codePointCount(0,body.length())>4000){feedback.setText("Enter a message of 1 to 4,000 characters.");return;}
        User actor=user.get();saving=true;long token=++request;updateControls();feedback.setText("Sending...");
        Task<ChatMessage> task=new Task<>(){protected ChatMessage call()throws SQLException{return service.sendMessage(actor,threadId,body);}};
        task.setOnSucceeded(e->{if(!current(token,actor))return;saving=false;compose.clear();activeSearch="";search.clear();updateControls();load(true);});
        task.setOnFailed(e->{if(!current(token,actor))return;saving=false;feedback.setText(error(task.getException(),"Unable to send. Your message is kept below; refresh before retrying."));updateControls();});
        start(task,"chat-message-send");
    }

    private void updateControls(){refresh.setDisable(loading||saving);find.setDisable(loading||saving);clear.setDisable(loading||saving);search.setDisable(saving);
        compose.setDisable(saving||!canSend);String body=compose.getText();send.setDisable(loading||saving||!canSend||body.isBlank()||body.codePointCount(0,body.length())>4000);}
    private boolean current(long token,User actor){User now=user.get();return active&&request==token&&actor!=null&&now!=null&&Objects.equals(actor.getUsername(),now.getUsername());}
    private static String error(Throwable failure,String fallback){return failure instanceof IllegalArgumentException?failure.getMessage():fallback;}
    private static void start(Task<?> task,String name){Thread thread=new Thread(task,name);thread.setDaemon(true);thread.start();}
}
