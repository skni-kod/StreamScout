USE streamscout;
CREATE TABLE IF NOT EXISTS twitch_tokens (
    client_id TEXT PRIMARY KEY,
    client_secret TEXT,
    access_token TEXT,
    refresh_token TEXT,
    expires_at TIMESTAMP
);