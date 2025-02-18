#!/bin/bash

echo "Waiting for Cassandra to be ready..."
until cqlsh cassandra-service -e "describe keyspaces"; do
  sleep 5
done

echo "Initializing keyspace and tables..."
cqlsh cassandra-service -f /scripts/1-create_tables.cql
cqlsh cassandra-service -f /scripts/2-create_tokens_table.cql
cqlsh cassandra-service -f /scripts/3-create_user_tables.cql
