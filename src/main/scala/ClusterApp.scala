package pl.sknikod.streamscout

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import akka.kafka.scaladsl.Consumer
import akka.stream.Materializer
import io.github.cdimascio.dotenv.Dotenv
import org.apache.kafka.clients.producer.KafkaProducer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{Offset, PersistenceQuery}
import akka.projection.{ProjectionBehavior, ProjectionId}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.stream.scaladsl.Source
import akka.util.Timeout
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import pl.sknikod.streamscout.infrastructure.kafka.{KafkaConfig, KafkaConsumerConfig, KafkaProducerConfig}
import pl.sknikod.streamscout.projections.{LastMessageProjection, ProjectionFactory, RecommendedStreamersProjection, TestProjection}
import pl.sknikod.streamscout.token.{TwitchToken, TwitchTokenActor, TwitchTokenDAO}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object ApiConfig {
  private val dotenv: Dotenv = Dotenv.load()

  val botToken: String = dotenv.get("BOT_TOKEN")
  val apiKey: String = dotenv.get("API_KEY")
}

object TwitchClusterApp extends App {

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "StreamScoutSystem")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContext = system.executionContext

  //private val TypeKey: EntityTypeKey[ChannelActorTEST.Command] = EntityTypeKey[ChannelActorTEST.Command]("ChannelActor")
  private val TypeKey: EntityTypeKey[ChannelActor.Command] = EntityTypeKey[ChannelActor.Command]("ChannelActor")
  private val sharding = ClusterSharding(system)

  val session = CassandraSessionRegistry(system).sessionFor(
    "akka.persistence.cassandra"
  )

  sharding.init(Entity(TypeKey) { entityContext =>
    println(s"Initializing entity for ${entityContext.entityId}")
    //ChannelActorTEST(entityContext.entityId)
    ChannelActor(entityContext.entityId, sharding, session)
  })

  sharding.init(
    Entity(ChannelWriteActor.TypeKey) { entityContext =>
      val clientId = entityContext.entityId
      ChannelWriteActor(clientId, sharding)
    }
  )
  
  sharding.init(Entity(TwitchApiActor.TypeKey) { entityContext =>
    TwitchApiActor(entityContext.entityId, sharding)
  })

  // READ JOURNAL
  /*
  val readJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  val eventsForChat = readJournal
    .eventsByTag("chat-tag", Offset.noOffset)
    .map(_.event)
    .runForeach(e => println(s"Real journal: $e"))
   */

  // CONNECTION CHECK
  session.executeWrite("SELECT release_version FROM system.local").onComplete {
    case Success(value) => println("Cassandra connection OK")
    case Failure(ex) => println(s"Error when connecting to Cassandra: ${ex.getMessage}")
  }

  val flowManager = FlowManager(sharding, session)
  flowManager.initializeSharding()
  flowManager.initializeFlow()


  // PROJECTIONS
  val projectionFactory = new ProjectionFactory(system, session)
  val testProjection = projectionFactory.createProjection(
    ProjectionId("messages-test-projection", "chat"), "chat-tag", () => new TestProjection(session))
  val lastMessageProjection = projectionFactory.createProjection(
    ProjectionId("last-message-projection", "lm-chat"), "chat-tag", () => new LastMessageProjection(session)
  )
  val recommendedStreamersProjection = projectionFactory.createProjection(
    ProjectionId("recommended-streamers-projection", "rs-chat"), "chat-tag", () => RecommendedStreamersProjection(session)
  )

  val projectionActor = system.systemActorOf(
    ProjectionBehavior(testProjection),
    name = "test-projection"
  )
  val lastMessageProjectionActor = system.systemActorOf(
    ProjectionBehavior(lastMessageProjection),
    name = "last-message"
  )
  val recommendedStreamersActor = system.systemActorOf(
    ProjectionBehavior(recommendedStreamersProjection),
    name = "recommended-streamers"
  )

  val route =
    path("test") {
      get {
        parameter("channelId") { channelId =>
          val entityRef = sharding.entityRefFor(TypeKey, channelId)

          //entityRef ! ChannelActorTEST.ProcessEvent(s"Event for channel $channelId")
          complete(s"Event received for channel: $channelId")
        }
      }
    }

  // KAFKA
  private val kafkaConfig = KafkaConfig()
  kafkaConfig.createTopic("messages", numPartitions = 50, replicationFactor = 1.shortValue)

  private val kafkaConsumerConfig = KafkaConsumerConfig()
  kafkaConsumerConfig.consumeMessages("messages", "messages-group")
    .runForeach { record =>
      val channel = record.key()
      val message = record.value()

      val entityRef = sharding.entityRefFor(TypeKey, channel)
      //entityRef ! ChannelActorTEST.ProcessEvent(s"[Kafka Event for channel $channel : $message]")
      //entityRef ! ChannelActor.NewMessage(message)

      import concurrent. duration. DurationInt
      implicit val timeout: Timeout = 3.seconds
      val writeActorRef: EntityRef[ChannelWriteActor.Command] = sharding.entityRefFor(ChannelWriteActor.TypeKey, message.clientId)
      val response = entityRef.ask[Boolean](ref => ChannelActor.NewMessage(message, ref, writeActorRef))

      response.onComplete {
        case Success(res) => println(s"Message for channel $channel  has been handled correctly: $res")
        case Failure(ex) => println(s"Error processing messages for $channel: ${ex.getMessage}")
      }
  }

  val bindingFuture: Future[Http.ServerBinding] = Http().newServerAt("0.0.0.0", 8080).bind(route)
}

object ChannelActorTEST {

  sealed trait Command
  case class ProcessEvent(body: String) extends Command

  def apply(channelId: String): Behavior[Command] = Behaviors.receiveMessage {
    case ProcessEvent(body) =>
      println(s"Received event for channel $channelId: $body")
      Behaviors.same
  }
}
