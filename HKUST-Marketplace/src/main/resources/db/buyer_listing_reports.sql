-- Run after schema.sql, once per database. Re-running leaves existing reports intact.
USE hkust_marketplace;

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

-- Title/seller snapshots preserve context if a listing is later deleted.
-- SET NULL leaves existing seller listing deletion behaviour unchanged.
-- Admin report review is a separate workflow; this migration only stores buyer reports.
