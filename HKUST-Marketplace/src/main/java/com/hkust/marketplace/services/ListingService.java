package com.hkust.marketplace.services;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.repositories.ListingRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.hkust.marketplace.utils.DummyDataService;

public class ListingService {
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;

    public ListingService() {
        this(new UserRepository(), new ListingRepository());
    }

    public ListingService(UserRepository userRepository, ListingRepository listingRepository) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.listingRepository = Objects.requireNonNull(listingRepository);
    }

    //use the signed-in account, rather than accepting a seller name from the screen
    public List<Listing> getMyListings(User user) throws SQLException {
        User current = requireActiveSeller(user);
        return listingRepository.findBySeller(current.getUsername());
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
        //admin active status is not part of this permission check
        return current;
    }

    //admin actions re-read the account so a demoted/deactivated admin is rejected
    private User requireActiveAdmin(User user) throws SQLException {
        User current = user == null || user.getUsername() == null
                ? null : userRepository.findByUsername(user.getUsername());
        if (current == null || !current.isActive() || current.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("An active admin account is required.");
        }
        return current;
    }

    //check the form details and seller account before saving a new listing
    public void createListing(User user, Listing listing) throws SQLException {
        validateListing(listing);

        User current = requireActiveSeller(user);
        //use the checked seller account and give this listing its own ID
        listing.setId(UUID.randomUUID().toString());
        listing.setSellerUsername(current.getUsername());
        listing.setSellerFullName(current.getFullName());
        //every new listing starts with no review result and waits for admin approval
        listing.setStatus(ListingStatus.PENDING_REVIEW);
        listing.setPostedAt(LocalDateTime.now());
        listing.setReviewedAt(null);
        listing.setRejectionReason(null);
        listing.setViewCount(0);
        //the repository commits the transaction, or rolls it back if saving fails
        listingRepository.create(listing);
    }

    /** Admin action: create a listing on behalf of an active seller; it starts Pending Review. */
    public void createListingAsAdmin(User admin, String sellerUsername, Listing listing) throws SQLException {
        requireActiveAdmin(admin);
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
        //TODO: call requireAdmin before accessing the listing repository
        requireAdmin(admin);
        //TODO: validate the listing ID, seller username, and nonblank removal reason
        if (reason == null || reason.isBlank()){
            throw new IllegalArgumentException("Removal reason cannot be blank !");
        }

        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("A listing ID is required.");
        }
        if (sellerUsername == null || sellerUsername.isBlank()) {
            throw new IllegalArgumentException("A seller username is required.");
        }
        //TODO: return listingRepository.removeListing(...) with the trimmed reason
        return listingRepository.removeListing(listingId, sellerUsername, reason);
    }

    //load an owned listing in any status; edit/delete apply their extra checks separately
    public Listing getListing(User user, String id) throws SQLException {
        User current = requireActiveSeller(user);
        Listing listing = listingRepository.findByIdAndSeller(id, current.getUsername());
        if (listing == null) throw new IllegalArgumentException("Listing not found or no longer available to your account.");
        return listing;
    }

    public void updateListing(User user, String id, Listing changes) throws SQLException {
        User current = requireActiveSeller(user);
        validateListing(changes);
        Listing existing = getListing(user, id);
        checkCanChange(existing);
        listingRepository.updateOwned(id, current.getUsername(), changes);
    }

    public void deleteListing(User user, String id) throws SQLException {
        User current = requireActiveSeller(user);
        Listing existing = getListing(user, id);
        checkCanChange(existing);
        listingRepository.deleteOwned(id, current.getUsername());
    }

    //check before opening the edit form and again before saving or deleting
    //this checks change restrictions only; getListing performs account and ownership checks
    //offers still use the existing in-memory store until the offer flow moves to MySQL
    public void checkCanChange(Listing listing) {
        if (!Set.of(ListingStatus.PENDING_REVIEW, ListingStatus.LIVE, ListingStatus.REJECTED)
                .contains(listing.getStatus())) {
            throw new IllegalArgumentException("Reserved, completed, or removed listings cannot be edited or deleted.");
        }
        boolean activeOffer = DummyDataService.getInstance().getAllDeals().stream()
                .anyMatch(deal -> Objects.equals(listing.getId(), deal.getListingId())
                        && ("Pending".equalsIgnoreCase(deal.getStatus())
                        || "Accepted".equalsIgnoreCase(deal.getStatus())
                        || "Countered".equalsIgnoreCase(deal.getStatus())));
        if (activeOffer) throw new IllegalArgumentException("Listings with active offers or requests cannot be edited or deleted.");
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

    //share the same blank and length checks across the text fields
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
