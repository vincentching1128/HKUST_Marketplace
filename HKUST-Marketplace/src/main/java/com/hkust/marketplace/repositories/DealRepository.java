package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealHistoryEntry;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import java.util.Objects;

/**
 * Database access for deals (offers / borrow requests / claims) between a buyer and a seller.
 * <p>
 * Creates PENDING proposals and stores explicit proposal/deal lifecycle states.
 * Callers must authorize reads in the service layer; repository filters are not authorization.
 */
public class DealRepository {

    /**
     * Supplies connections for deal persistence and lifecycle transactions.
     *
     * <p>Implementations must return a fresh connection; transaction boundaries remain the
     * caller's responsibility.
     */
    @FunctionalInterface
    public interface ConnectionProvider {
        /**
         * Returns a connection for one deal operation.
         *
         * @return an open connection that the caller closes
         * @throws SQLException when a connection cannot be obtained
         */
        Connection getConnection() throws SQLException;
    }

    private final ConnectionProvider connections;
    private static final String SELECT_DEALS = "SELECT d.*, l.itemName AS listing_title "
            + "FROM deals d JOIN listings l ON l.id = d.listing_id ";
    private static final String NEWEST_FIRST = " ORDER BY d.createdDate DESC, d.id ASC";
    private static final String OPEN_STATUSES = "('PENDING','COUNTERED','ACCEPTED')";

    /**
     * Creates a repository backed by {@link DatabaseManager#getConnection()}.
     */
    public DealRepository() { this(DatabaseManager::getConnection); }

    /**
     * Creates a repository using the supplied connection provider.
     *
     * @param connections the provider used for every deal operation; must not be {@code null}
     * @throws NullPointerException when {@code connections} is {@code null}
     */
    public DealRepository(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /**
     * Returns one deal by ID, excluding double-deleted records.
     *
     * <p>Uses a new connection and joins the listing title. Deals with {@code deleted = 2} are
     * not returned; {@code deleted = 1} records remain visible.
     *
     * @param id the deal ID
     * @return the deal, or {@link Optional#empty()} when it does not exist or is double-deleted
     * @throws IllegalArgumentException when the ID is blank or longer than 50 code points
     * @throws SQLException when the query fails
     */
    public Optional<Deal> findById(String id) throws SQLException {
        requireIdentifier(id, "Deal ID");
        return query("WHERE d.id = ?", List.of(id)).stream().findFirst();
    }

    /**
     * Returns the deals in which the user is the buyer, excluding double-deleted records.
     *
     * <p>Uses a new connection. Rows with {@code deleted = 2} are excluded, while
     * {@code deleted = 1} rows remain visible to the remaining participant. Reads are not
     * authorization; the service must check the caller's access.
     *
     * @param username the buyer's username
     * @return the buyer's deals, newest first; never {@code null}
     * @throws IllegalArgumentException when the username is blank or longer than 50 code points
     * @throws SQLException when the query fails
     */
    public List<Deal> findByBuyer(String username) throws SQLException {
        requireIdentifier(username, "Buyer username");
        return query("WHERE d.buyer_username = ?", List.of(username));
    }

    /**
     * Returns the buyer's closed deals ({@code COMPLETED} or {@code RETURNED}) with review data
     * for the history screen.
     *
     * <p>Uses a new connection. Each entry carries the listing title/category, seller username
     * and full name (possibly {@code null} after a permanent user deletion), the close time
     * ({@code returnedDate}, else {@code completedDate}), the stored deal type and status, the
     * agreed terms, and the review rating/comment/seller reply plus its {@code hidden} flag
     * (hidden reviews are included). Rows are not filtered by the {@code deleted} counter, and
     * no identifier validation is performed.
     *
     * @param username the buyer's username
     * @return closed deals for the buyer, newest close time first; never {@code null}
     * @throws SQLException when the query fails or a stored deal type/status is unknown
     */
    public List<DealHistoryEntry> findBuyerHistory(String username) throws SQLException {
        String sql = """
                SELECT d.id, l.itemName, l.category, d.seller_username, u.full_name,
                       COALESCE(d.returnedDate, d.completedDate) AS closed_at, d.dealType, d.status,
                       r.rating, r.comment, r.seller_reply, r.hidden, d.offeredPrice, d.borrowStartDate, d.borrowDueDate
                FROM deals d JOIN listings l ON l.id = d.listing_id
                LEFT JOIN users u ON u.username = d.seller_username
                LEFT JOIN reviews r ON r.deal_id = d.id
                WHERE d.buyer_username = ? AND d.status IN ('COMPLETED','RETURNED')
                ORDER BY closed_at DESC, d.id
                """;
        try (Connection connection = connections.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                List<DealHistoryEntry> history = new ArrayList<>();
                while (rows.next()) {
                    history.add(new DealHistoryEntry(rows.getString("id"), rows.getString("itemName"),
                            rows.getString("category"), rows.getString("seller_username"), rows.getString("full_name"),
                            readDateTime(rows, "closed_at"), rows.getString("dealType"),
                            DealStatus.valueOf(rows.getString("status")), rows.getObject("rating", Integer.class),
                            rows.getString("comment"), rows.getString("seller_reply"), rows.getBoolean("hidden"),
                            new com.hkust.marketplace.models.Listing.AgreedTerms(rows.getBigDecimal("offeredPrice"),
                                    readDateTime(rows, "borrowStartDate"), readDateTime(rows, "borrowDueDate"))));
                }
                return history;
            }
        }
    }

    /**
     * Returns the deals in which the user is the seller, excluding double-deleted records.
     *
     * <p>Uses a new connection. Rows with {@code deleted = 2} are excluded, while
     * {@code deleted = 1} rows remain visible to the remaining participant.
     *
     * @param username the seller's username
     * @return the seller's deals, newest first; never {@code null}
     * @throws IllegalArgumentException when the username is blank or longer than 50 code points
     * @throws SQLException when the query fails
     */
    public List<Deal> findBySeller(String username) throws SQLException {
        requireIdentifier(username, "Seller username");
        return query("WHERE d.seller_username = ?", List.of(username));
    }

    /**
     * Returns the deals proposed for one listing, excluding double-deleted records.
     *
     * <p>Uses a new connection; all statuses are included.
     *
     * @param listingId the listing ID
     * @return the listing's deals, newest first; never {@code null}
     * @throws IllegalArgumentException when the listing ID is blank or longer than 50 code points
     * @throws SQLException when the query fails
     */
    public List<Deal> findByListing(String listingId) throws SQLException {
        requireIdentifier(listingId, "Listing ID");
        return query("WHERE d.listing_id = ?", List.of(listingId));
    }

    /**
     * Preview the deals affected by a listing change. With the current schema all
     * pending/countered/accepted records are affected, including ongoing borrowing. This does not mean
     * they can all be cancelled: the service must decide which changes are allowed.
     * Terminal history (DECLINED, COMPLETED, RETURNED, CANCELLED) is excluded.
     *
     * <p>Uses a new connection and does not lock; see
     * {@link #findAffectedDeals(Connection, String)} for the recheck applied inside a transaction.
     * Rows with {@code deleted = 2} are excluded.
     *
     * @param listingId the listing ID
     * @return the open (PENDING/COUNTERED/ACCEPTED) deals, newest first; never {@code null}
     * @throws IllegalArgumentException when the listing ID is blank or longer than 50 code points
     * @throws SQLException when the query fails
     */
    public List<Deal> findAffectedDeals(String listingId) throws SQLException {
        requireIdentifier(listingId, "Listing ID");
        return query("WHERE d.listing_id = ? AND d.status IN " + OPEN_STATUSES, List.of(listingId));
    }

    /**
     * Recheck affected deals when applying a confirmed listing change. The caller
     * owns this transaction and must authorize the action, apply changes, and commit
     * or roll back. Lock the listing first, matching create(), so a concurrent create
     * cannot add a deal between this check and the listing update.
     * A confirmation preview alone must never authorize cancellation of new deals.
     *
     * <p>The listing row is locked with {@code FOR UPDATE} first, then the open deals are read
     * with {@code FOR UPDATE}. The caller owns the connection and its commit/rollback.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param listingId the listing whose open deals are rechecked
     * @return the locked open (PENDING/COUNTERED/ACCEPTED) deals with {@code deleted < 2}, newest
     *         first; never {@code null}
     * @throws IllegalArgumentException when the connection is not transactional, the listing ID is
     *         invalid, or the listing does not exist
     * @throws SQLException when the queries fail
     */
    public List<Deal> findAffectedDeals(Connection connection, String listingId) throws SQLException {
        requireTransaction(connection);
        requireIdentifier(listingId, "Listing ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM listings WHERE id = ? FOR UPDATE")) {
            statement.setString(1, listingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("The listing does not exist.");
            }
        }
        List<Deal> affected = new ArrayList<>();
        String sql = SELECT_DEALS + "WHERE d.listing_id = ? AND d.status IN " + OPEN_STATUSES
                + " AND d.deleted < 2" + NEWEST_FIRST + " FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) affected.add(mapRow(rows));
            }
        }
        return affected;
    }

    /**
     * Optional filters; created-date bounds are inclusive. Keyword is literal text.
     *
     * <p>Uses a new connection. The keyword is matched with {@code LOCATE} against the listing
     * title, buyer username, seller username and deal ID (case sensitivity follows the database
     * collation); blank/null filters are ignored. {@code type} is mapped to the stored deal type
     * and {@code status} to a known {@link DealStatus} name (case-insensitive). Rows with
     * {@code deleted = 2} are excluded.
     *
     * @param keyword literal text to find, or {@code null}/blank for no keyword filter
     * @param type the listing type to match, or {@code null} for all types
     * @param status the deal status name to match, or {@code null}/blank for all statuses
     * @param from inclusive earliest created date, or {@code null} for unbounded
     * @param to inclusive latest created date, or {@code null} for unbounded
     * @return matching deals, newest first; never {@code null}
     * @throws IllegalArgumentException when {@code from} is after {@code to} or {@code status}
     *         is not a known deal status
     * @throws SQLException when the query fails
     */
    public List<Deal> searchDeals(String keyword, ListingType type, String status,
                                  LocalDate from, LocalDate to) throws SQLException {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Start date must not be after end date.");
        }
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOCATE(?, l.itemName) > 0 OR LOCATE(?, d.buyer_username) > 0"
                    + " OR LOCATE(?, d.seller_username) > 0 OR LOCATE(?, d.id) > 0)");
            for (int i = 0; i < 4; i++) parameters.add(keyword.strip());
        }
        if (type != null) {
            where.append(" AND d.dealType = ?");
            parameters.add(databaseType(type));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND d.status = ?");
            parameters.add(databaseStatus(status));
        }
        if (from != null) {
            where.append(" AND d.createdDate >= ?");
            parameters.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            where.append(" AND DATE(d.createdDate) <= ?");
            parameters.add(java.sql.Date.valueOf(to));
        }
        return query(where.toString(), parameters);
    }

    /**
     * Persist a new PENDING proposal. Does not perform acceptance, listing
     * reservation, notification, or chat transitions.
     * The service must authenticate the buyer before calling this method.
     *
     * <p>Opens and commits its own transaction. The generated/returned ID, canonical seller,
     * {@code PENDING} status, row version and creation time are written back onto the deal.
     *
     * @param deal the proposal to insert
     * @return the new deal's ID
     * @throws IllegalArgumentException when the proposal is null or invalid (status not
     *         {@code PENDING}, bad price, bad borrow period, already countered/completed, or the
     *         buyer/listing/seller eligibility checks fail)
     * @throws SQLException when the insert fails; the transaction is rolled back
     */
    public String create(Deal deal) throws SQLException {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String id = create(connection, deal);
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    /**
     * Insert a proposal within the caller's deal/notification transaction.
     *
     * <p>The caller owns the connection and commit/rollback. The buyer, listing and seller are
     * validated on the same transaction, the listing is locked with {@code FOR UPDATE}, and a
     * second accepted deal for the listing is rejected. The deal's ID, canonical seller,
     * {@code PENDING} status, row version ({@code 1}) and creation time are set on the object.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param deal the proposal to insert
     * @return the new deal's ID
     * @throws IllegalArgumentException when the connection is not transactional or the proposal
     *         is invalid
     * @throws SQLException when the insert fails
     */
    public String create(Connection connection, Deal deal) throws SQLException {
        requireTransaction(connection);
        if (deal == null) throw new IllegalArgumentException("A deal is required.");
        if (deal.getStatus() != DealStatus.PENDING) {
            throw new IllegalArgumentException("New deals must start as PENDING.");
        }
        String type = databaseType(deal.getDealType());
        BigDecimal price = validatedPrice(deal.getOfferPrice());
        if (deal.getDealType() == ListingType.SELL && price == null) {
            throw new IllegalArgumentException("A sale offer price is required.");
        }
        if (deal.getDealType() == ListingType.LEND) {
            requireBorrowPeriod(deal.getBorrowStart(), deal.getBorrowEnd());
            if (price != null) throw new IllegalArgumentException("Borrow requests use dates, not a sale offer price.");
        } else if (deal.getBorrowStart() != null || deal.getBorrowEnd() != null) {
            throw new IllegalArgumentException("Only borrowing requests can have borrowing dates.");
        }
        if (deal.getDealType() == ListingType.GIVEAWAY && price != null) {
            throw new IllegalArgumentException("Giveaway claims cannot have an offer price.");
        }
        if (deal.getCompletedAt() != null || deal.getReturnedAt() != null
                || deal.getCounterOfferPrice() != null || deal.getCounterBorrowStart() != null
                || deal.getCounterBorrowEnd() != null) {
            throw new IllegalArgumentException("New proposals cannot already be countered, completed, or returned.");
        }
        String id = deal.getId() == null ? UUID.randomUUID().toString() : deal.getId();
        requireIdentifier(id, "Deal ID");
        LocalDateTime created = deal.getCreatedAt() == null ? LocalDateTime.now() : deal.getCreatedAt();
        String sql = """
                INSERT INTO deals (id, buyer_username, seller_username, listing_id, dealType,
                    status, offeredPrice, borrowStartDate, borrowDueDate, createdDate, completedDate)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, NULL)
                """;
        ValidatedParties parties = validateNewDeal(connection, deal.getBuyerUsername(),
                deal.getListingId(), deal.getDealType());
        requireNoAcceptedDeal(connection, deal.getListingId(), id);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, parties.buyerUsername());
            statement.setString(3, parties.sellerUsername());
            statement.setString(4, deal.getListingId());
            statement.setString(5, type);
            statement.setBigDecimal(6, price);
            statement.setTimestamp(7, dateTimestamp(deal.getBorrowStart()));
            statement.setTimestamp(8, dateTimestamp(deal.getBorrowEnd()));
            statement.setTimestamp(9, Timestamp.valueOf(created));
            if (statement.executeUpdate() != 1) throw new SQLException("The deal could not be created.");
        }
        deal.setId(id);
        deal.setBuyerUsername(parties.buyerUsername());
        deal.setSellerUsername(parties.sellerUsername());
        deal.setStatus(DealStatus.PENDING);
        deal.setRowVersion(1);
        deal.setCreatedAt(created);
        return id;
    }

    private List<Deal> query(String where, List<?> parameters) throws SQLException {
        //deleted = 2 means both participants are gone: hide these records from default queries
        String filter = where == null || where.isBlank()
                ? "WHERE d.deleted < 2"
                : where + " AND d.deleted < 2";
        List<Deal> deals = new ArrayList<>();
        try (Connection connection = connections.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_DEALS + filter + NEWEST_FIRST)) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) deals.add(mapRow(rows));
            }
        }
        return deals;
    }

    private Deal mapRow(ResultSet rows) throws SQLException {
        Deal deal = new Deal();
        deal.setId(rows.getString("id"));
        deal.setListingId(rows.getString("listing_id"));
        deal.setListingTitle(rows.getString("listing_title"));
        deal.setBuyerUsername(rows.getString("buyer_username"));
        deal.setSellerUsername(rows.getString("seller_username"));
        deal.setDealType(switch (rows.getString("dealType")) {
            case "BOUGHT" -> ListingType.SELL;
            case "BORROWED" -> ListingType.LEND;
            case "RECEIVED" -> ListingType.GIVEAWAY;
            default -> throw new SQLException("Unknown stored deal type.");
        });
        try {
            deal.setStatus(DealStatus.valueOf(rows.getString("status")));
        } catch (IllegalArgumentException | NullPointerException invalidStatus) {
            throw new SQLException("Unknown stored deal status; apply the deal-status migration.", invalidStatus);
        }
        BigDecimal price = rows.getBigDecimal("offeredPrice");
        deal.setOfferPrice(price == null ? null : price.doubleValue());
        Timestamp start = rows.getTimestamp("borrowStartDate");
        Timestamp end = rows.getTimestamp("borrowDueDate");
        deal.setBorrowStart(start == null ? null : start.toLocalDateTime());
        deal.setBorrowEnd(end == null ? null : end.toLocalDateTime());
        BigDecimal counterPrice = rows.getBigDecimal("counterOfferPrice");
        deal.setCounterOfferPrice(counterPrice == null ? null : counterPrice.doubleValue());
        deal.setCounterBorrowStart(readDateTime(rows, "counterBorrowStart"));
        deal.setCounterBorrowEnd(readDateTime(rows, "counterDueEnd"));
        deal.setReturnedAt(readDateTime(rows, "returnedDate"));
        Timestamp created = rows.getTimestamp("createdDate");
        Timestamp completed = rows.getTimestamp("completedDate");
        deal.setCreatedAt(created == null ? null : created.toLocalDateTime());
        deal.setCompletedAt(completed == null ? null : completed.toLocalDateTime());
        deal.setRowVersion(rows.getLong("row_version"));
        return deal;
    }

    private static String databaseType(ListingType type) {
        if (type == null) throw new IllegalArgumentException("A deal type is required.");
        return switch (type) {
            case SELL -> "BOUGHT";
            case LEND -> "BORROWED";
            case GIVEAWAY -> "RECEIVED";
        };
    }

    private static String databaseStatus(String status) {
        if (status == null) throw new IllegalArgumentException("A deal status is required.");
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        return DealStatus.valueOf(normalized).name();
    }

    private static BigDecimal validatedPrice(Double price) {
        if (price == null) return null;
        if (!Double.isFinite(price)) throw new IllegalArgumentException("Offer price must be finite.");
        BigDecimal amount = BigDecimal.valueOf(price);
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("99999999.99")) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Offer price must be nonnegative, within DECIMAL(10,2), and have at most two decimal places.");
        }
        return amount;
    }

    private static Timestamp dateTimestamp(LocalDateTime date) {
        return date == null ? null : Timestamp.valueOf(date);
    }

    private static LocalDateTime readDateTime(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static void requireBorrowPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || java.time.Duration.between(start, end).compareTo(
                java.time.Duration.ofSeconds(5)) < 0) {
            throw new IllegalArgumentException("Borrowing requires start/end times at least five seconds apart.");
        }
    }

    /**
     * Lock listing before deal, consistently with creation and affected-deal checks.
     *
     * <p>Caller-owned transaction: this method never commits. The row is read with
     * {@code FOR UPDATE} and double-deleted records are treated as absent.
     *
     * @param connection an open connection with auto-commit disabled; it is not closed
     * @param id the deal ID
     * @return the locked deal, or {@link Optional#empty()} when it does not exist, is
     *         double-deleted, or its listing disappeared
     * @throws IllegalArgumentException when the connection is not transactional or the ID is
     *         invalid
     * @throws SQLException when the queries fail
     */
    public Optional<Deal> findByIdForUpdate(Connection connection, String id) throws SQLException {
        requireTransaction(connection);
        requireIdentifier(id, "Deal ID");
        String listingId;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT listing_id FROM deals WHERE id = ? AND deleted < 2")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                listingId = rows.getString("listing_id");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM listings WHERE id = ? FOR UPDATE")) {
            statement.setString(1, listingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_DEALS + "WHERE d.id = ? AND d.deleted < 2 FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapRow(rows)) : Optional.empty();
            }
        }
    }

    /**
     * Guarded lifecycle write. Caller authenticates/authorizes the actor and owns
     * the transaction, including related listing updates and notification delivery.
     * Pass the row version shown to the actor. False means the deal disappeared or
     * its status/terms changed since that read; reload instead of silently retrying.
     *
     * <p>The deal and its listing are re-read with {@code FOR UPDATE}; on success the guarded
     * update advances {@code row_version}, stores the completion/return time, and, when accepting
     * a counter, adopts the counter terms while retaining the counter history.
     *
     * @param connection an open connection with auto-commit disabled; caller owns it
     * @param id the deal ID
     * @param expected the status the actor saw; must allow the transition to {@code next}
     * @param expectedVersion the {@code row_version} the actor saw
     * @param next the target status; {@code COUNTERED} must be written through
     *        {@link #updateProposal(Connection, String, DealStatus, long, Double, LocalDateTime, LocalDateTime)}
     * @param occurredAt the action time stored for completion or return
     * @return true when the guarded update matched the expected status and version and was
     *         applied; false when the deal disappeared or changed since the read
     * @throws IllegalArgumentException when the connection is not transactional, the transition is
     *         not allowed, the timestamp precedes creation, completion/return does not match the
     *         deal type, return precedes borrowing, or acceptance validation fails
     * @throws SQLException when any statement fails
     */
    public boolean updateStatus(Connection connection, String id, DealStatus expected,
                                long expectedVersion, DealStatus next, LocalDateTime occurredAt) throws SQLException {
        requireTransaction(connection);
        if (expected == null || !expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid deal status transition.");
        }
        if (next == DealStatus.COUNTERED) {
            throw new IllegalArgumentException("Use updateProposal to store counter terms together with COUNTERED status.");
        }
        Objects.requireNonNull(occurredAt, "An action timestamp is required.");
        Optional<Deal> found = findByIdForUpdate(connection, id);
        if (found.isEmpty() || found.get().getStatus() != expected
                || found.get().getRowVersion() != expectedVersion) return false;
        Deal deal = found.get();
        if (occurredAt.isBefore(deal.getCreatedAt())) {
            throw new IllegalArgumentException("An action cannot precede deal creation.");
        }
        if (next == DealStatus.COMPLETED && deal.getDealType() == ListingType.LEND
                || next == DealStatus.RETURNED && deal.getDealType() != ListingType.LEND) {
            throw new IllegalArgumentException("Borrowing ends as RETURNED; sales and giveaways end as COMPLETED.");
        }
        if (next == DealStatus.RETURNED && (deal.getBorrowStart() == null
                || occurredAt.isBefore(deal.getBorrowStart()))) {
            throw new IllegalArgumentException("An item cannot be returned before borrowing starts.");
        }
        if (next == DealStatus.ACCEPTED) {
            boolean counter = expected == DealStatus.COUNTERED;
            if (deal.getDealType() == ListingType.SELL
                    && validatedPrice(counter ? deal.getCounterOfferPrice() : deal.getOfferPrice()) == null) {
                throw new IllegalArgumentException("A sale proposal must have a price before acceptance.");
            }
            if (deal.getDealType() == ListingType.LEND) {
                requireBorrowPeriod(counter ? deal.getCounterBorrowStart() : deal.getBorrowStart(),
                        counter ? deal.getCounterBorrowEnd() : deal.getBorrowEnd());
            }
            validateNewDeal(connection, deal.getBuyerUsername(), deal.getListingId(), deal.getDealType());
            requireNoAcceptedDeal(connection, deal.getListingId(), id);
        }
        // Accepting a counter adopts its terms while retaining the counter history.
        boolean acceptCounter = expected == DealStatus.COUNTERED && next == DealStatus.ACCEPTED;
        String sql = "UPDATE deals SET status = ?, completedDate = ?, returnedDate = ?, row_version = row_version + 1"
                + (acceptCounter ? ", offeredPrice = COALESCE(counterOfferPrice, offeredPrice), "
                    + "borrowStartDate = COALESCE(counterBorrowStart, borrowStartDate), "
                    + "borrowDueDate = COALESCE(counterDueEnd, borrowDueDate)" : "")
                + " WHERE id = ? AND status = ? AND row_version = ? AND deleted < 2";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, next.name());
            statement.setTimestamp(2, next == DealStatus.COMPLETED ? dateTimestamp(occurredAt) : null);
            statement.setTimestamp(3, next == DealStatus.RETURNED ? dateTimestamp(occurredAt) : null);
            statement.setString(4, id);
            statement.setString(5, expected.name());
            statement.setLong(6, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Seller counteroffer; stale versions return false. Authorization and commit belong to the
     * service.
     *
     * <p>The deal and its listing are re-read with {@code FOR UPDATE}. On success the deal becomes
     * {@code COUNTERED} with the supplied terms and an incremented {@code row_version}; a
     * giveaway claim can never be countered. The caller owns the connection and transaction.
     *
     * @param connection an open connection with auto-commit disabled; caller owns it
     * @param id the deal ID
     * @param expected the status the seller saw ({@code PENDING} or {@code COUNTERED})
     * @param expectedVersion the {@code row_version} the seller saw
     * @param counterPrice the counter sale price for a sale, or {@code null} for borrowing
     * @param counterStart the counter borrowing start, required for borrowing
     * @param counterEnd the counter borrowing due time, required for borrowing
     * @return true when the guarded update matched and was applied; false when the deal
     *         disappeared or changed since the read
     * @throws IllegalArgumentException when the connection is not transactional, the status is not
     *         counterable, the terms do not match the deal type, or a giveaway is countered
     * @throws SQLException when any statement fails
     */
    public boolean updateProposal(Connection connection, String id, DealStatus expected,
                                  long expectedVersion, Double counterPrice, LocalDateTime counterStart,
                                  LocalDateTime counterEnd) throws SQLException {
        requireTransaction(connection);
        if (expected != DealStatus.PENDING && expected != DealStatus.COUNTERED) {
            throw new IllegalArgumentException("Only open proposals can be countered.");
        }
        Optional<Deal> found = findByIdForUpdate(connection, id);
        if (found.isEmpty() || found.get().getStatus() != expected
                || found.get().getRowVersion() != expectedVersion) return false;
        Deal deal = found.get();
        BigDecimal price = validatedPrice(counterPrice);
        if (deal.getDealType() == ListingType.GIVEAWAY) {
            throw new IllegalArgumentException("Giveaway claims cannot be countered.");
        }
        if (deal.getDealType() == ListingType.SELL) {
            if (price == null || counterStart != null || counterEnd != null) {
                throw new IllegalArgumentException("A sale counter requires a price and no borrowing dates.");
            }
        } else {
            requireBorrowPeriod(counterStart, counterEnd);
            if (price != null) throw new IllegalArgumentException("A borrowing counter changes the period, not a sale price.");
        }
        validateNewDeal(connection, deal.getBuyerUsername(), deal.getListingId(), deal.getDealType());
        requireNoAcceptedDeal(connection, deal.getListingId(), id);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE deals SET status = 'COUNTERED', counterOfferPrice = ?,
                    counterBorrowStart = ?, counterDueEnd = ?, row_version = row_version + 1
                WHERE id = ? AND status = ? AND row_version = ? AND deleted < 2
                """)) {
            statement.setBigDecimal(1, price);
            statement.setTimestamp(2, dateTimestamp(counterStart));
            statement.setTimestamp(3, dateTimestamp(counterEnd));
            statement.setString(4, id);
            statement.setString(5, expected.name());
            statement.setLong(6, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Candidates only: the worker must lock/recheck each deal before returning it.
     *
     * <p>Returns accepted borrowing deals whose due time is at or before {@code cutoff},
     * excluding double-deleted rows. Uses a new connection.
     *
     * @param cutoff the inclusive due-time threshold
     * @return candidate deals, newest first; never {@code null}
     * @throws NullPointerException when {@code cutoff} is {@code null}
     * @throws SQLException when the query fails
     */
    public List<Deal> findDueBorrowings(LocalDateTime cutoff) throws SQLException {
        Objects.requireNonNull(cutoff, "A cutoff is required.");
        return query("WHERE d.dealType = 'BORROWED' AND d.status = 'ACCEPTED' "
                + "AND d.borrowDueDate <= ?", List.of(dateTimestamp(cutoff)));
    }

    /**
     * Returns accepted borrowing deals due strictly after {@code now} and strictly before
     * {@code cutoff}, excluding double-deleted rows.
     *
     * <p>Uses a new connection; the caller decides when to warn and must recheck a deal before
     * acting on it.
     *
     * @param now exclusive lower bound on the due time
     * @param cutoff exclusive upper bound on the due time
     * @return candidate deals, newest first; never {@code null}
     * @throws SQLException when the query fails
     */
    public List<Deal> findBorrowingsNearingDueDate(LocalDateTime now, LocalDateTime cutoff) throws SQLException {
        return query("WHERE d.dealType = 'BORROWED' AND d.status = 'ACCEPTED' "
                + "AND d.borrowDueDate > ? AND d.borrowDueDate < ?",
                List.of(dateTimestamp(now), dateTimestamp(cutoff)));
    }

    private void requireNoAcceptedDeal(Connection connection, String listingId, String excludingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM deals WHERE listing_id = ? AND status = 'ACCEPTED'"
                        + " AND id <> ? AND deleted < 2 FOR UPDATE")) {
            statement.setString(1, listingId);
            statement.setString(2, excludingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) throw new IllegalArgumentException("This listing already has an accepted deal.");
            }
        }
    }

    /** Canonical database identities to use when a new deal is eventually inserted. */
    private record ValidatedParties(String buyerUsername, String sellerUsername) {}

    /**
     * Validate new-deal eligibility on the same transaction used for the insert.
     * The listing lock serializes competing attempts; the eventual create operation
     * must still check conflicting deals before inserting and committing.
     * The seller is derived from the stored listing, never supplied by the caller.
     */
    private ValidatedParties validateNewDeal(Connection connection, String buyerUsername,
                                              String listingId, ListingType requestedType)
            throws SQLException {
        requireTransaction(connection);
        requireIdentifier(buyerUsername, "Buyer username");
        requireIdentifier(listingId, "Listing ID");
        if (requestedType == null) {
            throw new IllegalArgumentException("A deal type is required.");
        }
        String seller = requireAvailableListing(connection, listingId, requestedType);
        String buyer = requireActiveBuyer(connection, buyerUsername);
        requireDistinctParties(buyer, seller);
        return new ValidatedParties(buyer, seller);
    }

    private String requireActiveBuyer(Connection connection, String username) throws SQLException {
        return requireActiveUser(connection, username, UserRole.BUYER);
    }

    private String requireActiveSeller(Connection connection, String username) throws SQLException {
        return requireActiveUser(connection, username, UserRole.SELLER);
    }

    /** Return the stored username, respecting the database's username lookup semantics. */
    private String requireActiveUser(Connection connection, String username, UserRole role)
            throws SQLException {
        requireTransaction(connection);
        requireIdentifier(username, "Username");
        String sql = "SELECT username, role, active FROM users WHERE username = ? FOR SHARE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean("active")
                        || !role.name().equals(rows.getString("role"))) {
                    throw new IllegalArgumentException("An active "
                            + role.name().toLowerCase(java.util.Locale.ROOT) + " account is required.");
                }
                return rows.getString("username");
            }
        }
    }

    /** Only LIVE listings accept new proposals; existing-deal actions need other checks. */
    private String requireAvailableListing(Connection connection, String listingId,
                                            ListingType requestedType) throws SQLException {
        requireTransaction(connection);
        requireIdentifier(listingId, "Listing ID");
        if (requestedType == null) {
            throw new IllegalArgumentException("A deal type is required.");
        }
        String seller;
        String sql = "SELECT seller_username, listingType, listingStatus FROM listings WHERE id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("The listing does not exist.");
                }
                if (!"LIVE".equals(rows.getString("listingStatus"))) {
                    throw new IllegalArgumentException("This listing is not available for new deals.");
                }
                if (!requestedType.name().equals(rows.getString("listingType"))) {
                    throw new IllegalArgumentException("The deal type must match the listing type.");
                }
                seller = rows.getString("seller_username");
                if (seller == null || seller.isBlank()) {
                    throw new IllegalArgumentException("The listing no longer has a seller.");
                }
            }
        }
        return requireActiveSeller(connection, seller);
    }

    private void requireDistinctParties(String buyerUsername, String sellerUsername) {
        requireIdentifier(buyerUsername, "Buyer username");
        requireIdentifier(sellerUsername, "Seller username");
        if (buyerUsername.equalsIgnoreCase(sellerUsername)) {
            throw new IllegalArgumentException("Deal buyer and seller must be different users.");
        }
    }

    private void requireIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        if (value.codePointCount(0, value.length()) > 50) {
            throw new IllegalArgumentException(label + " must be at most 50 characters.");
        }
    }

    private void requireTransaction(Connection connection) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw new IllegalArgumentException("Deal validation requires an active transaction with auto-commit disabled.");
        }
    }
}
