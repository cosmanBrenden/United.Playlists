-- Initial schema.
--
-- Playlists are local. There is deliberately no column linking an entry back to a
-- remote playlist for write-back purposes: origin is recorded for provenance only.

CREATE TABLE playlist (
    id                 UUID         NOT NULL PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    description        VARCHAR(2048),
    origin_provider    VARCHAR(32),
    origin_playlist_id VARCHAR(255),
    imported_at        TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);

-- Import checks this on every playlist it considers, to skip ones already copied.
CREATE INDEX idx_playlist_origin ON playlist (origin_provider, origin_playlist_id);

-- The list view orders by this.
CREATE INDEX idx_playlist_updated_at ON playlist (updated_at DESC);

CREATE TABLE playlist_entry (
    id                UUID         NOT NULL PRIMARY KEY,
    playlist_id       UUID         NOT NULL,
    position          INT          NOT NULL,
    provider          VARCHAR(32)  NOT NULL,
    provider_track_id VARCHAR(255) NOT NULL,
    title             VARCHAR(512) NOT NULL,
    -- Artists are stored joined by U+001F rather than in a child table: they are
    -- always read and written with their track and are never queried on their own,
    -- so a table would add a join for no benefit.
    artists           VARCHAR(1024),
    album             VARCHAR(512),
    duration_ms       BIGINT,
    artwork_url       VARCHAR(1024),
    added_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_playlist_entry_playlist
        FOREIGN KEY (playlist_id) REFERENCES playlist (id) ON DELETE CASCADE
);

-- Every playlist read fetches entries by playlist in position order.
CREATE INDEX idx_playlist_entry_playlist_position ON playlist_entry (playlist_id, position);

CREATE TABLE service_connection (
    provider                 VARCHAR(32)   NOT NULL PRIMARY KEY,
    -- Ciphertext (AES-256-GCM), not tokens. Sized for base64 of an IV + a long
    -- token + tag, with room for services that issue large JWTs.
    access_token_enc         VARCHAR(4096) NOT NULL,
    refresh_token_enc        VARCHAR(4096),
    access_token_expires_at  TIMESTAMP WITH TIME ZONE,
    scopes                   VARCHAR(1024),
    account_label            VARCHAR(255),
    market                   VARCHAR(8),
    connected_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    last_refreshed_at        TIMESTAMP WITH TIME ZONE
);
