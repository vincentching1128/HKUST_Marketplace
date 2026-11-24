package com.hkust.marketplace.controllers;

import javafx.scene.control.Label;
import java.util.Locale;

/** Shared presentation for seller ratings across portals. */
public final class RatingDisplay {
    public static final String SUMMARY_STYLE = "-fx-font-weight: bold; -fx-text-fill: #A68137;";
    public static final String META_STYLE = "-fx-text-fill: #5a5a7a; -fx-font-size: 12px;";

    private RatingDisplay() {}

    public static String average(double rating) {
        return String.format(Locale.ENGLISH, "\u2605 %.2f / 5", rating);
    }

    public static String average(double rating, long count) {
        return average(rating) + " (" + count + (count == 1 ? " review)" : " reviews)");
    }

    public static Label stars(int rating) {
        Label label = new Label("\u2605".repeat(rating) + "\u2606".repeat(5 - rating));
        label.setStyle("-fx-text-fill: #A68137; -fx-font-size: 16px;");
        label.setAccessibleText(rating + " out of 5 stars");
        return label;
    }

    public static String buyerName(String username) {
        return username == null ? "Deleted buyer" : "@" + username;
    }
}
