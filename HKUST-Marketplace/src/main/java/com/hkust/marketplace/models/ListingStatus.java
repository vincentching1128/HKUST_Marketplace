package com.hkust.marketplace.models;

/**
 * Lifecycle and moderation states of a listing.
 *
 * <p>{@code PENDING_REVIEW} and {@code REJECTED} are pre-publication states;
 * {@code LIVE}, {@code RESERVED}, {@code SOLD}, {@code LENT}, and {@code GIVEN} are visible
 * to buyers as appropriate; {@code REMOVED} is the soft-deleted state excluded from listing
 * finders.
 */
public enum ListingStatus {
    PENDING_REVIEW,
    LIVE,
    REJECTED,
    RESERVED,
    SOLD,
    LENT,
    GIVEN,
    REMOVED
}
