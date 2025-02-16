USE streamscout;
CREATE TABLE last_user_messages (
    channel TEXT,
    username TEXT,
    content TEXT,
    date TIMESTAMP,
    client_id TEXT,
    PRIMARY KEY (channel, username)
);