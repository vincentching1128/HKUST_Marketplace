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