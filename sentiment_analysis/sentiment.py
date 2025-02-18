from confluent_kafka import Consumer, KafkaError
from datetime import datetime
from dateutil.parser import parse
from transformers import pipeline
from cassandra.cluster import Cluster
from cassandra.query import SimpleStatement
import json
import time
import os

def connect_cassandra():
    cluster = Cluster(
        [os.getenv('CASSANDRA_HOST', 'cassandra-service')],
        port=int(os.getenv('CASSANDRA_PORT', 9042))
    )
    
    session = cluster.connect()
    
    session.execute("""
        CREATE KEYSPACE IF NOT EXISTS streamscout
        WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
    """)

    session.execute("""
        CREATE TABLE IF NOT EXISTS streamscout.sentiment_analysis (
            channel text,
            clientId text,
            date timestamp,
            user text,
            content text,
            sentiment float,
            PRIMARY KEY ((channel, user), date)
        ) WITH CLUSTERING ORDER BY (date DESC);
    """)

    session.execute("CREATE INDEX IF NOT EXISTS idx_user ON streamscout.sentiment_analysis (user);")
    session.execute("CREATE INDEX IF NOT EXISTS idx_channel ON streamscout.sentiment_analysis (channel);")

    return cluster, session

def get_sentiment(text):
    result = sentiment_analyzer(text)[0]
    stars = int(result['label'].split()[0])
    return (stars - 1) / 4

sentiment_analyzer = pipeline(
    "sentiment-analysis",
    model="nlptown/bert-base-multilingual-uncased-sentiment"
)

cluster, session = connect_cassandra()

conf = {
    'bootstrap.servers': os.getenv('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092'),
    'group.id': 'sentiment-analysis-group',
    'auto.offset.reset': 'earliest',
    'enable.auto.commit': 'false'
}

consumer = Consumer(conf)
consumer.subscribe(['messages'])

def process_message(msg):
    try:
        data = json.loads(msg.value())
        
        message = {
            "channel": data['channel'],
            "user": data['user'],
            "content": data['content'],
            "date": parse(data['date']),
            "clientId": data['clientId']
        }
        
        sentiment = get_sentiment(message['content'])
        
        insert = SimpleStatement("""
            INSERT INTO streamscout.sentiment_analysis 
            (channel, clientId, date, user, content, sentiment)
            VALUES (%(channel)s, %(clientId)s, %(date)s, %(user)s, %(content)s, %(sentiment)s)
        """)
        
        session.execute(insert, {
            'channel': message['channel'],
            'clientId': message['clientId'],
            'date': message['date'],
            'user': message['user'],
            'content': message['content'],
            'sentiment': sentiment
        })
        
        print(f"Saved sentiment {sentiment:.2f} for message from {message['user']}")

    except Exception as e:
        print(f"Processing error: {str(e)}")

def wait_for_dependencies():
    ready = False
    while not ready:
        try:
            test_consumer = Consumer({'bootstrap.servers': conf['bootstrap.servers'], 'group.id': 'test'})
            test_consumer.list_topics()
            test_consumer.close()
            cluster.connect()
            ready = True
        except:
            print("Waiting for dependencies...")
            time.sleep(5)

wait_for_dependencies()

try:
    while True:
        msg = consumer.poll(1.0)
        if msg and not msg.error():
            process_message(msg)
except KeyboardInterrupt:
    pass
finally:
    consumer.close()
    cluster.shutdown()