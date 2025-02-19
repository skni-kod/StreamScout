#!/bin/bash

source /app/.env

echo "Waiting for Cassandra to be ready..."
until cqlsh "$CASSANDRA_HOST" -e "describe keyspaces"; do
  sleep 5
done

echo "Initializing keyspace and tables..."
cqlsh "$CASSANDRA_HOST" -f /scripts/1-create_tables.cql
cqlsh "$CASSANDRA_HOST" -f /scripts/2-create_tokens_table.cql
cqlsh "$CASSANDRA_HOST" -f /scripts/3-create_user_tables.cql

echo "Inserting Twitch tokens..."
cqlsh "$CASSANDRA_HOST" -e "
USE streamscout;

INSERT INTO twitch_tokens (client_id, client_secret, access_token, refresh_token, expires_at)
VALUES ('$TWITCH_CLIENT_ID', '$TWITCH_CLIENT_SECRET', '$TWITCH_ACCESS_TOKEN', '$TWITCH_REFRESH_TOKEN', '$TWITCH_EXPIRES_AT')
IF NOT EXISTS;
"

echo "Cassandra initialization complete."