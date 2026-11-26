package com.hkust.marketplace.controllers;

import javafx.scene.control.Label;
import java.util.Locale;

/** Shared presentation for seller ratings across portals. */
public final class RatingDisplay {
    /** Inline style used for average-rating summary labels. */
    public static final String SUMMARY_STYLE = "-fx-font-weight: bold; -fx-text-fill: #A68137;";
    /** Inline style used for secondary review metadata labels. */
    public static final String META_STYLE = "-fx-text-fill: #5a5a7a; -fx-font-size: 12px;";

    private RatingDisplay() {}

    /**
     * Formats an average rating as text with a leading star glyph.
     *
     * <p>Uses the English locale so the decimal separator is always a period.
     *
     * @param rating average rating to display
     * @return text such as {@code 4.50 / 5} preceded by a star glyph
     */
    public static String average(double rating) {
        return String.format(Locale.ENGLISH, "\u2605 %.2f / 5", rating);
    }

    /**
     * Formats an average rating together with its review count.
     *
     * @param rating average rating to display
     * @param count number of reviews; {@code 1} renders the singular label
     *        {@code review}, every other value renders {@code reviews}
     * @return the text from {@link #average(double)} followed by the parenthesised count
     */
    public static String average(double rating, long count) {
        return average(rating) + " (" + count + (count == 1 ? " review)" : " reviews)");
    }

    /**
     * Builds a read-only label containing five filled or empty star glyphs.
     *
     * <p>The label uses the shared rating style and exposes accessible text such as
     * {@code "4 out of 5 stars"}.
     *
     * @param rating number of filled stars, normally {@code 0} through {@code 5}
     * @return a new label showing the requested rating
     * @throws IllegalArgumentException if {@code rating} is negative or greater than
     *         {@code 5}
     */
    public static Label stars(int rating) {
        Label label = new Label("\u2605".repeat(rating) + "\u2606".repeat(5 - rating));
        label.setStyle("-fx-text-fill: #A68137; -fx-font-size: 16px;");
        label.setAccessibleText(rating + " out of 5 stars");
        return label;
    }

    /**
     * Returns the display name for the buyer who wrote a review.
     *
     * @param username buyer username, or {@code null} when the account was deleted
     * @return {@code "@username"}, or {@code "Deleted buyer"} when the username is
     *         {@code null}
     */
    public static String buyerName(String username) {
        return username == null ? "Deleted buyer" : "@" + username;
    }
}
