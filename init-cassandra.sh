#!/bin/bash
# init.sh

until cqlsh -e "describe keyspaces"; do
    echo "Waiting for Cassandra to be ready..."
    sleep 5
done

echo "Creating keyspace..."
cqlsh -e "CREATE KEYSPACE IF NOT EXISTS streamscout WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"

echo "Initializing Cassandra..."
cqlsh -f /src/main/scala/infrastructure/docker/ddl-scripts/1-create_tables.cql
cqlsh -f /src/main/scala/infrastructure/docker/ddl-scripts/2-create_tokens_table.cql
cqlsh -f /src/main/scala/infrastructure/docker/ddl-scripts/3-create_user_tables.cql
echo "Cassandra initialization completed."