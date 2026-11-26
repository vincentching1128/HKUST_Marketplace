package com.hkust.marketplace.models;

/**
 * Time units accepted for lending durations.
 *
 * <p>Supports {@code SECONDS} in addition to day, week, and month units so short borrowing
 * periods can be exercised by services and tests.
 */
public enum LendingUnit {
    DAYS("Day", "Days"),
    WEEKS("Week", "Weeks"),
    MONTHS("Month", "Months"),
    SECONDS("Second", "Seconds");

    private final String singular;
    private final String plural;

    LendingUnit(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    public String getDisplayName(int duration) {
        return duration == 1 ? singular : plural;
    }

    @Override
    public String toString() {
        return plural;
    }
}
