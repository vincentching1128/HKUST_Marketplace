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

public class ReviewService {
    public static final int MAX_TEXT_LENGTH = 2000;

    private final ReviewRepository reviews = new ReviewRepository();
    private final DealRepository deals = new DealRepository();
    private final UserRepository users = new UserRepository();
    private final NotificationRepository notifications = new NotificationRepository();

    public List<Review> getSellerReviews(User seller) throws SQLException {
        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            User current = requireRole(connection, seller, UserRole.SELLER);
            List<Review> result = reviews.findBySeller(connection, current.getUsername());
            connection.commit();
            return result;
        }
    }

    /** Only public profile fields and visible reviews leave this service. */
    public record SellerProfile(String name, String username, String bio, String meetup, List<Review> reviews) {
        public OptionalDouble average() {
            return reviews.stream().mapToInt(Review::getRating).average();
        }
    }

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

    /** Saves one review per finished deal and notifies its seller in the same transaction. */
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
