package pl.sknikod.streamscout
package infrastructure.kafka

import io.circe.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import io.circe.syntax.*
import io.circe.generic.auto.*
import io.circe.parser.*
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}

import java.util.{Collections, Properties}
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.serialization.StringDeserializer

import java.time.LocalDateTime
import java.util.Properties

case class Message(channel: String, user: String, content: String, date: LocalDateTime)

class MessageSerializer extends Serializer[Message] {
  override def serialize(topic: String, data: Message): Array[Byte] = {
    if (data == null) {
      null
    } else {
      data.asJson.noSpaces.getBytes
    }
  }
}

class MessageDeserializer extends Deserializer[Message] {
  override def deserialize(topic: String, data: Array[Byte]): Message = {
    if (data == null) {
      throw new IllegalArgumentException("Data cannot be null")
    }

    val jsonStr = new String(data)
    parse(jsonStr) match {
      case Right(json) =>
        json.as[Message] match {
          case Right(message) => message
          case Left(_) =>
            throw new RuntimeException(s"Invalid message format in topic $topic: $jsonStr")
        }
      case Left(error) =>
        throw new RuntimeException(s"Invalid JSON in topic $topic: $error")
    }
  }
}


case class KafkaConfig() {

  def producerConfig(): KafkaProducer[String, Message] =
    val kafkaProps = new Properties()
    kafkaProps.put("bootstrap.servers", "localhost:9092")
    kafkaProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    kafkaProps.put("value.serializer", classOf[MessageSerializer].getName)
    new KafkaProducer[String, Message](kafkaProps)

  def consumerConfig(groupId: String): KafkaConsumer[String, Message] =
    val kafkaProps = new Properties()
    kafkaProps.put("bootstrap.servers", "localhost:9092")
    kafkaProps.put("key.deserializer", classOf[StringDeserializer].getName)
    kafkaProps.put("value.deserializer", classOf[MessageDeserializer].getName)
    kafkaProps.put("group.id", groupId)
    kafkaProps.put("enable.auto.commit", "true")
    kafkaProps.put("auto.offset.reset", "latest")
    new KafkaConsumer[String, Message](kafkaProps)

  private def adminConfig(): Properties = {
    val adminProps = new Properties()
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    adminProps
  }

  def createTopic(name: String, numPartitions: Int, replicationFactor: Short): Unit = {
    val adminClient = AdminClient.create(adminConfig())

    val topic = new NewTopic(name, numPartitions, replicationFactor)
    try {
      adminClient.createTopics(Collections.singletonList(topic)).all().get()
      println(s"Topic $name created")
    } catch {
      case e: Exception =>
        println(s"Error when creating topic: ${e.getMessage}")
    } finally {
      adminClient.close()
    }
  }

}

