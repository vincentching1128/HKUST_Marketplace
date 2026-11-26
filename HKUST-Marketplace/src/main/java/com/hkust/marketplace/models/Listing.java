package com.hkust.marketplace.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Listing details shared by the screens and database repository.
 */
public class Listing {
    /**
     * Snapshot of the terms agreed with the accepted deal for a listing.
     *
     * @param price agreed sale price, when the deal is a sale
     * @param start borrowing start time, when the deal is a loan
     * @param due borrowing due time, when the deal is a loan
     */
    public record AgreedTerms(java.math.BigDecimal price, LocalDateTime start, LocalDateTime due) {
        private static final java.time.format.DateTimeFormatter DATE =
                java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH);

        public String display() {
            if (price != null) return String.format(java.util.Locale.ENGLISH, "Agreed price: HK$ %.2f", price);
            if (start != null && due != null) return "Agreed borrowing period: " + DATE.format(start) + " to " + DATE.format(due);
            return "Terms unavailable";
        }
    }

    public String termsDisplay() {
        if (type == ListingType.GIVEAWAY) return "Free";
        if (agreedTerms != null) return agreedTerms.display();
        if (type == ListingType.LEND) return "Lending duration: " + getLendingDurationDisplay();
        return String.format(java.util.Locale.ENGLISH, "Asking price: HK$ %.2f", price);
    }
    private AgreedTerms agreedTerms;

    public AgreedTerms getAgreedTerms() { return agreedTerms; }
    public void setAgreedTerms(AgreedTerms terms) { agreedTerms = terms; }
    public static final int MAX_PHOTO_BYTES = 16_777_215; //MySQL MEDIUMBLOB limit per photo
    private String id;
    private String title;
    private String description;
    private String category;
    private ListingType type;
    private ListingStatus status;
    private String condition;          // New / Like New / Used / For Parts
    private Double price;              // Only for SELL
    private Integer lendingDuration;  // Only for LEND
    private LendingUnit lendingUnit;
    private String meetupLocation;
    private String sellerUsername;
    private String sellerFullName;
    private LocalDateTime postedAt;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
    private int viewCount;
    private long rowVersion = 1;       // optimistic-lock column: bumped by every update
    private List<byte[]> photos = new ArrayList<>(); //actual image data, with up to six photos

    public Listing() {
        this.postedAt = LocalDateTime.now();
        this.status = ListingStatus.PENDING_REVIEW;
        this.viewCount = 0;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public ListingType getType() { return type; }
    public void setType(ListingType type) { this.type = type; }

    public ListingStatus getStatus() { return status; }
    public void setStatus(ListingStatus status) { this.status = status; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Integer getLendingDuration() { return lendingDuration; }
    public void setLendingDuration(Integer lendingDuration) { this.lendingDuration = lendingDuration; }

    //keep the old dummy data working until its listing flow is replaced
    @Deprecated
    public void setLendingDays(Integer days) {
        this.lendingDuration = days;
        this.lendingUnit = days == null ? null : LendingUnit.DAYS;
    }

    public LendingUnit getLendingUnit() { return lendingUnit; }
    public void setLendingUnit(LendingUnit lendingUnit) { this.lendingUnit = lendingUnit; }

    public String getLendingDurationDisplay() {
        if (lendingDuration == null || lendingUnit == null) return "N/A";
        return lendingDuration + " " + lendingUnit.getDisplayName(lendingDuration);
    }

    public String getMeetupLocation() { return meetupLocation; }
    public void setMeetupLocation(String meetupLocation) { this.meetupLocation = meetupLocation; }

    public String getSellerUsername() { return sellerUsername; }
    public void setSellerUsername(String sellerUsername) { this.sellerUsername = sellerUsername; }

    public String getSellerFullName() { return sellerFullName; }
    public void setSellerFullName(String sellerFullName) { this.sellerFullName = sellerFullName; }

    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public long getRowVersion() { return rowVersion; }
    public void setRowVersion(long rowVersion) { this.rowVersion = rowVersion; }

    public List<byte[]> getPhotos() { return photos; }
    public void setPhotos(List<byte[]> photos) { this.photos = photos; }

    public String getPriceDisplay() {
        if (type == ListingType.SELL && price != null) {
            return String.format("HK$ %.0f", price);
        } else if (type == ListingType.LEND) {
            return "Borrow";
        } else {
            return "Free";
        }
    }

    public String getTypeDisplay() {
        return switch (type) {
            case SELL -> "For Sale";
            case LEND -> "For Lending";
            case GIVEAWAY -> "Giveaway";
        };
    }
}
