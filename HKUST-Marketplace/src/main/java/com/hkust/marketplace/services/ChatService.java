package com.hkust.marketplace.services;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.*;
import com.hkust.marketplace.utils.DatabaseManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Shared buyer/seller private messaging. Messages and recipient notifications commit together. */
public class ChatService {
    private final ChatRepository chats;
    private final DealRepository deals;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final DealRepository.ConnectionProvider connections;

    public ChatService() {
        this(new ChatRepository(),new DealRepository(),new UserRepository(),new NotificationRepository(),DatabaseManager::getConnection);
    }

    public ChatService(ChatRepository chats, DealRepository deals, UserRepository users,
                       NotificationRepository notifications, DealRepository.ConnectionProvider connections) {
        this.chats=Objects.requireNonNull(chats); this.deals=Objects.requireNonNull(deals); this.users=Objects.requireNonNull(users);
        this.notifications=Objects.requireNonNull(notifications); this.connections=Objects.requireNonNull(connections);
    }

    public List<ChatThread> getInbox(User actor) throws SQLException {
        return transaction(conn -> {
            User current=requireActor(conn,actor);
            chats.ensureDealThreads(conn,current.getUsername(),current.getRole());
            return chats.findThreads(conn,current.getUsername()).stream().filter(thread->participant(current,thread)).toList();
        });
    }

    /** Reuse a listing/pair conversation across proposals and deal state changes. */
    public ChatThread openForDeal(User actor, String dealId) throws SQLException {
        identifier(dealId);
        return transaction(conn -> {
            Deal deal=deals.findByIdForUpdate(conn,dealId).orElseThrow(ChatService::unavailable);
            User current=requireActor(conn,actor);
            boolean owns=current.getRole()==UserRole.BUYER && current.getUsername().equals(deal.getBuyerUsername())
                    || current.getRole()==UserRole.SELLER && current.getUsername().equals(deal.getSellerUsername());
            if (!owns) throw unavailable();
            return chats.findOrCreate(conn,deal);
        });
    }

    /** Buyer integration point: chat can start from visible listing details before an offer exists. */
    public ChatThread openForListing(User buyer, String listingId) throws SQLException {
        identifier(listingId);
        return transaction(conn -> {
            Listing listing=chats.findListingForChat(conn,listingId);
            User current=requireActor(conn,buyer);
            if (current.getRole()!=UserRole.BUYER || listing==null
                    || !Set.of(ListingStatus.LIVE,ListingStatus.RESERVED,ListingStatus.SOLD,ListingStatus.LENT,ListingStatus.GIVEN).contains(listing.getStatus())) {
                throw unavailable();
            }
            User seller=listing.getSellerUsername()==null?null:users.findByUsername(conn,listing.getSellerUsername());
            if (seller==null || !seller.isActive() || seller.getRole()!=UserRole.SELLER) throw unavailable();
            Deal context=new Deal();context.setListingId(listing.getId());context.setListingTitle(listing.getTitle());
            context.setDealType(listing.getType());context.setBuyerUsername(current.getUsername());context.setSellerUsername(seller.getUsername());
            return chats.findOrCreate(conn,context);
        });
    }

    public ChatConversation getConversation(User actor, String threadId, String search) throws SQLException {
        identifier(threadId);
        if (search!=null && search.codePointCount(0,search.length())>4000) throw new IllegalArgumentException("Search text is too long.");
        return transaction(conn -> {
            User current=requireActor(conn,actor);
            ChatThread thread=requireThread(conn,current,threadId,false);
            return new ChatConversation(thread,chats.findMessages(conn,threadId,search),recipient(conn,current,thread)!=null);
        });
    }

    public ChatMessage sendMessage(User actor, String threadId, String text) throws SQLException {
        identifier(threadId);
        String body=text==null?"":text.strip();
        if (body.isBlank() || body.codePointCount(0,body.length())>4000) {
            throw new IllegalArgumentException("Enter a message of 1 to 4,000 characters.");
        }
        return transaction(conn -> {
            User current=requireActor(conn,actor);
            ChatThread thread=requireThread(conn,current,threadId,true);
            User recipient=requireRecipient(conn,current,thread);
            ChatMessage message=chats.insert(conn,new ChatMessage(0,threadId,current.getRole(),current.getUsername(),
                    ChatMessage.Kind.TEXT,body,null,null,null,null,null,null,null));
            notifyRecipient(conn,thread,recipient,message);
            return message;
        });
    }

    /**
     * Share an existing buyer PENDING proposal or seller COUNTERED proposal as an
     * immutable formal message. Buyer submission services can call this after saving
     * a proposal. Repeating the same deal version does not duplicate messages/notifications.
     */
    public ChatMessage sendProposal(User actor, String threadId, String dealId) throws SQLException {
        identifier(threadId);identifier(dealId);
        return transaction(conn -> {
            // Follow the same listing/deal -> thread lock order as openForDeal.
            Deal deal=deals.findByIdForUpdate(conn,dealId).orElseThrow(ChatService::unavailable);
            User current=requireActor(conn,actor);
            ChatThread thread=requireThread(conn,current,threadId,true);
            if (!Objects.equals(thread.listingId(),deal.getListingId())
                    || !Objects.equals(thread.buyerUsername(),deal.getBuyerUsername())
                    || !Objects.equals(thread.sellerUsername(),deal.getSellerUsername())) throw unavailable();
            ChatMessage existing=chats.findProposalMessage(conn,dealId,deal.getRowVersion());
            if (existing!=null) return existing;
            boolean counter=current.getRole()==UserRole.SELLER;
            if (counter ? deal.getStatus()!=DealStatus.COUNTERED : deal.getStatus()!=DealStatus.PENDING) {
                throw new IllegalArgumentException("Only your own pending proposal or current counteroffer can be shared.");
            }
            User recipient=requireRecipient(conn,current,thread);
            Double price=counter?deal.getCounterOfferPrice():deal.getOfferPrice();
            var start=counter?deal.getCounterBorrowStart():deal.getBorrowStart();
            var end=counter?deal.getCounterBorrowEnd():deal.getBorrowEnd();
            String terms=switch(deal.getDealType()) {
                case SELL -> "Sale price: HK$ "+price;
                case LEND -> "Borrowing period: "+start+" to "+end;
                case GIVEAWAY -> "Giveaway claim";
            };
            ChatMessage message=chats.insert(conn,new ChatMessage(0,threadId,current.getRole(),current.getUsername(),
                    counter?ChatMessage.Kind.COUNTER:ChatMessage.Kind.PROPOSAL,terms,dealId,deal.getRowVersion(),
                    deal.getDealType(),price,start,end,null));
            notifyRecipient(conn,thread,recipient,message);
            return message;
        });
    }

    private User requireActor(Connection conn, User actor) throws SQLException {
        User current=actor==null || actor.getUsername()==null || actor.getUsername().isBlank()?null:users.findByUsername(conn,actor.getUsername());
        if (current==null || !current.isActive() || current.getRole()==UserRole.ADMIN) {
            throw new IllegalArgumentException("An active buyer or seller account is required for private chat.");
        }
        return current;
    }

    private ChatThread requireThread(Connection conn,User actor,String id,boolean lock)throws SQLException {
        ChatThread thread=chats.findThread(conn,id,lock);
        if(thread==null || !participant(actor,thread))throw unavailable();return thread;
    }

    private static boolean participant(User user,ChatThread thread) {
        return user.getRole()==UserRole.BUYER && user.getUsername().equals(thread.buyerUsername())
                || user.getRole()==UserRole.SELLER && user.getUsername().equals(thread.sellerUsername());
    }

    private User recipient(Connection conn,User actor,ChatThread thread)throws SQLException {
        boolean buyer=actor.getRole()==UserRole.BUYER;
        String name=buyer?thread.sellerUsername():thread.buyerUsername();
        User other=name==null?null:users.findByUsername(conn,name);
        return other!=null && other.isActive() && other.getRole()==(buyer?UserRole.SELLER:UserRole.BUYER)?other:null;
    }

    private User requireRecipient(Connection conn,User actor,ChatThread thread)throws SQLException {
        User other=recipient(conn,actor,thread);
        if(other==null)throw new IllegalArgumentException("The other account is unavailable. This conversation is read-only.");return other;
    }

    private void notifyRecipient(Connection conn,ChatThread thread,User recipient,ChatMessage message)throws SQLException {
        String body=message.body();
        if(body.codePointCount(0,body.length())>500)body=body.substring(0,body.offsetByCodePoints(0,500))+"…";
        notifications.create(conn,new Notification(recipient.getUsername(),"New Chat Message",
                "@"+message.senderName()+" — "+thread.listingTitle()+"\n"+body,"Chat"));
    }

    private static void identifier(String id){if(id==null || id.isBlank() || id.length()>50)throw new IllegalArgumentException("A valid conversation or proposal ID is required.");}
    private static IllegalArgumentException unavailable(){return new IllegalArgumentException("This conversation is not available to your account.");}
    @FunctionalInterface private interface Work<T>{T run(Connection conn)throws SQLException;}
    private <T>T transaction(Work<T> work)throws SQLException {
        try(Connection conn=connections.getConnection()) {
            conn.setAutoCommit(false);
            try {T value=work.run(conn);conn.commit();return value;}
            catch(SQLException|RuntimeException failure){try{conn.rollback();}catch(SQLException rb){failure.addSuppressed(rb);}throw failure;}
        }
    }
}
