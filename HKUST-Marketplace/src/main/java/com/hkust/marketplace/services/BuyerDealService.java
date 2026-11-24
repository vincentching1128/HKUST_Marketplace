package com.hkust.marketplace.services;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.utils.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Buyer proposal and completion rules. Every state change and notification commits together. */
public class BuyerDealService {
    private static final Duration REMINDER_LEAD = Duration.ofDays(1);
    private static final DateTimeFormatter DUE_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH);
    private final DealRepository deals;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final DealRepository.ConnectionProvider connections;
    private final Clock clock;

    public BuyerDealService() {
        this(new DealRepository(), new UserRepository(), new NotificationRepository(),
                DatabaseManager::getConnection, Clock.systemDefaultZone());
    }

    public BuyerDealService(DealRepository deals, UserRepository users, NotificationRepository notifications,
                            DealRepository.ConnectionProvider connections, Clock clock) {
        this.deals = Objects.requireNonNull(deals);
        this.users = Objects.requireNonNull(users);
        this.notifications = Objects.requireNonNull(notifications);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<DealHistoryEntry> getHistory(User buyer) throws SQLException {
        return deals.findBuyerHistory(requireBuyer(null, buyer).getUsername());
    }

    public List<Deal> getMyDeals(User buyer) throws SQLException {
        User current = requireBuyer(null, buyer);
        return deals.findByBuyer(current.getUsername());
    }

    public Deal getMyDeal(User buyer, String id) throws SQLException {
        User current = requireBuyer(null, buyer);
        Deal deal = deals.findById(id).orElseThrow(BuyerDealService::unavailable);
        requireOwner(current, deal);
        return deal;
    }

    /** A giveaway claim is a pending request; only seller acceptance reserves the item. */
    public Deal submit(User buyer, String listingId, ListingType type, Double price,
                       LocalDateTime start, LocalDateTime end) throws SQLException {
        if (type == null) throw new IllegalArgumentException("A listing type is required.");
        if (type == ListingType.LEND) {
            LocalDateTime now = LocalDateTime.now(clock);
            if (start == null || end == null || start.isBefore(now)
                    || Duration.between(start, end).compareTo(Duration.ofSeconds(5)) < 0) {
                throw new IllegalArgumentException("Choose a future borrowing period of at least five seconds.");
            }
        }
        Deal proposal = new Deal();
        proposal.setListingId(listingId);
        proposal.setDealType(type);
        proposal.setOfferPrice(price);
        proposal.setBorrowStart(start);
        proposal.setBorrowEnd(end);
        proposal.setCreatedAt(LocalDateTime.now(clock));
        inTransaction(connection -> {
            User current = requireBuyer(connection, buyer);
            proposal.setBuyerUsername(current.getUsername());
            // The listing lock in this query serializes proposals from the same buyer.
            for (Deal existing : deals.findAffectedDeals(connection, listingId)) {
                if (current.getUsername().equals(existing.getBuyerUsername())) {
                    throw new IllegalArgumentException("You already have an active proposal for this listing.");
                }
            }
            deals.create(connection, proposal); // Rechecks LIVE, type, seller and competing acceptance.
            notifications.create(connection, new Notification(proposal.getSellerUsername(),
                    "New Buyer Proposal", "@" + current.getUsername() + " sent a " + type.name().toLowerCase()
                    + " proposal for '" + listingTitle(connection, listingId) + "'.", "Offer"));
        });
        return proposal;
    }

    public void acceptCounter(User buyer, String id, long version) throws SQLException {
        change(buyer, id, version, (connection, deal, now) -> {
            if (deal.getStatus() != DealStatus.COUNTERED) throw new IllegalArgumentException("This counteroffer is no longer open.");
            if (deal.getDealType() == ListingType.LEND && (deal.getCounterBorrowEnd() == null
                    || !deal.getCounterBorrowEnd().isAfter(now))) {
                throw new IllegalArgumentException("The countered borrowing period has expired.");
            }
            updated(deals.updateStatus(connection, id, DealStatus.COUNTERED, version, DealStatus.ACCEPTED, now));
            updateListing(connection, deal, "LIVE", "RESERVED");
            cancelCompetitors(connection, deal, now);
            notifySeller(connection, deal, "Counteroffer Accepted", "The buyer accepted your counteroffer for '");
        });
    }

    public void declineCounter(User buyer, String id, long version) throws SQLException {
        change(buyer, id, version, (connection, deal, now) -> {
            if (deal.getStatus() != DealStatus.COUNTERED) throw new IllegalArgumentException("This counteroffer is no longer open.");
            updated(deals.updateStatus(connection, id, DealStatus.COUNTERED, version, DealStatus.DECLINED, now));
            notifySeller(connection, deal, "Counteroffer Declined", "The buyer declined your counteroffer for '");
        });
    }

    public void complete(User buyer, String id, long version) throws SQLException {
        change(buyer, id, version, (connection, deal, now) -> {
            if (deal.getStatus() != DealStatus.ACCEPTED || deal.getDealType() == ListingType.LEND) {
                throw new IllegalArgumentException("Only accepted purchases or claims can be completed.");
            }
            updated(deals.updateStatus(connection, id, DealStatus.ACCEPTED, version, DealStatus.COMPLETED, now));
            updateListing(connection, deal, "RESERVED", deal.getDealType() == ListingType.SELL ? "SOLD" : "GIVEN");
            notifySeller(connection, deal, "Deal Completed", "The buyer completed the deal for '");
        });
    }

    public void returnBorrowed(User buyer, String id, long version) throws SQLException {
        change(buyer, id, version, (connection, deal, now) -> {
            if (deal.getStatus() != DealStatus.ACCEPTED || deal.getDealType() != ListingType.LEND) {
                throw new IllegalArgumentException("Only an accepted borrowing can be returned.");
            }
            updated(deals.updateStatus(connection, id, DealStatus.ACCEPTED, version, DealStatus.RETURNED, now));
            updateListing(connection, deal, "RESERVED", "LIVE");
            notifySeller(connection, deal, "Borrowing Returned", "The buyer returned '");
        });
    }

    /** Safe to run repeatedly; a stale candidate is rechecked under the listing/deal lock. */
    public int returnDueBorrowings() throws SQLException {
        int returned = 0;
        for (Deal candidate : deals.findDueBorrowings(LocalDateTime.now(clock))) {
            boolean changed = inTransactionResult(connection -> {
                Deal deal = deals.findByIdForUpdate(connection, candidate.getId()).orElse(null);
                LocalDateTime now = LocalDateTime.now(clock);
                if (deal == null || deal.getStatus() != DealStatus.ACCEPTED
                        || deal.getDealType() != ListingType.LEND || deal.getBorrowEnd() == null
                        || deal.getBorrowEnd().isAfter(now)) return false;
                if (!deals.updateStatus(connection, deal.getId(), DealStatus.ACCEPTED,
                        deal.getRowVersion(), DealStatus.RETURNED, now)) return false;
                updateListing(connection, deal, "RESERVED", "LIVE");
                notifications.create(connection, new Notification(deal.getBuyerUsername(), "Borrowing Returned",
                        "The borrowing period for '" + deal.getListingTitle() + "' ended automatically.", "Borrow"));
                notifySeller(connection, deal, "Borrowing Returned", "The borrowing period ended for '");
                return true;
            });
            if (changed) returned++;
        }
        return returned;
    }

    public void remindDueBorrowings() throws SQLException {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Deal candidate : deals.findBorrowingsNearingDueDate(now, now.plus(REMINDER_LEAD))) {
            inTransaction(connection -> {
                Deal deal = deals.findByIdForUpdate(connection, candidate.getId()).orElse(null);
                if (deal == null || !reminderDue(deal, LocalDateTime.now(clock))) return;

                // The same delivery ID survives restarts and concurrent app sessions.
                String reminderId = UUID.nameUUIDFromBytes(
                        ("borrow-due-reminder:" + deal.getId()).getBytes(StandardCharsets.UTF_8)).toString();
                if (notifications.existsForUpdate(connection, reminderId)) return;
                Notification reminder = new Notification(deal.getBuyerUsername(), "Borrowing Due Soon",
                        "Your borrowing for '" + deal.getListingTitle() + "' is due on "
                                + deal.getBorrowEnd().format(DUE_TIME) + ".", "Borrow");
                reminder.setId(reminderId);
                notifications.create(connection, reminder);
            });
        }
    }

    private static boolean reminderDue(Deal deal, LocalDateTime now) {
        LocalDateTime end = deal.getBorrowEnd();
        return deal.getStatus() == DealStatus.ACCEPTED && deal.getDealType() == ListingType.LEND
                && end != null && end.isAfter(now) && end.isBefore(now.plus(REMINDER_LEAD));
    }

    private void cancelCompetitors(Connection connection, Deal accepted, LocalDateTime now) throws SQLException {
        for (Deal other : deals.findAffectedDeals(connection, accepted.getListingId())) {
            if (other.getId().equals(accepted.getId())) continue;
            if (other.getStatus() == DealStatus.ACCEPTED) throw new IllegalArgumentException("Another deal was already accepted.");
            updated(deals.updateStatus(connection, other.getId(), other.getStatus(),
                    other.getRowVersion(), DealStatus.CANCELLED, now));
            notifications.create(connection, new Notification(other.getBuyerUsername(), "Proposal Cancelled",
                    "Another proposal was accepted for '" + accepted.getListingTitle() + "'.", "Offer"));
        }
    }

    private void updateListing(Connection connection, Deal deal, String expected, String next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE listings SET listingStatus = ?, row_version = row_version + 1 "
                        + "WHERE id = ? AND seller_username = ? AND listingStatus = ?")) {
            statement.setString(1, next);
            statement.setString(2, deal.getListingId());
            statement.setString(3, deal.getSellerUsername());
            statement.setString(4, expected);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("The listing changed. Refresh the deal.");
        }
    }

    private String listingTitle(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT itemName FROM listings WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : "this listing"; }
        }
    }

    private void notifySeller(Connection connection, Deal deal, String title, String prefix) throws SQLException {
        notifications.create(connection, new Notification(deal.getSellerUsername(), title,
                prefix + deal.getListingTitle() + "'.", "Offer"));
    }

    private User requireBuyer(Connection connection, User buyer) throws SQLException {
        if (buyer == null || buyer.getUsername() == null || buyer.getUsername().isBlank()) throw unavailable();
        User current = connection == null ? users.findByUsername(buyer.getUsername())
                : users.findByUsername(connection, buyer.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.BUYER) throw unavailable();
        return current;
    }

    private static void requireOwner(User buyer, Deal deal) {
        if (!buyer.getUsername().equals(deal.getBuyerUsername())) throw unavailable();
    }

    private void change(User buyer, String id, long version, Change action) throws SQLException {
        if (version < 1) throw new IllegalArgumentException("Refresh the deal before acting.");
        inTransaction(connection -> {
            Deal deal = deals.findByIdForUpdate(connection, id).orElseThrow(BuyerDealService::unavailable);
            requireOwner(requireBuyer(connection, buyer), deal);
            if (deal.getRowVersion() != version) throw stale();
            action.run(connection, deal, LocalDateTime.now(clock));
        });
    }

    private static void updated(boolean value) { if (!value) throw stale(); }
    private static IllegalArgumentException stale() { return new IllegalArgumentException("The deal changed. Refresh and review its latest status."); }
    private static IllegalArgumentException unavailable() { return new IllegalArgumentException("This deal is not available to your buyer account."); }

    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    @FunctionalInterface private interface Change { void run(Connection connection, Deal deal, LocalDateTime now) throws SQLException; }
    private void inTransaction(ChangeOnly work) throws SQLException { inTransactionResult(connection -> { work.run(connection); return null; }); }
    @FunctionalInterface private interface ChangeOnly { void run(Connection connection) throws SQLException; }
    private <T> T inTransactionResult(Work<T> work) throws SQLException {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }
}
