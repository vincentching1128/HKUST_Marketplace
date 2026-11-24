package com.hkust.marketplace.models;

import java.time.LocalDateTime;

public class Deal {
    private String id;
    private String listingId;
    private String listingTitle;
    private String buyerUsername;
    private String sellerUsername;
    private ListingType dealType;
    private DealStatus status;
    private Double offerPrice;
    private LocalDateTime borrowStart;
    private LocalDateTime borrowEnd;
    private Double counterOfferPrice;
    private LocalDateTime counterBorrowStart;
    private LocalDateTime counterBorrowEnd;
    private LocalDateTime returnedAt;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private boolean rated;
    private long rowVersion = 1;

    public Deal() {
        this.createdAt = LocalDateTime.now();
        this.status = DealStatus.PENDING;
        this.rated = false;
    }

    public String agreedTermsDisplay() {
        if (status != DealStatus.ACCEPTED && status != DealStatus.COMPLETED && status != DealStatus.RETURNED) return "";
        if (dealType == ListingType.GIVEAWAY) return "Free";
        return new Listing.AgreedTerms(offerPrice == null ? null : java.math.BigDecimal.valueOf(offerPrice),
                borrowStart, borrowEnd).display();
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getListingId() { return listingId; }
    public void setListingId(String listingId) { this.listingId = listingId; }

    public String getListingTitle() { return listingTitle; }
    public void setListingTitle(String listingTitle) { this.listingTitle = listingTitle; }

    public String getBuyerUsername() { return buyerUsername; }
    public void setBuyerUsername(String buyerUsername) { this.buyerUsername = buyerUsername; }

    public String getSellerUsername() { return sellerUsername; }
    public void setSellerUsername(String sellerUsername) { this.sellerUsername = sellerUsername; }

    public ListingType getDealType() { return dealType; }
    public void setDealType(ListingType dealType) { this.dealType = dealType; }

    public DealStatus getStatus() { return status; }
    public void setStatus(DealStatus status) { this.status = java.util.Objects.requireNonNull(status); }

    public Double getOfferPrice() { return offerPrice; }
    public void setOfferPrice(Double offerPrice) { this.offerPrice = offerPrice; }

    public LocalDateTime getBorrowStart() { return borrowStart; }
    public void setBorrowStart(LocalDateTime borrowStart) { this.borrowStart = borrowStart; }

    public LocalDateTime getBorrowEnd() { return borrowEnd; }
    public void setBorrowEnd(LocalDateTime borrowEnd) { this.borrowEnd = borrowEnd; }

    public Double getCounterOfferPrice() { return counterOfferPrice; }
    public void setCounterOfferPrice(Double counterOfferPrice) { this.counterOfferPrice = counterOfferPrice; }

    public LocalDateTime getCounterBorrowStart() { return counterBorrowStart; }
    public void setCounterBorrowStart(LocalDateTime counterBorrowStart) { this.counterBorrowStart = counterBorrowStart; }

    public LocalDateTime getCounterBorrowEnd() { return counterBorrowEnd; }
    public void setCounterBorrowEnd(LocalDateTime counterBorrowEnd) { this.counterBorrowEnd = counterBorrowEnd; }

    public LocalDateTime getReturnedAt() { return returnedAt; }
    public void setReturnedAt(LocalDateTime returnedAt) { this.returnedAt = returnedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public boolean isRated() { return rated; }
    public void setRated(boolean rated) { this.rated = rated; }

    public long getRowVersion() { return rowVersion; }
    public void setRowVersion(long rowVersion) { this.rowVersion = rowVersion; }
}
