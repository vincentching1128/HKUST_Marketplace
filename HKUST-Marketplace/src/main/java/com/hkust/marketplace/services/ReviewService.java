package com.hkust.marketplace.services;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.ReviewRepository;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Seller-review lifecycle service backed by the MySQL {@code reviews} and {@code deals} tables.
 *
 * <p>Validates completed-deal eligibility, writes reviews, seller replies, moderation flags,
 * and hidden/unhidden state while notifying affected users in the same transaction.
 */
public class ReviewService {
    /** Maximum accepted review comment or reply length in characters. */
    public static final int MAX_TEXT_LENGTH = 2000;

    private final ReviewRepository reviews = new ReviewRepository();
    private final DealRepository deals = new DealRepository();
    private final UserRepository users = new UserRepository();
    private final NotificationRepository notifications = new NotificationRepository();

    /**
     * Returns the signed-in seller's visible reviews, newest first.
     *
     * <p>Reviews hidden by an administrator are excluded. The lookup runs on its own
     * connection and commits before returning.
     *
     * @param seller the signed-in seller
     * @return the seller's visible reviews
     * @throws IllegalArgumentException if the stored account is missing, inactive, or not
     *     a seller
     * @throws SQLException if the account or review query fails
     */
    public List<Review> getSellerReviews(User seller) throws SQLException {
        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            User current = requireRole(connection, seller, UserRole.SELLER);
            List<Review> result = reviews.findBySeller(connection, current.getUsername());
            connection.commit();
            return result;
        }
    }

    /**
     * Only public profile fields and visible reviews leave this service.
     *
     * @param name the seller's display name
     * @param username the seller's username
     * @param bio the seller's biography
     * @param meetup the seller's preferred meet-up location
     * @param reviews the seller's visible reviews, newest first
     */
    public record SellerProfile(String name, String username, String bio, String meetup, List<Review> reviews) {
        /**
         * Computes the average rating over the visible reviews.
         *
         * @return the average rating, or an empty optional when there are no reviews
         */
        public OptionalDouble average() {
            return reviews.stream().mapToInt(Review::getRating).average();
        }
    }

    /**
     * Returns the public profile of a seller together with their visible reviews.
     *
     * <p>No signed-in account is required. Hidden reviews are excluded, and only public
     * fields (name, username, bio, preferred meet-up) are exposed.
     *
     * @param username the seller's username
     * @return the public seller profile and visible reviews
     * @throws IllegalArgumentException if no seller has that username
     * @throws SQLException if the account or review query fails
     */
    public SellerProfile getPublicProfile(String username) throws SQLException {
        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            User seller = users.findByUsername(connection, username);
            if (seller == null || seller.getRole() != UserRole.SELLER) {
                throw new IllegalArgumentException("This seller profile is unavailable.");
            }
            SellerProfile result = new SellerProfile(seller.getFullName(), seller.getUsername(), seller.getBio(),
                    seller.getPreferredMeetup(), List.copyOf(reviews.findBySeller(connection, seller.getUsername())));
            connection.commit();
            return result;
        }
    }

    /**
     * Saves one review for a finished deal the buyer participated in.
     *
     * <p>The comment is stripped and must contain 1 to 2,000 characters, and the rating
     * must be from 1 to 5. Inside one transaction the buyer is re-read and must be active,
     * the deal is locked, and it must belong to that buyer and be Completed or Returned.
     * A deal can be reviewed only once, and a deal whose seller account was deleted cannot
     * be reviewed. The review and the seller's "New Seller Review" notification commit
     * together.
     *
     * @param buyer the signed-in buyer
     * @param dealId the finished deal identifier
     * @param rating the rating from 1 to 5
     * @param comment the review text
     * @throws IllegalArgumentException if the comment, rating, or deal id is invalid, the
     *     buyer is not active, the deal is not the buyer's finished deal, the deal was
     *     already reviewed, or the seller account was deleted
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public void submitReview(User buyer, String dealId, int rating, String comment) throws SQLException {
        String text = requireText(comment, "Review");
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Choose a rating from 1 to 5.");
        inTransaction(connection -> {
            User current = requireRole(connection, buyer, UserRole.BUYER);
            Deal deal = deals.findByIdForUpdate(connection, dealId).orElse(null);
            if (deal == null || !current.getUsername().equals(deal.getBuyerUsername())
                    || (deal.getStatus() != DealStatus.COMPLETED && deal.getStatus() != DealStatus.RETURNED)) {
                throw new IllegalArgumentException("You can only review one of your finished deals.");
            }
            if (deal.getSellerUsername() == null) throw new IllegalArgumentException("The seller account was deleted.");
            if (reviews.existsForDealForUpdate(connection, dealId)) {
                throw new IllegalArgumentException("You already reviewed this deal.");
            }
            reviews.create(connection, UUID.randomUUID().toString(), dealId, rating, text);
            notifications.create(connection, new Notification(deal.getSellerUsername(), "New Seller Review",
                    "@" + current.getUsername() + " gave you " + rating + "/5 for '"
                            + deal.getListingTitle() + "'. Open Reviews & Feedback to read it.", "Review"));
        });
    }

    /**
     * Adds the seller's single reply to one of their visible reviews.
     *
     * <p>The review must belong to the signed-in active seller, must not be hidden, and
     * must not already have a reply. The reply and the buyer's "Seller Replied to Your
     * Review" notification commit together.
     *
     * @param seller the signed-in seller
     * @param reviewId the review identifier
     * @param reply the reply text
     * @throws IllegalArgumentException if the reply text or review id is invalid, the
     *     seller is not active, the review is unavailable or hidden, the seller already
     *     replied, or the buyer account no longer exists
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public void reply(User seller, String reviewId, String reply) throws SQLException {
        String text = requireText(reply, "Reply");
        inTransaction(connection -> {
            Review review = requireSellerReview(connection, seller, reviewId);
            if (review.getSellerReply() != null) throw new IllegalArgumentException("You already replied to this review.");
            reviews.saveReply(connection, reviewId, text);
            notifications.create(connection, new Notification(review.getBuyerUsername(), "Seller Replied to Your Review",
                    "@" + review.getSellerUsername() + " replied to your review for '"
                            + review.getListingTitle() + "':\n\n" + text, "Review"));
        });
    }

    /**
     * Flags one of the seller's visible reviews for administrator moderation.
     *
     * <p>Flagging an already-flagged review is a no-op. The update and the notification to
     * every active admin commit together.
     *
     * @param seller the signed-in seller
     * @param reviewId the review identifier
     * @throws IllegalArgumentException if the review id is invalid, the seller is not
     *     active, or the review is missing, hidden, or not owned by the seller
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public void flag(User seller, String reviewId) throws SQLException {
        inTransaction(connection -> {
            Review review = requireSellerReview(connection, seller, reviewId);
            if (review.isFlagged()) return;
            reviews.flag(connection, reviewId);
            notifications.notifyAllAdmins(connection, "Review", "Seller Flagged a Review",
                    "@" + review.getSellerUsername() + " flagged the review by @" + review.getBuyerUsername()
                            + " for '" + review.getListingTitle() + "'. Open Seller Ratings to moderate it.");
        });
    }

    /**
     * Sets an administrator-controlled hidden state for a review.
     *
     * <p>The acting account must be an active admin. Setting the state the review already
     * has is a no-op. Hiding notifies the buyer and, when present, the seller in the same
     * transaction; unhiding sends no notification.
     *
     * @param admin the acting administrator
     * @param reviewId the review identifier
     * @param hidden {@code true} to hide the review from public view, {@code false} to
     *     restore it
     * @throws IllegalArgumentException if the admin is not active or the review no longer
     *     exists
     * @throws SQLException if any database read or write fails; the transaction is
     *     rolled back
     */
    public void setHidden(User admin, String reviewId, boolean hidden) throws SQLException {
        inTransaction(connection -> {
            requireRole(connection, admin, UserRole.ADMIN);
            Review review = reviews.findForModeration(connection, reviewId);
            if (review == null) throw new IllegalArgumentException("This review no longer exists.");
            if (review.isHidden() == hidden) return;
            reviews.setHidden(connection, reviewId, hidden);
            if (!hidden) return;

            String item = "'" + review.getListingTitle() + "'";
            if (review.getBuyerUsername() != null) {
                notifications.create(connection, new Notification(review.getBuyerUsername(), "Your Review Was Hidden",
                        "An admin hid your review for " + item
                                + " from public view. You can still see it in Deal History.", "Review"));
            }
            if (review.getSellerUsername() != null) {
                notifications.create(connection, new Notification(review.getSellerUsername(), "A Seller Review Was Hidden",
                        "An admin hid a review you received for " + item + " from public view.", "Review"));
            }
        });
    }

    private Review requireSellerReview(Connection connection, User seller, String reviewId) throws SQLException {
        User current = requireRole(connection, seller, UserRole.SELLER);
        Review review = reviews.findBySellerForUpdate(connection, current.getUsername(), reviewId);
        if (review == null || review.isHidden()) {
            throw new IllegalArgumentException("This review is no longer available for action.");
        }
        return review;
    }

    private User requireRole(Connection connection, User actor, UserRole role) throws SQLException {
        User current = actor == null || actor.getUsername() == null || actor.getUsername().isBlank()
                ? null : users.findByUsername(connection, actor.getUsername());
        if (current == null || !current.isActive() || current.getRole() != role) {
            throw new IllegalArgumentException("An active " + role.name().toLowerCase(java.util.Locale.ROOT)
                    + " account is required.");
        }
        return current;
    }

    private static String requireText(String value, String label) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + " must be 1 to " + MAX_TEXT_LENGTH + " characters.");
        }
        return text;
    }

    private void inTransaction(ReviewAction action) throws SQLException {
        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                action.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface ReviewAction {
        void run(Connection connection) throws SQLException;
    }
}
