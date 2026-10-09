CREATE TABLE country_infos (
    id BIGINT NOT NULL AUTO_INCREMENT,
    iso_code CHAR(2) NOT NULL,
    name VARCHAR(255) NOT NULL,
    capital_city VARCHAR(255) NOT NULL DEFAULT '',
    phone_code VARCHAR(32) NOT NULL DEFAULT '',
    continent_code CHAR(2) NOT NULL,
    currency_iso_code CHAR(3) NOT NULL,
    country_flag VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_country_infos_iso_code (iso_code),
    KEY idx_country_infos_deleted_at (deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE languages (
    country_id BIGINT NOT NULL,
    iso_code VARCHAR(10) NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (country_id, iso_code),
    CONSTRAINT fk_languages_country
        FOREIGN KEY (country_id) REFERENCES country_infos (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE sync_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(255) NOT NULL,
    country_iso_code CHAR(2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    message TEXT NULL,
    synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sync_logs_country_synced_at (country_iso_code, synced_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
