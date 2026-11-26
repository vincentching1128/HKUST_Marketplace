package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;

/**
 * Simple session holder for the currently logged-in user.
 * TODO: Replace with proper JWT / session token handling when backend is added.
 */
public class SessionManager {
    private static User currentUser;

    /**
     * Stores the authenticated user as the session's current user.
     *
     * <p>Any previously signed-in user is replaced. Passing {@code null} clears the session,
     * which is equivalent to calling {@link #logout()}. The session is process-wide static
     * state and is intended to be driven from the JavaFX Application Thread.
     *
     * @param user the signed-in user, or {@code null} to clear the session
     */
    public static void login(User user) {
        currentUser = user;
    }

    /**
     * Clears the current session so no user is signed in.
     *
     * <p>Safe to call when no user is signed in; later role and login checks return
     * {@code false} until {@link #login(User)} is called again.
     */
    public static void logout() {
        currentUser = null;
    }

    /**
     * Returns the user currently signed in.
     *
     * @return the current user, or {@code null} when the session is empty
     */
    public static User getCurrentUser() {
        return currentUser;
    }

    /**
     * Reports whether a user is currently signed in.
     *
     * @return {@code true} when a current user is set, {@code false} otherwise
     */
    public static boolean isLoggedIn() {
        return currentUser != null;
    }

    /**
     * Reports whether the current user has the buyer role.
     *
     * @return {@code true} only when a current user exists and is a buyer; {@code false} for
     *         signed-out users and every other role
     */
    public static boolean isBuyer() {
        return currentUser != null && currentUser.getRole() == com.hkust.marketplace.models.UserRole.BUYER;
    }

    /**
     * Reports whether the current user has the seller role.
     *
     * @return {@code true} only when a current user exists and is a seller; {@code false} for
     *         signed-out users and every other role
     */
    public static boolean isSeller() {
        return currentUser != null && currentUser.getRole() == com.hkust.marketplace.models.UserRole.SELLER;
    }

    /**
     * Reports whether the current user has the administrator role.
     *
     * @return {@code true} only when a current user exists and is an administrator;
     *         {@code false} for signed-out users and every other role
     */
    public static boolean isAdmin() {
        return currentUser != null && currentUser.getRole() == com.hkust.marketplace.models.UserRole.ADMIN;
    }
}
