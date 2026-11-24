package com.hkust.marketplace.models;

import java.time.LocalDateTime;

/** A closed deal and the signed-in buyer's own review, including moderated submissions. */
public record DealHistoryEntry(String dealId, String title, String category, String sellerUsername,
                               String sellerName, LocalDateTime closedAt, String type, DealStatus outcome,
                               Integer rating, String comment, String reply, boolean hidden, Listing.AgreedTerms agreedTerms) {
    public String sellerDisplay() {
        return sellerUsername == null ? "Deleted seller" : sellerName + " (@" + sellerUsername + ")";
    }

    public boolean canReview() {
        return sellerUsername != null && rating == null;
    }
}
