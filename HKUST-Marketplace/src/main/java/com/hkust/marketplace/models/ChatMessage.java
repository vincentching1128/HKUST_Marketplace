package com.hkust.marketplace.models;

import java.time.LocalDateTime;

/** Formal proposal terms are immutable snapshots, independent of later deal edits. */
public record ChatMessage(long id, String threadId, UserRole senderRole, String senderName,
                          Kind kind, String body, String proposalId, Long proposalVersion,
                          ListingType proposalType, Double proposalPrice, LocalDateTime borrowStart,
                          LocalDateTime borrowEnd, LocalDateTime createdAt) {
    /**
     * Message purposes in a chat thread: free text, proposal, or counter-proposal.
     */
    public enum Kind { TEXT, PROPOSAL, COUNTER }
}
