#!/bin/bash

KAFKA_BROKER="kafka:9092"
TOPIC_NAME="messages"

echo "Creating topic: $TOPIC_NAME"
kafka-topics.sh --create --topic $TOPIC_NAME --bootstrap-server $KAFKA_BROKER --partitions 50 --replication-factor 1

echo "Topic created successfully"
