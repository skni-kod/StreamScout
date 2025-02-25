package pl.sknikod.streamscout
package infrastructure.kafka

import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.{Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}

import java.time.LocalDateTime
import java.util.{Collections, Properties}
import scala.concurrent.{ExecutionContext, Future}

case class Message(channel: String, user: String, content: String, date: LocalDateTime, clientId: String)

class MessageSerializer extends Serializer[Message] {
  override def serialize(topic: String, data: Message): Array[Byte] =
    Option(data).map(_.asJson.noSpaces.getBytes).orNull
}

class MessageDeserializer extends Deserializer[Message] {
  override def deserialize(topic: String, data: Array[Byte]): Message = {
    val jsonStr = Option(data).map(new String(_)).getOrElse("")
    parse(jsonStr).flatMap(_.as[Message]).getOrElse(
      throw new IllegalArgumentException(s"Invalid message format in topic $topic: $jsonStr")
    )
  }
}

case class KafkaProducerConfig()(implicit system: ActorSystem[_], materializer: Materializer) {
  private val bootstrapServers = "localhost:9093"

  private val producerSettings: ProducerSettings[String, Message] =
    ProducerSettings(system, new StringSerializer, new MessageSerializer)
      .withBootstrapServers(bootstrapServers)
  
  private val producer: Producer[String, Message] = producerSettings.createKafkaProducer()
  
  def sendMessage(topic: String, key: String, value: Message)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val record = new ProducerRecord[String, Message](topic, key, value)

    Future {
      producer.send(record).get()
    }
  }
  
}

case class KafkaConsumerConfig()(implicit system: ActorSystem[_], materializer: Materializer) {
  private val bootstrapServers = "localhost:9093"
  
  private def consumerSettings(groupId: String): ConsumerSettings[String, Message] =
    ConsumerSettings(system, new StringDeserializer, new MessageDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty("auto.offset.reset", "latest")

  def consumeMessages(topic: String, groupId: String): Source[ConsumerRecord[String, Message], Control] = {
    Consumer
      .plainSource(consumerSettings(groupId), Subscriptions.topics(topic))
  }
  
}


case class KafkaConfig() {
  
  private def adminConfig(): Properties = {
    val adminProps = new Properties()
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9093")
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

