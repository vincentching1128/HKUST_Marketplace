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
    lending_unit ENUM('DAYS', 'WEEKS', 'MONTHS', 'SECONDS'),
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
    message TEXT NOT NULL,
    timestamp DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    isRead BOOLEAN NOT NULL DEFAULT FALSE,

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
    id VARCHAR(50) PRIMARY KEY,
    -- NULL after that participant's account is permanently deleted; the deal is preserved
    buyer_username VARCHAR(50) NULL,
    seller_username VARCHAR(50) NULL,
    listing_id VARCHAR(50) NOT NULL,
    dealType ENUM('BOUGHT','BORROWED','RECEIVED') NOT NULL,
    status ENUM('PENDING','COUNTERED','ACCEPTED','DECLINED','CANCELLED','COMPLETED','RETURNED') NOT NULL DEFAULT 'PENDING',
    offeredPrice DECIMAL(10,2) NULL,
    counterOfferPrice DECIMAL(10,2) NULL,
    borrowStartDate DATETIME(6) NULL,
    borrowDueDate DATETIME(6) NULL,
    counterBorrowStart DATETIME(6) NULL,
    counterDueEnd DATETIME(6) NULL,
    returnedDate DATETIME(6) NULL,
    createdDate DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completedDate DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 1,
    -- 0: both accounts exist, 1: one participant deleted, 2: both deleted (hidden by default)
    deleted SMALLINT NOT NULL DEFAULT 0,

    INDEX idx_deals_buyer_status (buyer_username, status),
    INDEX idx_deals_seller_status (seller_username, status),
    INDEX idx_deals_status_created (status, createdDate),
    INDEX idx_deals_deleted (deleted, createdDate),

    CONSTRAINT chk_deals_deleted CHECK (deleted BETWEEN 0 AND 2),
    CONSTRAINT chk_deals_offered_price_nonnegative
        CHECK (offeredPrice IS NULL OR offeredPrice >= 0),
    CONSTRAINT chk_deals_counter_price_nonnegative
        CHECK (counterOfferPrice IS NULL OR counterOfferPrice >= 0),
    CONSTRAINT chk_deals_borrow_dates
        CHECK (borrowDueDate IS NULL OR borrowStartDate IS NULL OR borrowDueDate >= borrowStartDate),
    CONSTRAINT chk_deals_counter_borrow_dates
        CHECK (counterDueEnd IS NULL OR counterBorrowStart IS NULL OR counterDueEnd >= counterBorrowStart),
    CONSTRAINT chk_deals_completed_date
        CHECK (status <> 'COMPLETED' OR completedDate IS NOT NULL),

    CONSTRAINT fk_deals_buyer_user FOREIGN KEY (buyer_username)
        REFERENCES users(username) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_deals_seller_user FOREIGN KEY (seller_username)
        REFERENCES users(username) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_deals_item FOREIGN KEY (listing_id)
        REFERENCES listings(id) ON UPDATE CASCADE ON DELETE CASCADE

);

-- A deal supplies the buyer, seller, and listing for its review.
-- The review service must check the buyer's identity and deal eligibility on submission.
CREATE TABLE IF NOT EXISTS reviews (
    id VARCHAR(50) PRIMARY KEY,
    deal_id VARCHAR(50) NOT NULL,
    rating TINYINT UNSIGNED NOT NULL,
    comment TEXT NOT NULL,
    seller_reply TEXT NULL,
    flagged BOOLEAN NOT NULL DEFAULT FALSE,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    UNIQUE KEY uq_reviews_deal (deal_id),
    INDEX idx_reviews_created_at (created_at),
    INDEX idx_reviews_rating_created_at (rating, created_at),

    CONSTRAINT chk_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_reviews_comment CHECK (CHAR_LENGTH(TRIM(comment)) > 0),
    CONSTRAINT chk_reviews_seller_reply
        CHECK (seller_reply IS NULL OR CHAR_LENGTH(TRIM(seller_reply)) > 0),
    CONSTRAINT fk_reviews_deal FOREIGN KEY (deal_id)
        REFERENCES deals(id) ON UPDATE CASCADE ON DELETE CASCADE
);

-- One private conversation per listing and buyer/seller pair. Nullable references
-- preserve history on account/listing deletion without granting a reused username access.
CREATE TABLE IF NOT EXISTS chat_threads (
    id VARCHAR(50) PRIMARY KEY,
    listing_id VARCHAR(50) NULL,
    listing_title VARCHAR(255) NOT NULL,
    listing_type ENUM('SELL','LEND','GIVEAWAY') NOT NULL,
    buyer_username VARCHAR(50) NULL,
    seller_username VARCHAR(50) NULL,
    buyer_name VARCHAR(50) NOT NULL,
    seller_name VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 0: both accounts exist, 1: one participant deleted, 2: both deleted (hidden by default)
    deleted SMALLINT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_chat_parties (listing_id, buyer_username, seller_username),
    INDEX idx_chat_buyer (buyer_username, created_at),
    INDEX idx_chat_seller (seller_username, created_at),
    INDEX idx_chat_deleted (deleted),
    CONSTRAINT chk_chat_threads_deleted CHECK (deleted BETWEEN 0 AND 2),
    CONSTRAINT fk_chat_listing FOREIGN KEY (listing_id)
        REFERENCES listings(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_chat_buyer FOREIGN KEY (buyer_username)
        REFERENCES users(username) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_chat_seller FOREIGN KEY (seller_username)
        REFERENCES users(username) ON UPDATE CASCADE ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(50) NOT NULL,
    sender_role ENUM('BUYER','SELLER') NOT NULL,
    sender_name VARCHAR(50) NOT NULL,
    message_type ENUM('TEXT','PROPOSAL','COUNTER') NOT NULL DEFAULT 'TEXT',
    body TEXT NOT NULL,
    proposal_id VARCHAR(50) NULL,
    proposal_version BIGINT NULL,
    proposal_type ENUM('SELL','LEND','GIVEAWAY') NULL,
    proposal_price DECIMAL(10,2) NULL,
    borrow_start DATETIME(6) NULL,
    borrow_end DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_chat_messages_thread (thread_id, id),
    UNIQUE KEY uq_chat_proposal_version (proposal_id, proposal_version),
    CONSTRAINT fk_chat_message_thread FOREIGN KEY (thread_id)
        REFERENCES chat_threads(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_chat_message_proposal FOREIGN KEY (proposal_id)
        REFERENCES deals(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_chat_message_body CHECK (CHAR_LENGTH(TRIM(body)) BETWEEN 1 AND 4000)
);

-- Buyer/seller identity validation belongs in the backend deal service.
-- Seed the existing admin account for fresh installations.
-- INSERT IGNORE preserves the password and profile of an existing admin.
INSERT IGNORE INTO users
    (username, full_name, password_hash, role,
     residence_hall, bio, preferred_meetup, active)
VALUES
    ('admin',   'HKUST Marketplace Admin', '$argon2id$v=19$m=19456,t=2,p=1$XyO+XgbpU7xAgOOn3KpU3g$HFJl0UFMaE6R1l/kcgU31MwWeYNY361m+r7sToYn0+c', 'ADMIN', NULL, NULL, NULL, TRUE);
