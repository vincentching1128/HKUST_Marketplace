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


CREATE TABLE IF NOT EXISTS listings (
    -- listing with id as key, seller username (being referenced) and other item info
    -- depending on the type as well
    id VARCHAR(50) PRIMARY KEY,
    -- NULL once the seller account is hard-deleted; the listing row is kept as REMOVED
    seller_username VARCHAR(50) NULL,
    itemName VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    listingType ENUM('SELL', 'LEND', 'GIVEAWAY') NOT NULL,

    listingStatus ENUM(
    'PENDING_REVIEW', 'LIVE', 'REJECTED', 'RESERVED',
    'SOLD', 'LENT', 'GIVEN', 'REMOVED'
    ) NOT NULL DEFAULT 'PENDING_REVIEW',
    price DECIMAL(10,2),
    description TEXT NOT NULL,
    item_condition ENUM ( 'USED', 'LIKE_NEW', 'FOR_PARTS', 'NEW') NOT NULL,
    lending_duration INT,
    lending_unit ENUM('DAYS', 'WEEKS', 'MONTHS'),
    meetup_location VARCHAR(100) NOT NULL,
    posted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reviewed_at DATETIME(6),
    rejection_reason TEXT,
    view_count INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 1,

    CONSTRAINT chk_listing_id_not_blank
    CHECK (CHAR_LENGTH(TRIM(id)) > 0),
    CONSTRAINT fk_listings_seller
    FOREIGN KEY (seller_username) REFERENCES users(username)
        ON UPDATE CASCADE
        ON DELETE SET NULL,
    CONSTRAINT chk_itemname_not_blank
    CHECK (CHAR_LENGTH(TRIM(itemName)) > 0),
    CONSTRAINT chk_category_not_blank
    CHECK (CHAR_LENGTH(TRIM(category)) > 0),
    CONSTRAINT chk_description_not_blank
    CHECK (CHAR_LENGTH(TRIM(description)) > 0),
    CONSTRAINT chk_meetup_location_not_blank
    CHECK (CHAR_LENGTH(TRIM(meetup_location)) > 0),
    CONSTRAINT chk_view_count_nonnegative
    CHECK (view_count >= 0),
    CONSTRAINT chk_rejection_reason
    CHECK (
        listingStatus <> 'REJECTED'
        OR (rejection_reason IS NOT NULL AND CHAR_LENGTH(TRIM(rejection_reason)) > 0)
    ),
    CONSTRAINT chk_sell_price
    CHECK (listingType <> 'SELL' OR (price IS NOT NULL AND price >= 0)),
    CONSTRAINT chk_lend_duration
    CHECK (
        listingType <> 'LEND'
        OR (
            lending_duration IS NOT NULL
            AND lending_duration > 0
            AND lending_unit IS NOT NULL
        )
    )
);

-- A listing is referenced by its listing photos, use one row for each photo
CREATE TABLE IF NOT EXISTS listing_photos (
    listing_id VARCHAR(50) NOT NULL,
    photo_position TINYINT NOT NULL,
    photo_data MEDIUMBLOB NOT NULL,

    PRIMARY KEY (listing_id, photo_position),
    CONSTRAINT fk_listing_photos_listing
    FOREIGN KEY (listing_id)
        REFERENCES listings(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT chk_photo_position
    CHECK (photo_position BETWEEN 1 AND 6),
    CONSTRAINT chk_photo_data_not_empty
    CHECK (OCTET_LENGTH(photo_data) > 0)
);

-- Buyer reports retain the listing title and seller if a listing is later deleted.
-- Nullable references keep existing account/listing deletion behaviour unchanged.
CREATE TABLE IF NOT EXISTS listing_reports (
    id VARCHAR(50) PRIMARY KEY,
    listing_id VARCHAR(50),
    listing_title VARCHAR(255) NOT NULL,
    seller_username VARCHAR(50) NOT NULL,
    buyer_username VARCHAR(50),
    reason VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_listing_reports_listing (listing_id),
    CONSTRAINT fk_listing_reports_listing FOREIGN KEY (listing_id)
        REFERENCES listings(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_listing_reports_buyer FOREIGN KEY (buyer_username)
        REFERENCES users(username) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_listing_report_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 2000)
);

CREATE TABLE IF NOT EXISTS notifications(
    id VARCHAR(50) PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    category VARCHAR(50) NOT NULL,
    title VARCHAR(50) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    timestamp DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    isRead BOOLEAN NOT NULL DEFAULT TRUE,

    INDEX idx_notifications_recipient (username, timestamp),
    INDEX idx_notifications_unread (username, isRead),

    CONSTRAINT fk_notifications_user FOREIGN KEY (username)
        REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_notification_category_not_blank
        CHECK (CHAR_LENGTH(TRIM(category)) > 0),
    CONSTRAINT chk_notification_title_not_blank
        CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_notification_message_not_blank
        CHECK (CHAR_LENGTH(TRIM(message)) > 0)
);

CREATE TABLE IF NOT EXISTS deals(

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
