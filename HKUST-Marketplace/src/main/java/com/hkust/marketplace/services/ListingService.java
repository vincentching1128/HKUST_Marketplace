package com.hkust.marketplace.services;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.repositories.ListingRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.SellerStatsRepository;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.utils.DatabaseManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ListingService {
    private static final Logger LOGGER = Logger.getLogger(ListingService.class.getName());
    private static final int NOTIFICATION_REASON_LIMIT = 500;

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final NotificationRepository notificationRepository;
    private final DealRepository deals;
    private final DealRepository.ConnectionProvider connections;

    public ListingService() {
        this(new UserRepository(), new ListingRepository(), new NotificationRepository());
    }

    /** Kept for existing callers; notifications use the default repository. */
    public ListingService(UserRepository userRepository, ListingRepository listingRepository) {
        this(userRepository, listingRepository, new NotificationRepository());
    }

    public ListingService(UserRepository userRepository, ListingRepository listingRepository,
                          NotificationRepository notificationRepository) {
        this(userRepository, listingRepository, notificationRepository, new DealRepository(), DatabaseManager::getConnection);
    }

    public ListingService(UserRepository userRepository, ListingRepository listingRepository,
                          NotificationRepository notificationRepository, DealRepository deals,
                          DealRepository.ConnectionProvider connections) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.listingRepository = Objects.requireNonNull(listingRepository);
        this.notificationRepository = Objects.requireNonNull(notificationRepository);
        this.deals = Objects.requireNonNull(deals);
        this.connections = Objects.requireNonNull(connections);
    }

    //use the signed-in account, rather than accepting a seller name from the screen
    public List<Listing> getMyListings(User user) throws SQLException {
        User current = requireActiveSeller(user);
        return listingRepository.findBySeller(current.getUsername());
    }

    public SellerStatsRepository.Stats getSellerStats(User seller) throws SQLException {
        User current = requireActiveSeller(seller);
        return new SellerStatsRepository().findBySeller(current.getUsername());
    }

    //check the database account each time in case its role or active status changed
    private User requireActiveSeller(User user) throws SQLException {
        User current = user == null || user.getUsername() == null
                ? null : userRepository.findByUsername(user.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.SELLER) {
            throw new IllegalArgumentException("An active seller account is required to access your listings.");
        }
        return current;
    }

    //check the stored role rather than trusting the account details held by the screen
    private User requireAdmin(User user) throws SQLException {
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new IllegalArgumentException("An admin account is required.");
        }
        User current = userRepository.findByUsername(user.getUsername());
        if (current == null || current.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("An admin account is required.");
        }
        return current;
    }

    //Create and Edit also require the stored admin account to be active
    private User requireActiveAdmin(User user) throws SQLException {
        User current = user == null || user.getUsername() == null
                ? null : userRepository.findByUsername(user.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("An active admin account is required.");
        }
        return current;
    }

    public void createListing(User user, Listing listing) throws SQLException {
        validateListing(listing);

        User current = requireActiveSeller(user);
        listing.setId(UUID.randomUUID().toString());
        listing.setSellerUsername(current.getUsername());
        listing.setSellerFullName(current.getFullName());
        //every new listing starts with no review result and waits for admin approval
        listing.setStatus(ListingStatus.PENDING_REVIEW);
        listing.setPostedAt(LocalDateTime.now());
        listing.setReviewedAt(null);
        listing.setRejectionReason(null);
        listing.setViewCount(0);
        listingRepository.create(listing);
        notifyAdminsOfPendingListing(listing.getTitle(), current.getUsername(), current.getUsername(),
                "New Listing Submitted");
    }

    /** Admin action: create a listing on behalf of an active seller; it starts Pending Review. */
    public void createListingAsAdmin(User admin, String sellerUsername, Listing listing) throws SQLException {
        User currentAdmin = requireActiveAdmin(admin);
        validateListing(listing);
        User seller = sellerUsername == null || sellerUsername.isBlank()
                ? null : userRepository.findByUsername(sellerUsername);
        if (seller == null || !seller.isActive() || seller.getRole() != UserRole.SELLER) {
            throw new IllegalArgumentException("Choose an active seller account.");
        }
        //the seller's account owns the listing, not the admin who typed it
        listing.setId(UUID.randomUUID().toString());
        listing.setSellerUsername(seller.getUsername());
        listing.setSellerFullName(seller.getFullName());
        listing.setStatus(ListingStatus.PENDING_REVIEW);
        listing.setPostedAt(LocalDateTime.now());
        listing.setReviewedAt(null);
        listing.setRejectionReason(null);
        listing.setViewCount(0);
        listingRepository.create(listing);
        notifyAdminsOfPendingListing(listing.getTitle(), seller.getUsername(), currentAdmin.getUsername(),
                "Listing Created by Admin");
    }

    /** Admin action: edit any listing's content; it returns to Pending Review for re-approval. */
    public void updateListingAsAdmin(User admin, String id, Listing changes) throws SQLException {
        requireActiveAdmin(admin);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("A listing ID is required.");
        validateListing(changes);
        if (!listingRepository.updateAsAdmin(id, changes)) {
            throw new IllegalArgumentException("Listing not found.");
        }
    }

    /** Admin removal entry point; returns whether an existing listing was marked Removed. */
    public boolean removeListingAsAdmin(User admin, String listingId, String sellerUsername,
                                       String reason) throws SQLException {
        requireAdmin(admin);
        if (reason == null || reason.isBlank()){
            throw new IllegalArgumentException("Removal reason cannot be blank !");
        }

        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("A listing ID is required.");
        }
        if (sellerUsername == null || sellerUsername.isBlank()) {
            throw new IllegalArgumentException("A seller username is required.");
        }
        //read the listing and its open proposals before the status change so the
        //notifications can name the item and reach every affected buyer
        Listing listing = listingRepository.findByIdAndSeller(listingId, sellerUsername);
        List<Deal> affectedDeals = deals.findAffectedDeals(listingId);
        boolean removed = listingRepository.removeListing(listingId, sellerUsername, reason);
        if (!removed) {
            return false;
        }
        String title = listing == null ? "the listing" : "'" + listing.getTitle() + "'";
        notifyListingRemoved(sellerUsername, title, shortened(reason), affectedDeals);
        return true;
    }

    /**
     * Notifies the seller and every buyer with an open proposal (PENDING, COUNTERED or
     * ACCEPTED) on the removed listing. Best-effort: the listing is already removed, so a
     * notification failure is logged instead of failing the takedown.
     */
    private void notifyListingRemoved(String sellerUsername, String title, String reason,
                                      List<Deal> affectedDeals) {
        sendNotification(sellerUsername, "Listing Removed",
                "Your listing " + title + " was removed by an administrator. Reason: " + reason);
        Set<String> buyers = new java.util.LinkedHashSet<>();
        for (Deal deal : affectedDeals) {
            String buyer = deal.getBuyerUsername();
            if (buyer != null && !buyer.isBlank() && !buyer.equalsIgnoreCase(sellerUsername)) {
                buyers.add(buyer);
            }
        }
        for (String buyer : buyers) {
            sendNotification(buyer, "Listing Removed",
                    "The listing " + title + " you made an offer on was removed by an administrator."
                            + " Reason: " + reason);
        }
    }

    /** Persists one listing-related notification; notification failures never block an action. */
    private void sendNotification(String username, String title, String message) {
        if (username == null || username.isBlank()) return;
        try {
            notificationRepository.create(new Notification(username, title, message, "Listing"));
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Failed to create listing notification for " + username, ex);
        } catch (IllegalArgumentException invalid) {
            LOGGER.log(Level.WARNING, "Invalid listing notification for " + username, invalid);
        }
    }

    private static String shortened(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.codePointCount(0, text.length()) <= NOTIFICATION_REASON_LIMIT) return text;
        int end = text.offsetByCodePoints(0, NOTIFICATION_REASON_LIMIT);
        return text.substring(0, end) + "...";
    }

    //load an owned listing in any status; edit/delete apply their extra checks separately
    public Listing getListing(User user, String id) throws SQLException {
        User current = requireActiveSeller(user);
        Listing listing = listingRepository.findByIdAndSeller(id, current.getUsername());
        if (listing == null) throw new IllegalArgumentException("Listing not found or no longer available to your account.");
        return listing;
    }

    public void updateListing(User user, String id, Listing changes) throws SQLException {
        ListingChangePreview preview = previewListingChange(user, id);
        if (!preview.affectedVersions().isEmpty()) throw confirmationRequired();
        updateListing(user, id, changes, preview);
    }

    public void deleteListing(User user, String id) throws SQLException {
        ListingChangePreview preview = previewListingChange(user, id);
        if (!preview.affectedVersions().isEmpty()) throw confirmationRequired();
        deleteListing(user, id, preview);
    }

    /** Exact listing/proposal versions shown in the seller's confirmation dialog. */
    public record ListingChangePreview(String listingId, long listingVersion, Map<String, Long> affectedVersions) {
        public ListingChangePreview { affectedVersions = Map.copyOf(affectedVersions); }
    }

    public ListingChangePreview previewListingChange(User user, String id) throws SQLException {
        Listing listing = getListing(user, id);
        checkCanChange(listing);
        List<Deal> affected = deals.findAffectedDeals(id);
        requireNoAcceptedDeals(affected);
        return new ListingChangePreview(id, listing.getRowVersion(), proposalVersions(affected));
    }

    public void updateListing(User user, String id, Listing changes, ListingChangePreview confirmed) throws SQLException {
        validateListing(changes);
        changeListing(user, id, changes, confirmed);
    }

    public void deleteListing(User user, String id, ListingChangePreview confirmed) throws SQLException {
        changeListing(user, id, null, confirmed);
    }

    private void changeListing(User user, String id, Listing changes, ListingChangePreview confirmed) throws SQLException {
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new IllegalArgumentException("An active seller account is required.");
        }
        if (confirmed == null || !Objects.equals(id, confirmed.listingId())) throw confirmationRequired();
        try (Connection conn = connections.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Listing existing = listingRepository.findByIdAndSellerForUpdate(conn, id, user.getUsername());
                if (existing == null) throw new IllegalArgumentException("Listing not found or no longer available to your account.");
                User current = userRepository.findByUsername(conn, user.getUsername());
                if (current == null || !current.isActive() || current.getRole() != UserRole.SELLER) {
                    throw new IllegalArgumentException("An active seller account is required.");
                }
                checkCanChange(existing);
                List<Deal> affected = deals.findAffectedDeals(conn, id);
                requireNoAcceptedDeals(affected);
                if (existing.getRowVersion() != confirmed.listingVersion()
                        || !proposalVersions(affected).equals(confirmed.affectedVersions())) {
                    throw new IllegalArgumentException("The listing or its proposals changed. Reload and confirm the latest details.");
                }
                LocalDateTime now = LocalDateTime.now();
                for (Deal deal : affected) {
                    if (!deals.updateStatus(conn, deal.getId(), deal.getStatus(), deal.getRowVersion(), DealStatus.CANCELLED, now)) {
                        throw new IllegalArgumentException("A proposal changed. Reload and confirm again.");
                    }
                    notificationRepository.create(conn, new Notification(deal.getBuyerUsername(),
                            changes == null ? "Listing Removed" : "Listing Changed",
                            "The seller " + (changes == null ? "removed" : "edited") + " listing '"
                                    + existing.getTitle() + "'. Your pending proposal was cancelled.", "Listing"));
                }
                if (changes == null) {
                    listingRepository.deleteOwned(conn, id, current.getUsername());
                } else {
                    listingRepository.updateOwned(conn, id, current.getUsername(), changes);
                    notifyAdminsOfResubmission(conn, changes.getTitle(), current.getUsername());
                }
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    private void notifyAdminsOfResubmission(Connection conn, String title, String seller) throws SQLException {
        List<String> recipients = new java.util.ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement("SELECT username FROM users WHERE role = 'ADMIN' AND active = TRUE FOR SHARE");
             ResultSet rows = stmt.executeQuery()) {
            while (rows.next()) recipients.add(rows.getString("username"));
        }
        for (String recipient : recipients) notificationRepository.create(conn, new Notification(recipient,
                "Listing Resubmitted for Review", "Listing '" + title + "' by @" + seller + " is waiting for review.", "New Listing"));
    }

    private static Map<String, Long> proposalVersions(List<Deal> affected) {
        return affected.stream().collect(Collectors.toMap(Deal::getId, Deal::getRowVersion));
    }

    private static void requireNoAcceptedDeals(List<Deal> affected) {
        if (affected.stream().anyMatch(deal -> deal.getStatus() == DealStatus.ACCEPTED)) {
            throw new IllegalArgumentException("This listing has an accepted deal. Resolve the deal before editing or deleting it.");
        }
    }

    private static IllegalArgumentException confirmationRequired() {
        return new IllegalArgumentException("Review and confirm cancellation of the affected proposals before changing this listing.");
    }

    /**
     * Creates a "New Listing" notification for every active admin (except the admin who
     * performed the action) so the review queue is visible from the notification board.
     * Called after a listing has been saved in Pending Review.
     */
    private void notifyAdminsOfPendingListing(String listingTitle, String sellerUsername,
                                              String actorUsername, String notificationTitle)
            throws SQLException {
        String message = "Listing '" + listingTitle + "' by @" + sellerUsername
                + " is waiting for review.";
        for (User admin : userRepository.findAll()) {
            if (admin.getRole() != UserRole.ADMIN || !admin.isActive()) continue;
            if (actorUsername != null && admin.getUsername().equalsIgnoreCase(actorUsername)) continue;
            notificationRepository.create(new Notification(
                    admin.getUsername(), notificationTitle, message, "New Listing"));
        }
    }

    //check before opening the edit form and again before saving or deleting
    //this checks change restrictions only; getListing performs account and ownership checks
    //Pending proposal cancellation is rechecked in the confirmed change transaction.
    public void checkCanChange(Listing listing) {
        if (!Set.of(ListingStatus.PENDING_REVIEW, ListingStatus.LIVE, ListingStatus.REJECTED)
                .contains(listing.getStatus())) {
            throw new IllegalArgumentException("Reserved, completed, or removed listings cannot be edited or deleted.");
        }
    }

    //creation and editing share the same field rules
    private void validateListing(Listing listing) {
        if (listing == null) throw new IllegalArgumentException("Listing details are required.");
        if (listing.getType() == null) throw new IllegalArgumentException("Choose a listing type.");
        //trim required text and keep it within the database column limits
        listing.setTitle(requiredText(listing.getTitle(), "Item name", 255));
        listing.setCategory(requiredText(listing.getCategory(), "Category", 100));
        listing.setMeetupLocation(requiredText(listing.getMeetupLocation(), "Meet-up location", 100));
        String description = requiredText(listing.getDescription(), "Description", 65535);
        //MySQL TEXT has a byte limit; some characters use more than one UTF-8 byte
        if (description.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IllegalArgumentException("Description must be at most 65,535 UTF-8 bytes.");
        }
        listing.setDescription(description);
        if (!Set.of("New", "Like New", "Used", "For Parts").contains(
                Objects.toString(listing.getCondition(), ""))) {
            throw new IllegalArgumentException("Choose a valid item condition.");
        }
        //Sell needs a positive price that fits DECIMAL(10,2), without rounding away extra decimals
        if (listing.getType() == ListingType.SELL) {
            Double price = listing.getPrice();
            if (price == null || !Double.isFinite(price) || price <= 0
                    || price > 99_999_999.99
                    || BigDecimal.valueOf(price).stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException(
                        "Enter a positive price up to HK$99,999,999.99 with at most two decimal places.");
            }
        } else {
            listing.setPrice(null); //Lend and Give Away do not store a sale price
        }
        //Lend needs both a whole-number duration and its unit
        if (listing.getType() == ListingType.LEND) {
            if (listing.getLendingDuration() == null || listing.getLendingDuration() <= 0
                    || listing.getLendingUnit() == null) {
                throw new IllegalArgumentException("Choose a positive whole-number lending duration and a unit.");
            }
        } else {
            //clear lending details when the item is for sale or being given away
            listing.setLendingDuration(null);
            listing.setLendingUnit(null);
        }

        //photos are optional, but any supplied data must fit the six MEDIUMBLOB rows
        if (listing.getPhotos() != null) {
            if (listing.getPhotos().size() > 6) {
                throw new IllegalArgumentException("A listing can have at most six photos.");
            }
            for (byte[] photo : listing.getPhotos()) {
                if (photo == null || photo.length == 0 || photo.length > Listing.MAX_PHOTO_BYTES) {
                    throw new IllegalArgumentException("Each photo must be nonempty and smaller than 16 MiB.");
                }
            }
        }

    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        String trimmed = value.trim();
        //count full Unicode characters, including those represented by two Java chars
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new IllegalArgumentException(label + " must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
