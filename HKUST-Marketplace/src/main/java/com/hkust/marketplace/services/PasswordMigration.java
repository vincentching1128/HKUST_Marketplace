package com.hkust.marketplace.services;

import java.sql.SQLException;

/** One-time migration for a database created with the earlier plaintext seed script. */
public final class PasswordMigration {
    private PasswordMigration() { }

    /**
     * Runs the legacy-password migration and prints the number of upgraded accounts.
     *
     * @param args ignored
     * @throws SQLException if the account scan or an upgrade fails
     */
    public static void main(String[] args) throws SQLException {
        int migrated = new AuthService().migrateLegacyPasswords();
        System.out.println("Accounts upgraded to Argon2id: " + migrated);
    }
}
