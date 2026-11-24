package com.hkust.marketplace.services;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.ListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Seller offer/request/claim management. Each write commits the deal, listing,
 * and recipient notifications together or rolls them all back.
 */
public class DealService {
    private final UserRepository users;
    private final DealRepository deals;
    private final ListingRepository listings;
    private final NotificationRepository notifications;
    private final DealRepository.ConnectionProvider connections;
    private final Clock clock;

    public DealService() {
        this(new UserRepository(), new DealRepository(), new ListingRepository(),
                new NotificationRepository());
    }

    public DealService(UserRepository users, DealRepository deals,
                       ListingRepository listings, NotificationRepository notifications) {
        this(users, deals, listings, notifications, DatabaseManager::getConnection, Clock.systemDefaultZone());
    }

    public DealService(UserRepository users, DealRepository deals,
                       ListingRepository listings, NotificationRepository notifications,
                       DealRepository.ConnectionProvider connections, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.deals = Objects.requireNonNull(deals);
        this.listings = Objects.requireNonNull(listings);
        this.notifications = Objects.requireNonNull(notifications);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Load this seller's pending/countered incoming offers, borrow requests, and claims. */
    public List<Deal> getIncomingOffers(User seller) throws SQLException {
        User current = requireActiveSeller(null, seller);
        return deals.findBySeller(current.getUsername()).stream()
                .filter(deal -> deal.getStatus() == DealStatus.PENDING
                        || deal.getStatus() == DealStatus.COUNTERED)
                .toList();
    }

    /** Load an owned deal in any status for its detail view and future linked chat. */
    public Deal getSellerDeal(User seller, String dealId) throws SQLException {
        User current = requireActiveSeller(null, seller);
        Deal deal = deals.findById(dealId).orElseThrow(DealService::unavailableDeal);
        requireOwner(current, deal);
        return deal;
    }

    /** Accept a PENDING proposal; accepting a seller's counter is the buyer's action. */
    public void acceptOffer(User seller, String dealId, long expectedVersion) throws SQLException {
        validateAction(seller, expectedVersion);
        inTransaction(connection -> {
            Deal deal = requireOwnedDeal(connection, seller, dealId, expectedVersion);
            if (deal.getStatus() != DealStatus.PENDING) {
                throw new IllegalArgumentException("Only pending offers can be accepted by the seller. Counteroffers await the buyer.");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (deal.getDealType() == ListingType.LEND && (deal.getBorrowEnd() == null
                    || !deal.getBorrowEnd().isAfter(now))) {
                throw new IllegalArgumentException("The requested borrowing period has expired. Counter with new dates instead.");
            }
            requireUpdated(deals.updateStatus(connection, dealId, DealStatus.PENDING,
                    expectedVersion, DealStatus.ACCEPTED, now));
            // Acceptance reserves the item; completion/return services own its later final status.
            if (!listings.reserveForDeal(connection, deal.getListingId(), deal.getSellerUsername())) {
                throw new IllegalArgumentException("The listing is no longer available. Reload your offers.");
            }
            notifyBuyer(connection, deal, "Offer Accepted", "The seller accepted your proposal for '"
                    + deal.getListingTitle() + "'. Arrange collection with the seller.");
            for (Deal competing : deals.findAffectedDeals(connection, deal.getListingId())) {
                if (competing.getId().equals(deal.getId())) continue;
                if (competing.getStatus() == DealStatus.ACCEPTED) {
                    throw new IllegalArgumentException("This listing already has another accepted deal.");
                }
                requireUpdated(deals.updateStatus(connection, competing.getId(), competing.getStatus(),
                        competing.getRowVersion(), DealStatus.CANCELLED, now));
                notifyBuyer(connection, competing, "Proposal Cancelled",
                        "Your proposal for '" + deal.getListingTitle()
                                + "' was cancelled because another proposal was accepted.");
            }
        });
    }

    /** Decline an open owned proposal using the version displayed to the seller. */
    public void declineOffer(User seller, String dealId, long expectedVersion) throws SQLException {
        validateAction(seller, expectedVersion);
        inTransaction(connection -> {
            Deal deal = requireOwnedDeal(connection, seller, dealId, expectedVersion);
            requireOpenProposal(deal);
            requireUpdated(deals.updateStatus(connection, dealId, deal.getStatus(),
                    expectedVersion, DealStatus.DECLINED, LocalDateTime.now(clock)));
            notifyBuyer(connection, deal, "Offer Declined",
                    "The seller declined your proposal for '" + deal.getListingTitle() + "'.");
        });
    }

    /**
     * Counter a sale with a price, or a borrowing request with start/end times.
     * Supply null for inapplicable terms. Giveaway claims cannot be countered.
     */
    public void counterOffer(User seller, String dealId, long expectedVersion,
                             Double counterPrice, LocalDateTime counterStart,
                             LocalDateTime counterEnd) throws SQLException {
        validateAction(seller, expectedVersion);
        inTransaction(connection -> {
            Deal deal = requireOwnedDeal(connection, seller, dealId, expectedVersion);
            requireOpenProposal(deal);
            if (deal.getDealType() == ListingType.LEND && (counterEnd == null
                    || !counterEnd.isAfter(LocalDateTime.now(clock)))) {
                throw new IllegalArgumentException("The countered borrowing period must not have expired.");
            }
            requireUpdated(deals.updateProposal(connection, dealId, deal.getStatus(), expectedVersion,
                    counterPrice, counterStart, counterEnd));
            String terms = deal.getDealType() == ListingType.SELL
                    ? "HK$" + java.math.BigDecimal.valueOf(counterPrice).toPlainString()
                    : counterStart + " to " + counterEnd;
            notifyBuyer(connection, deal, "Offer Countered", "The seller proposed " + terms
                    + " for '" + deal.getListingTitle() + "'. Accept or decline these new terms.");
        });
    }

    private User requireActiveSeller(Connection connection, User seller) throws SQLException {
        requireSellerIdentity(seller);
        User current = connection == null ? users.findByUsername(seller.getUsername())
                : users.findByUsername(connection, seller.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.SELLER) {
            throw new IllegalArgumentException("An active seller account is required.");
        }
        return current;
    }

    private Deal requireOwnedDeal(Connection connection, User seller, String id, long expectedVersion) throws SQLException {
        // Match repository lock order: listing/deal first, then account locks.
        Deal deal = deals.findByIdForUpdate(connection, id).orElseThrow(DealService::unavailableDeal);
        requireOwner(requireActiveSeller(connection, seller), deal);
        if (deal.getRowVersion() != expectedVersion) throw staleDeal();
        return deal;
    }

    private static void requireOwner(User seller, Deal deal) {
        if (!seller.getUsername().equals(deal.getSellerUsername())) throw unavailableDeal();
    }

    private static void requireSellerIdentity(User seller) {
        if (seller == null || seller.getUsername() == null || seller.getUsername().isBlank()) {
            throw new IllegalArgumentException("A signed-in seller is required.");
        }
    }

    private static void validateAction(User seller, long expectedVersion) {
        requireSellerIdentity(seller);
        if (expectedVersion < 1) throw new IllegalArgumentException("A valid deal version is required.");
    }

    private static void requireOpenProposal(Deal deal) {
        if (deal.getStatus() != DealStatus.PENDING && deal.getStatus() != DealStatus.COUNTERED) {
            throw new IllegalArgumentException("Only pending or countered proposals can be declined or countered.");
        }
    }

    private static IllegalArgumentException unavailableDeal() {
        return new IllegalArgumentException("This deal is not available to this seller.");
    }

    private static IllegalArgumentException staleDeal() {
        return new IllegalArgumentException("The proposal changed. Reload it and review the latest terms before trying again.");
    }

    private static void requireUpdated(boolean updated) {
        if (!updated) throw staleDeal();
    }

    private void notifyBuyer(Connection connection, Deal deal, String title, String message) throws SQLException {
        notifications.create(connection, new Notification(deal.getBuyerUsername(), title, message, "Offer"));
    }

    @FunctionalInterface
    private interface TransactionAction { void run(Connection connection) throws SQLException; }

    private void inTransaction(TransactionAction action) throws SQLException {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                action.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }
}
