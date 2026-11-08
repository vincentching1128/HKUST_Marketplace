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
-- Passwords are stored as plaintext ONLY for the frontend demo;
-- replace with BCrypt hashes once AuthService hashing is implemented.
-- INSERT IGNORE keeps this script re-runnable (username is the PK).
-- ============================================================
INSERT IGNORE INTO users
    (username, full_name, password_hash, role,
     residence_hall, bio, preferred_meetup, active)
VALUES
    ('admin',   'HKUST Marketplace Admin', 'admin123',  'ADMIN',  NULL, NULL, NULL, TRUE),
    ('alice',   'Alice Chan',              'password1', 'BUYER',  'Hall 3', NULL, NULL, TRUE),
    ('bob',     'Bob Wong',                'password1', 'BUYER',  'Off-campus', NULL, NULL, TRUE),
    ('charlie', 'Charlie Lee',             'password1', 'SELLER', NULL,
     'Final year CSE student selling textbooks and gadgets.', 'Library', TRUE),
    ('diana',   'Diana Ng',                'password1', 'SELLER', NULL,
     'Selling furniture and kitchen items before graduation.', 'Hall 5 Lobby', TRUE);
