package com.hkust.marketplace.models;

import java.time.LocalDateTime;

/** Stored references control access; name/title snapshots retain readable history. */
public record ChatThread(String id, String listingId, String listingTitle, ListingType listingType,
                         String buyerUsername, String sellerUsername, String buyerName,
                         String sellerName, LocalDateTime createdAt) {}
