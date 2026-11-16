package com.hkust.marketplace.models;

/** Shared persisted states for proposals and their resulting deals. */
public enum DealStatus {
    PENDING, COUNTERED, ACCEPTED, DECLINED, CANCELLED, COMPLETED, RETURNED;

    public boolean isOpen() {
        return this == PENDING || this == COUNTERED || this == ACCEPTED;
    }

    public String getDisplayName() {
        String lower = name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public boolean canTransitionTo(DealStatus next) {
        if (next == null) return false;
        return switch (this) {
            case PENDING, COUNTERED -> next == COUNTERED || next == ACCEPTED || next == DECLINED || next == CANCELLED;
            case ACCEPTED -> next == COMPLETED || next == RETURNED || next == CANCELLED;
            case DECLINED, CANCELLED, COMPLETED, RETURNED -> false;
        };
    }
}
