-- Per-service API credentials, entered by the user in the app.
--
-- Separate from service_connection: that table holds the tokens for one signed-in
-- account and is thrown away on disconnect, whereas these are the app registration
-- itself and survive connecting and disconnecting.

CREATE TABLE provider_setting (
    provider           VARCHAR(32)   NOT NULL PRIMARY KEY,
    -- Not a secret. A PKCE client id ships inside every copy of the app by design,
    -- so it is stored in the clear and can be shown back to the user for editing.
    client_id          VARCHAR(512)  NOT NULL,
    -- Ciphertext (AES-256-GCM), or null for services that need no secret.
    -- Google issues one for "Desktop app" clients and expects it in the token
    -- exchange; Spotify's PKCE flow has none. It is not a true secret either, since
    -- it also ships with the app, but it is not something to leave lying in a plain
    -- file next to the database.
    client_secret_enc  VARCHAR(4096),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
