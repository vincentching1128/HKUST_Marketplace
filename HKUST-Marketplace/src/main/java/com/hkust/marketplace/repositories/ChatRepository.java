package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Chat persistence; all methods use a caller-owned connection and never commit. */
public class ChatRepository {
    /** Make agreed/historical deals reachable from Chats even after leaving Incoming Offers. */
    public void ensureDealThreads(Connection conn,String username,UserRole role) throws SQLException {
        String owner=role==UserRole.BUYER?"d.buyer_username":"d.seller_username";
        List<Deal> contexts=new ArrayList<>();
        try (PreparedStatement stmt=conn.prepareStatement("""
                SELECT DISTINCT d.listing_id,d.buyer_username,d.seller_username,d.dealType,l.itemName
                FROM deals d JOIN listings l ON l.id=d.listing_id
                WHERE d.status IN ('ACCEPTED','COMPLETED','RETURNED') AND
                """+owner+" = ? AND NOT EXISTS (SELECT 1 FROM chat_threads t WHERE t.listing_id=d.listing_id "
                +"AND t.buyer_username=d.buyer_username AND t.seller_username=d.seller_username)")) {
            stmt.setString(1,username);
            try(ResultSet rows=stmt.executeQuery()) {
                while(rows.next()) {
                    Deal deal=new Deal();deal.setListingId(rows.getString("listing_id"));deal.setListingTitle(rows.getString("itemName"));
                    deal.setBuyerUsername(rows.getString("buyer_username"));deal.setSellerUsername(rows.getString("seller_username"));
                    deal.setDealType(switch(rows.getString("dealType")){case "BOUGHT"->ListingType.SELL;case "BORROWED"->ListingType.LEND;case "RECEIVED"->ListingType.GIVEAWAY;default->throw new SQLException("Unknown deal type.");});
                    contexts.add(deal);
                }
            }
        }
        for(Deal deal:contexts)findOrCreate(conn,deal);
    }

    public Listing findListingForChat(Connection conn, String id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT id,seller_username,itemName,listingType,listingStatus FROM listings WHERE id=? FOR UPDATE")) {
            stmt.setString(1, id);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) return null;
                Listing listing = new Listing(); listing.setId(rows.getString("id"));
                listing.setSellerUsername(rows.getString("seller_username")); listing.setTitle(rows.getString("itemName"));
                listing.setType(ListingType.valueOf(rows.getString("listingType")));
                listing.setStatus(ListingStatus.valueOf(rows.getString("listingStatus"))); return listing;
            }
        }
    }
    public List<ChatThread> findThreads(Connection conn, String username) throws SQLException {
        List<ChatThread> threads = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT t.* FROM chat_threads t
                WHERE t.buyer_username = ? OR t.seller_username = ?
                ORDER BY COALESCE((SELECT MAX(m.id) FROM chat_messages m WHERE m.thread_id=t.id),0) DESC,
                    t.created_at DESC, t.id ASC
                """)) {
            stmt.setString(1, username); stmt.setString(2, username);
            try (ResultSet rows = stmt.executeQuery()) { while (rows.next()) threads.add(thread(rows)); }
        }
        return threads;
    }

    public ChatThread findThread(Connection conn, String id, boolean lock) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM chat_threads WHERE id = ?" + (lock ? " FOR UPDATE" : ""))) {
            stmt.setString(1, id);
            try (ResultSet rows = stmt.executeQuery()) { return rows.next() ? thread(rows) : null; }
        }
    }

    public ChatThread findOrCreate(Connection conn, Deal deal) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO chat_threads(id,listing_id,listing_title,listing_type,buyer_username,seller_username,buyer_name,seller_name)
                VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=chat_threads.id
                """)) {
            stmt.setString(1, UUID.randomUUID().toString()); stmt.setString(2, deal.getListingId());
            stmt.setString(3, deal.getListingTitle()); stmt.setString(4, deal.getDealType().name());
            stmt.setString(5, deal.getBuyerUsername()); stmt.setString(6, deal.getSellerUsername());
            stmt.setString(7, deal.getBuyerUsername()); stmt.setString(8, deal.getSellerUsername()); stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT * FROM chat_threads WHERE listing_id=? AND buyer_username=? AND seller_username=? FOR UPDATE
                """)) {
            stmt.setString(1, deal.getListingId()); stmt.setString(2, deal.getBuyerUsername()); stmt.setString(3, deal.getSellerUsername());
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) throw new SQLException("Conversation could not be opened.");
                return thread(rows);
            }
        }
    }

    public List<ChatMessage> findMessages(Connection conn, String threadId, String search) throws SQLException {
        List<ChatMessage> messages = new ArrayList<>();
        boolean filter = search != null && !search.isBlank();
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM chat_messages WHERE thread_id=?"
                + (filter ? " AND LOCATE(?,body)>0" : "") + " ORDER BY id ASC")) {
            stmt.setString(1, threadId); if (filter) stmt.setString(2, search.strip());
            try (ResultSet rows = stmt.executeQuery()) { while (rows.next()) messages.add(message(rows)); }
        }
        return messages;
    }

    public ChatMessage findProposalMessage(Connection conn, String dealId, long version) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM chat_messages WHERE proposal_id=? AND proposal_version=?")) {
            stmt.setString(1, dealId); stmt.setLong(2, version);
            try (ResultSet rows = stmt.executeQuery()) { return rows.next() ? message(rows) : null; }
        }
    }

    public ChatMessage insert(Connection conn, ChatMessage value) throws SQLException {
        String sql = """
                INSERT INTO chat_messages(thread_id,sender_role,sender_name,message_type,body,proposal_id,
                    proposal_version,proposal_type,proposal_price,borrow_start,borrow_end)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """;
        long id;
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, value.threadId()); stmt.setString(2, value.senderRole().name()); stmt.setString(3, value.senderName());
            stmt.setString(4, value.kind().name()); stmt.setString(5, value.body()); stmt.setString(6, value.proposalId());
            stmt.setObject(7, value.proposalVersion()); stmt.setString(8, value.proposalType() == null ? null : value.proposalType().name());
            stmt.setBigDecimal(9, value.proposalPrice() == null ? null : BigDecimal.valueOf(value.proposalPrice()));
            stmt.setTimestamp(10, timestamp(value.borrowStart())); stmt.setTimestamp(11, timestamp(value.borrowEnd()));
            if (stmt.executeUpdate() != 1) throw new SQLException("Message could not be saved.");
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Message ID was not returned.");
                id = keys.getLong(1);
            }
        }
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM chat_messages WHERE id=?")) {
            stmt.setLong(1, id);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) throw new SQLException("Saved message could not be loaded.");
                return message(rows);
            }
        }
    }

    private ChatThread thread(ResultSet rows) throws SQLException {
        return new ChatThread(rows.getString("id"),rows.getString("listing_id"),rows.getString("listing_title"),
                ListingType.valueOf(rows.getString("listing_type")),rows.getString("buyer_username"),rows.getString("seller_username"),
                rows.getString("buyer_name"),rows.getString("seller_name"),date(rows,"created_at"));
    }

    private ChatMessage message(ResultSet rows) throws SQLException {
        long version = rows.getLong("proposal_version"); Long nullableVersion = rows.wasNull() ? null : version;
        String type = rows.getString("proposal_type"); BigDecimal price = rows.getBigDecimal("proposal_price");
        return new ChatMessage(rows.getLong("id"),rows.getString("thread_id"),UserRole.valueOf(rows.getString("sender_role")),
                rows.getString("sender_name"),ChatMessage.Kind.valueOf(rows.getString("message_type")),rows.getString("body"),
                rows.getString("proposal_id"),nullableVersion,type == null ? null : ListingType.valueOf(type),
                price == null ? null : price.doubleValue(),date(rows,"borrow_start"),date(rows,"borrow_end"),date(rows,"created_at"));
    }

    private static LocalDateTime date(ResultSet rows,String column) throws SQLException {
        Timestamp value=rows.getTimestamp(column); return value==null?null:value.toLocalDateTime();
    }
    private static Timestamp timestamp(LocalDateTime value) { return value==null?null:Timestamp.valueOf(value); }
}
