package com.hkust.marketplace.models;

public enum LendingUnit {
    DAYS("Day", "Days"),
    WEEKS("Week", "Weeks"),
    MONTHS("Month", "Months");

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
