package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Chat persistence; all methods use a caller-owned connection and never commit. */
public class ChatRepository {
    /**
     * Make agreed/historical deals reachable from Chats even after leaving Incoming Offers.
     *
     * <p>For the given buyer or seller, finds {@code ACCEPTED}/{@code COMPLETED}/
     * {@code RETURNED} deals with {@code deleted < 2} and both usernames present that have no
     * matching chat thread, then creates each missing thread. Caller-owned connection; this
     * method never commits.
     *
     * @param conn an open connection with auto-commit disabled; it is not closed
     * @param username the account whose deals are scanned
     * @param role {@link UserRole#BUYER} to scan buyer deals, any other value to scan seller deals
     * @throws SQLException when a query or insert fails, or a stored deal type is unknown
     */
    public void ensureDealThreads(Connection conn,String username,UserRole role) throws SQLException {
        String owner=role==UserRole.BUYER?"d.buyer_username":"d.seller_username";
        List<Deal> contexts=new ArrayList<>();
        try (PreparedStatement stmt=conn.prepareStatement("""
                SELECT DISTINCT d.listing_id,d.buyer_username,d.seller_username,d.dealType,l.itemName
                FROM deals d JOIN listings l ON l.id=d.listing_id
                WHERE d.status IN ('ACCEPTED','COMPLETED','RETURNED') AND
                d.deleted < 2 AND d.buyer_username IS NOT NULL AND d.seller_username IS NOT NULL AND
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

    /**
     * Locks and returns a listing for a chat authorization decision.
     *
     * <p>Caller-owned connection: this method never commits. The row is read with
     * {@code FOR UPDATE}; only id, seller, title, type and status are populated.
     *
     * @param conn an open connection with auto-commit disabled; it is not closed
     * @param id the listing ID
     * @return the locked listing summary, or {@code null} when no such listing exists
     * @throws SQLException when the query fails
     */
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
    /**
     * Returns the chat threads the user participates in, newest activity first.
     *
     * <p>Matches the buyer or seller username and excludes threads with {@code deleted >= 2}.
     * Ordering uses the highest message ID (0 for an empty thread), then creation time, then
     * thread ID. Caller-owned connection; this method never commits.
     *
     * @param conn an open connection; the caller owns its lifecycle
     * @param username the buyer or seller username
     * @return the user's visible threads; never {@code null}
     * @throws SQLException when the query fails
     */
    public List<ChatThread> findThreads(Connection conn, String username) throws SQLException {
        List<ChatThread> threads = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT t.* FROM chat_threads t
                WHERE (t.buyer_username = ? OR t.seller_username = ?) AND t.deleted < 2
                ORDER BY COALESCE((SELECT MAX(m.id) FROM chat_messages m WHERE m.thread_id=t.id),0) DESC,
                    t.created_at DESC, t.id ASC
                """)) {
            stmt.setString(1, username); stmt.setString(2, username);
            try (ResultSet rows = stmt.executeQuery()) { while (rows.next()) threads.add(thread(rows)); }
        }
        return threads;
    }

    /**
     * Returns one chat thread, optionally locking it for a caller-owned transaction.
     *
     * <p>Threads with {@code deleted >= 2} are treated as missing; when {@code lock} is true the
     * row is read with {@code FOR UPDATE}. Caller-owned connection; this method never commits.
     *
     * @param conn an open connection with auto-commit disabled; it is not closed
     * @param id the thread ID
     * @param lock true to append {@code FOR UPDATE}
     * @return the thread, or {@code null} when it does not exist or is double-deleted
     * @throws SQLException when the query fails
     */
    public ChatThread findThread(Connection conn, String id, boolean lock) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM chat_threads WHERE id = ? AND deleted < 2" + (lock ? " FOR UPDATE" : ""))) {
            stmt.setString(1, id);
            try (ResultSet rows = stmt.executeQuery()) { return rows.next() ? thread(rows) : null; }
        }
    }

    /**
     * Returns the thread for a listing/buyer/seller triple, inserting it when missing.
     *
     * <p>The insert relies on the unique key with {@code ON DUPLICATE KEY UPDATE}, so
     * concurrent callers converge on one thread, which is then re-read with {@code FOR UPDATE}.
     * Caller-owned connection: this method never commits.
     *
     * @param conn an open connection with auto-commit disabled; it is not closed
     * @param deal the deal whose listing, participants, title and type identify the thread
     * @return the existing or newly inserted thread, locked for the caller
     * @throws SQLException when the insert fails or the thread cannot be re-read
     */
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
                SELECT * FROM chat_threads WHERE listing_id=? AND buyer_username=? AND seller_username=?
                    AND deleted < 2 FOR UPDATE
                """)) {
            stmt.setString(1, deal.getListingId()); stmt.setString(2, deal.getBuyerUsername()); stmt.setString(3, deal.getSellerUsername());
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) throw new SQLException("Conversation could not be opened.");
                return thread(rows);
            }
        }
    }

    /**
     * Returns a thread's messages in ascending ID order.
     *
     * <p>When {@code search} is non-blank, only messages whose body contains it ({@code LOCATE})
     * are returned. Caller-owned connection; this method never commits.
     *
     * @param conn an open connection; the caller owns its lifecycle
     * @param threadId the thread whose messages are returned
     * @param search substring to filter by, or {@code null}/blank for every message
     * @return the messages, oldest first; never {@code null}
     * @throws SQLException when the query fails
     */
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

    /**
     * Returns the transcript message that recorded a specific proposal version.
     *
     * <p>Caller-owned connection; this method never commits.
     *
     * @param conn an open connection; the caller owns its lifecycle
     * @param dealId the proposal/deal ID stored on the message
     * @param version the proposal version stored on the message
     * @return the message, or {@code null} when no message matches
     * @throws SQLException when the query fails
     */
    public ChatMessage findProposalMessage(Connection conn, String dealId, long version) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM chat_messages WHERE proposal_id=? AND proposal_version=?")) {
            stmt.setString(1, dealId); stmt.setLong(2, version);
            try (ResultSet rows = stmt.executeQuery()) { return rows.next() ? message(rows) : null; }
        }
    }

    /**
     * Inserts one chat message and returns the stored row.
     *
     * <p>The generated message ID is read back and the row is re-selected so database defaults
     * are reflected. Caller-owned connection; this method never commits.
     *
     * @param conn an open connection; the caller owns its lifecycle
     * @param value the message to store; its fields are written as supplied
     * @return the stored message including its generated ID
     * @throws SQLException when the insert or re-read fails
     */
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
