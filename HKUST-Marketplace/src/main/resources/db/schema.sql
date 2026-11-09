USE hkust_marketplace;

CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(50) PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('BUYER', 'SELLER', 'ADMIN') NOT NULL,
    residence_hall VARCHAR(100),
    bio TEXT,
    preferred_meetup VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_username_not_blank
    CHECK (CHAR_LENGTH(TRIM(username)) > 0),
    CONSTRAINT chk_full_name_not_blank
    CHECK (CHAR_LENGTH(TRIM(full_name)) > 0)
    );

-- ============================================================
-- Seed demo accounts (same credentials as the in-memory demo).
-- Passwords are salted Argon2id hashes. Demo login passwords remain
-- admin123 for admin and password1 for the other four accounts.
-- Existing plaintext rows can be upgraded with PasswordMigration;
-- successful legacy logins also upgrade their stored password.
-- INSERT IGNORE keeps this script re-runnable (username is the PK).
-- ============================================================
INSERT IGNORE INTO users
    (username, full_name, password_hash, role,
     residence_hall, bio, preferred_meetup, active)
VALUES
    ('admin',   'HKUST Marketplace Admin', '$argon2id$v=19$m=19456,t=2,p=1$5GUYDuRgfgAJLT/ARasiFg$vcSHZNWD31oH57tee9uX5iI4CX04ddGsIrH0Adbte80', 'ADMIN', NULL, NULL, NULL, TRUE),
    ('alice',   'Alice Chan', '$argon2id$v=19$m=19456,t=2,p=1$aZ1KIYfT1gSgVyl24sLYzw$xSPGD8I+HbBA0ZkB26Bet86bpWEU0PSdiFtBsioUyZk', 'BUYER', 'Hall 3', NULL, NULL, TRUE),
    ('bob',     'Bob Wong', '$argon2id$v=19$m=19456,t=2,p=1$6w1dLgOqHE5N7fp1c0MhLQ$DCCz4KqF9b+qzJlSw4CIQE0IwrkH197OuzfEhUvyJZw', 'BUYER', 'Off-campus', NULL, NULL, TRUE),
    ('charlie', 'Charlie Lee', '$argon2id$v=19$m=19456,t=2,p=1$UV8Td+PqbVd0ktsDMjrk+A$SnWIPrnQNK+RR4o5lRXhpQ3ZsciS9Oh3cNm4hTnTjJw', 'SELLER', NULL,
     'Final year CSE student selling textbooks and gadgets.', 'Library', TRUE),
    ('diana',   'Diana Ng', '$argon2id$v=19$m=19456,t=2,p=1$7+V42kBf0v3aAqxwmBayyQ$SBsvvJ/pDs3ZPNtEvFGIKVrtGuSZBfTUVj1WBM70K2M', 'SELLER', NULL,
     'Selling furniture and kitchen items before graduation.', 'Hall 5 Lobby', TRUE);
