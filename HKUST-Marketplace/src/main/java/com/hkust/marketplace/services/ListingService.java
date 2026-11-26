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

/**
 * Listing lifecycle service for sellers and administrators, backed by MySQL.
 *
 * <p>Creates and validates listings and photos, performs seller and admin edits/removals,
 * enforces ownership and optimistic-lock checks, cancels affected proposals, and writes
 * related notifications inside caller transactions where required.
 */
public class ListingService {
    private static final int NOTIFICATION_REASON_LIMIT = 500;

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final NotificationRepository notificationRepository;
    private final DealRepository deals;
    private final DealRepository.ConnectionProvider connections;

    /** Creates a service backed by the default repositories and database connections. */
    public ListingService() {
        this(new UserRepository(), new ListingRepository(), new NotificationRepository());
    }

    /**
     * Creates a service that writes notifications through a new default
     * {@link NotificationRepository}.
     *
     * <p>Kept for existing callers.
     *
     * @param userRepository the account repository used for active-role checks
     * @param listingRepository the listing repository
     * @throws NullPointerException if any argument is {@code null}
     */
    public ListingService(UserRepository userRepository, ListingRepository listingRepository) {
        this(userRepository, listingRepository, new NotificationRepository());
    }

    /**
     * Creates a service with explicit account, listing, and notification repositories.
     *
     * @param userRepository the account repository used for role and ownership checks
     * @param listingRepository the listing repository
     * @param notificationRepository the repository used for listing notifications
     * @throws NullPointerException if any argument is {@code null}
     */
    public ListingService(UserRepository userRepository, ListingRepository listingRepository,
                          NotificationRepository notificationRepository) {
        this(userRepository, listingRepository, notificationRepository, new DealRepository(), DatabaseManager::getConnection);
    }

    /**
     * Creates a service with fully supplied collaborators; useful for tests.
     *
     * @param userRepository the account repository used for active-role checks
     * @param listingRepository the listing repository
     * @param notificationRepository the notification repository
     * @param deals the deal repository whose caller-owned transactions are used
     * @param connections supplies the JDBC connections whose commit and rollback this
     *     service owns
     * @throws NullPointerException if any argument is {@code null}
     */
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
    /**
     * Returns every non-removed listing owned by the signed-in seller, in any status.
     *
     * <p>The caller's account is re-read from the database and must be an active seller;
     * the seller name is taken from that account rather than from the UI.
     *
     * @param user the signed-in account
     * @return the seller's listings
     * @throws IllegalArgumentException if the stored account is missing, inactive, or not
     *     a seller
     * @throws SQLException if the account or listing query fails
     */
    public List<Listing> getMyListings(User user) throws SQLException {
        User current = requireActiveSeller(user);
        return listingRepository.findBySeller(current.getUsername());
    }

    /**
     * Returns the aggregate dashboard statistics for the signed-in seller.
     *
     * @param seller the signed-in seller account; its stored role and active flag are
     *     re-checked before the query
     * @return the seller's views, offers, completed deals, and average rating
     * @throws IllegalArgumentException if the stored account is missing, inactive, or not
     *     a seller
     * @throws SQLException if the account or statistics query fails
     */
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

    //Create and Edit also require the stored admin account to be active
    private User requireActiveAdmin(User user) throws SQLException {
        User current = user == null || user.getUsername() == null
                ? null : userRepository.findByUsername(user.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("An active admin account is required.");
        }
        return current;
    }

    /**
     * Creates a new seller listing that starts in Pending Review.
     *
     * <p>The listing is validated first, then the signed-in account is re-read and must be
     * an active seller. The service assigns the id, seller name, posted time, zero views,
     * and a cleared review result. The listing is inserted before every active admin
     * except the acting user is notified with "New Listing Submitted".
     *
     * @param user the signed-in seller
     * @param listing the listing values; required fields are validated and normalised
     * @throws IllegalArgumentException if the listing is invalid or the stored account is
     *     missing, inactive, or not a seller
     * @throws SQLException if the listing insert or the subsequent admin notifications fail
     */
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

    /**
     * Creates a listing on behalf of an active seller as an administrator.
     *
     * <p>The admin account must be active in the database, the listing must pass the
     * normal validation, and the target seller must be an active seller. The listing is
     * owned by the seller and starts in Pending Review; all active admins except the
     * acting admin are then notified.
     *
     * @param admin the acting administrator; its stored role and active flag are checked
     * @param sellerUsername the username of the seller who will own the listing
     * @param listing the listing values to create
     * @throws IllegalArgumentException if the admin is not an active admin, the listing is
     *     invalid, or the seller is missing or not an active seller
     * @throws SQLException if the listing insert or the subsequent admin notifications fail
     */
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

    /**
     * Edits any listing's content as an administrator and returns it to Pending Review.
     *
     * <p>The acting admin must be active. Inside one transaction the owned listing is
     * locked and re-checked against the supplied seller. Only Pending Review, Live, and
     * Rejected listings may be edited; Reserved, Sold, Lent, Given, and Removed listings
     * are rejected before any deal or content write. All open proposals are then loaded.
     * An accepted deal blocks the edit; every non-accepted open proposal is cancelled with
     * an optimistic-version check and its buyer is notified. The content is then updated,
     * the listing status becomes Pending Review with its previous review result cleared,
     * and the seller is notified using the pre-edit title. Any failure rolls the whole
     * transaction back.
     *
     * @param admin the acting administrator
     * @param sellerUsername the seller who must own the listing
     * @param id the listing identifier
     * @param changes the replacement content and photo set
     * @throws IllegalArgumentException if the admin is not active, an identifier is
     *     missing, the listing is missing or owned by another seller, its status is not
     *     Pending Review, Live, or Rejected, an accepted deal exists, or a proposal changed
     *     concurrently
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public void updateListingAsAdmin(User admin, String sellerUsername, String id, Listing changes) throws SQLException {
        requireActiveAdmin(admin);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("A listing ID is required.");
        if (sellerUsername == null || sellerUsername.isBlank()) {
            throw new IllegalArgumentException("A seller username is required.");
        }
        validateListing(changes);
        try (Connection conn = connections.getConnection()) {
            conn.setAutoCommit(false);
            try {
                //locks the owned row, validating the seller passed by the admin screen
                Listing existing = listingRepository.findByIdAndSellerForUpdate(conn, id, sellerUsername);
                if (existing == null) {
                    throw new IllegalArgumentException("Listing not found or no longer owned by this seller.");
                }
                checkCanChange(existing);
                //locks/loads the open proposals; an accepted deal blocks the edit (seller rule)
                List<Deal> affectedDeals = deals.findAffectedDeals(conn, id);
                requireNoAcceptedDeals(affectedDeals);
                //mirror the seller edit: cancel open proposals and tell each buyer
                LocalDateTime now = LocalDateTime.now();
                for (Deal deal : affectedDeals) {
                    if (!deals.updateStatus(conn, deal.getId(), deal.getStatus(), deal.getRowVersion(),
                            DealStatus.CANCELLED, now)) {
                        throw new IllegalArgumentException("A proposal changed. Reload and try again.");
                    }
                    notificationRepository.create(conn, new Notification(deal.getBuyerUsername(),
                            "Listing Changed",
                            "An administrator edited listing '" + existing.getTitle()
                                    + "'. Your proposal was cancelled.", "Listing"));
                }
                if (!listingRepository.updateAsAdmin(conn, id, changes)) {
                    throw new IllegalArgumentException("Listing not found.");
                }
                //same pattern as the buyer offer flow: notify inside the same transaction.
                //Use the title the seller/buyer knew before the edit, so the item is identifiable.
                notifyListingEditedbyAdmin(conn, sellerUsername, existing.getTitle());
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    /**
     * Soft-removes a listing as an administrator in one atomic transaction.
     *
     * <p>The acting account must be an active admin, and the reason and identifiers must be
     * non-blank. Inside one transaction the owned listing is locked; when no matching
     * non-removed listing exists the transaction rolls back and this method returns
     * {@code false}. Every open proposal ({@code PENDING}, {@code COUNTERED}, or
     * {@code ACCEPTED}) is locked and cancelled with an optimistic-version check; terminal
     * deals and their history are untouched. The listing is then marked Removed with the
     * trimmed reason. The seller and each distinct affected buyer receive a
     * {@code "Listing Removed"} notification inside the same transaction, so deal
     * cancellation, removal, and notifications commit or roll back together.
     *
     * @param admin the acting administrator; its stored role and active flag are checked
     * @param listingId the listing to remove
     * @param sellerUsername the seller who must own the listing
     * @param reason the removal reason shown to the users; it is trimmed before storage and
     *     may be shortened for the notification
     * @return {@code true} when an existing, not-yet-removed listing was marked Removed;
     *     {@code false} when no matching listing exists or it was already removed
     * @throws IllegalArgumentException if the account is not an active admin, the reason or
     *     an identifier is blank, or an open proposal changed concurrently
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public boolean removeListingAsAdmin(User admin, String listingId, String sellerUsername,
                                       String reason) throws SQLException {
        requireActiveAdmin(admin);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Removal reason cannot be blank !");
        }
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("A listing ID is required.");
        }
        if (sellerUsername == null || sellerUsername.isBlank()) {
            throw new IllegalArgumentException("A seller username is required.");
        }
        String removalReason = reason.trim();
        try (Connection conn = connections.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Listing existing = listingRepository.findByIdAndSellerForUpdate(
                        conn, listingId, sellerUsername);
                if (existing == null) {
                    conn.rollback();
                    return false;
                }
                List<Deal> affectedDeals = deals.findAffectedDeals(conn, listingId);
                LocalDateTime now = LocalDateTime.now();
                for (Deal deal : affectedDeals) {
                    if (!deals.updateStatus(conn, deal.getId(), deal.getStatus(),
                            deal.getRowVersion(), DealStatus.CANCELLED, now)) {
                        throw new IllegalArgumentException("A proposal changed. Reload and try again.");
                    }
                }
                if (!listingRepository.removeListing(conn, listingId, sellerUsername, removalReason)) {
                    conn.rollback();
                    return false;
                }
                notifyListingRemoved(conn, sellerUsername, existing.getTitle(),
                        shortened(removalReason), affectedDeals);
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    /**
     * Inserts the seller and distinct affected-buyer notifications using the removal
     * transaction's connection; a failure rolls the whole removal back.
     */
    private void notifyListingRemoved(Connection conn, String sellerUsername, String listingTitle,
                                      String reason, List<Deal> affectedDeals) throws SQLException {
        notificationRepository.create(conn, new Notification(sellerUsername, "Listing Removed",
                "Your listing '" + listingTitle + "' was removed by an administrator. Reason: "
                        + reason, "Listing"));
        Set<String> buyers = new java.util.LinkedHashSet<>();
        for (Deal deal : affectedDeals) {
            String buyer = deal.getBuyerUsername();
            if (buyer != null && !buyer.isBlank() && !buyer.equalsIgnoreCase(sellerUsername)) {
                buyers.add(buyer);
            }
        }
        for (String buyer : buyers) {
            notificationRepository.create(conn, new Notification(buyer, "Listing Removed",
                    "The listing '" + listingTitle + "' you made an offer on was removed by an administrator."
                            + " Reason: " + reason, "Listing"));
        }
    }
    private void notifyListingEditedbyAdmin(Connection conn, String sellerUsername, String oldListingTitle)
            throws SQLException {
        notificationRepository.create(conn, new Notification(sellerUsername, "Listing Edited",
                "Your listing '" + oldListingTitle + "' was edited by an administrator and is awaiting review.",
                "Listing"));
    }

    private static String shortened(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.codePointCount(0, text.length()) <= NOTIFICATION_REASON_LIMIT) return text;
        int end = text.offsetByCodePoints(0, NOTIFICATION_REASON_LIMIT);
        return text.substring(0, end) + "...";
    }

    //load an owned listing in any status; edit/delete apply their extra checks separately
    /**
     * Returns one listing owned by the signed-in seller in any non-removed status.
     *
     * @param user the signed-in seller
     * @param id the listing identifier
     * @return the owned listing
     * @throws IllegalArgumentException if the stored account is not an active seller, or
     *     the listing does not exist, is removed, or is not owned by that seller
     * @throws SQLException if the account or listing query fails
     */
    public Listing getListing(User user, String id) throws SQLException {
        User current = requireActiveSeller(user);
        Listing listing = listingRepository.findByIdAndSeller(id, current.getUsername());
        if (listing == null) throw new IllegalArgumentException("Listing not found or no longer available to your account.");
        return listing;
    }

    /**
     * Edits an owned listing after confirming that affected proposals may be cancelled.
     *
     * <p>If the listing currently has open proposals, this overload throws so the caller
     * can show the confirmation preview first and call the four-argument overload with the
     * confirmed versions.
     *
     * @param user the signed-in seller
     * @param id the listing identifier
     * @param changes the replacement content and photo set
     * @throws IllegalArgumentException if the listing cannot be changed, has affected
     *     proposals that require confirmation, has an accepted deal, or the new content is
     *     invalid
     * @throws SQLException if a database read or write fails
     */
    public void updateListing(User user, String id, Listing changes) throws SQLException {
        ListingChangePreview preview = previewListingChange(user, id);
        if (!preview.affectedVersions().isEmpty()) throw confirmationRequired();
        updateListing(user, id, changes, preview);
    }

    /**
     * Soft-removes an owned listing after confirming that affected proposals may be
     * cancelled.
     *
     * <p>If the listing currently has open proposals, this overload throws so the caller
     * can show the confirmation preview first and call the three-argument overload with
     * the confirmed versions.
     *
     * @param user the signed-in seller
     * @param id the listing identifier
     * @throws IllegalArgumentException if the listing cannot be changed, has affected
     *     proposals that require confirmation, or has an accepted deal
     * @throws SQLException if a database read or write fails
     */
    public void deleteListing(User user, String id) throws SQLException {
        ListingChangePreview preview = previewListingChange(user, id);
        if (!preview.affectedVersions().isEmpty()) throw confirmationRequired();
        deleteListing(user, id, preview);
    }

    /**
     * Exact listing/proposal versions shown in the seller's confirmation dialog and
     * required again when the change is saved.
     *
     * @param listingId the listing identifier the preview belongs to
     * @param listingVersion the listing's row version at preview time
     * @param affectedVersions proposal identifiers mapped to the row version each proposal
     *     had at preview time; stored as an unmodifiable copy
     */
    public record ListingChangePreview(String listingId, long listingVersion, Map<String, Long> affectedVersions) {
        public ListingChangePreview { affectedVersions = Map.copyOf(affectedVersions); }
    }

    /**
     * Previews the exact listing and proposal versions a change would cancel.
     *
     * <p>The listing is loaded through {@link #getListing(User, String)}, which checks the
     * active seller and ownership, and then the status rules and accepted-deal rule are
     * verified.
     *
     * @param user the signed-in seller
     * @param id the listing identifier
     * @return the versions to echo back when confirming the change
     * @throws IllegalArgumentException if the account or ownership check fails, the
     *     listing status cannot be changed, or an accepted deal exists
     * @throws SQLException if a database query fails
     */
    public ListingChangePreview previewListingChange(User user, String id) throws SQLException {
        Listing listing = getListing(user, id);
        checkCanChange(listing);
        List<Deal> affected = deals.findAffectedDeals(id);
        requireNoAcceptedDeals(affected);
        return new ListingChangePreview(id, listing.getRowVersion(), proposalVersions(affected));
    }

    /**
     * Applies an edit using the versions returned by
     * {@link #previewListingChange(User, String)}.
     *
     * <p>Inside one transaction the listing is locked and the active-seller check is
     * repeated. The supplied versions must still match, otherwise the change is rejected.
     * Open proposals are cancelled and their buyers notified, the listing is updated and
     * returned to Pending Review, and every active admin is notified that it was
     * resubmitted. All writes commit together or roll back together.
     *
     * @param user the signed-in seller
     * @param id the listing identifier
     * @param changes the replacement content and photo set
     * @param confirmed the preview whose versions must still match
     * @throws IllegalArgumentException if the confirmation is missing or stale, the seller
     *     is no longer active or the owner, the status changed, an accepted deal exists,
     *     or the content is invalid
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public void updateListing(User user, String id, Listing changes, ListingChangePreview confirmed) throws SQLException {
        validateListing(changes);
        changeListing(user, id, changes, confirmed);
    }

    /**
     * Soft-removes an owned listing using the versions returned by
     * {@link #previewListingChange(User, String)}.
     *
     * <p>Inside one transaction the listing is locked and the active-seller check is
     * repeated, stale versions are rejected, open proposals are cancelled and their buyers
     * notified, and the listing is marked Removed while its deal history is preserved.
     *
     * @param user the signed-in seller
     * @param id the listing identifier
     * @param confirmed the preview whose versions must still match
     * @throws IllegalArgumentException if the confirmation is missing or stale, the seller
     *     is no longer active or the owner, the status changed, or an accepted deal exists
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
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
    /**
     * Checks whether a listing's current status permits an edit or deletion.
     *
     * <p>Only Pending Review, Live, and Rejected listings may be changed. This method
     * checks change restrictions only; the caller must still verify the account and
     * ownership.
     *
     * @param listing the listing to check
     * @throws IllegalArgumentException if the status is not Pending Review, Live, or
     *     Rejected
     */
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
