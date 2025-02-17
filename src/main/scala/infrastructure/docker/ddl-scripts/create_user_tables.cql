USE streamscout;
CREATE TABLE IF NOT EXISTS last_user_messages (
    channel TEXT,
    username TEXT,
    content TEXT,
    date TIMESTAMP,
    client_id TEXT,
    PRIMARY KEY (channel, username)
);

CREATE TABLE IF NOT EXISTS user_watchtime (
    username TEXT PRIMARY KEY,
    watchtime MAP<TEXT, INT>
);