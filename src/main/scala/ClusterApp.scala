package pl.sknikod.streamscout

import infrastructure.kafka.{KafkaConfig, KafkaConsumerConfig}
import projections.{LastMessageProjection, ProjectionFactory, RecommendedStreamersProjection}

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import akka.projection.{ProjectionBehavior, ProjectionId}
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object TwitchClusterApp extends App {

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "StreamScoutSystem")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContext = system.executionContext

  private val TypeKey: EntityTypeKey[ChannelActor.Command] = EntityTypeKey[ChannelActor.Command]("ChannelActor")
  private val sharding = ClusterSharding(system)

  val session = CassandraSessionRegistry(system).sessionFor(
    "akka.persistence.cassandra"
  )

  sharding.init(Entity(TypeKey) { entityContext =>
    println(s"Initializing entity for ${entityContext.entityId}")
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
  val lastMessageProjection = projectionFactory.createProjection(
    ProjectionId("last-message-projection", "lm-chat"), "chat-tag", () => new LastMessageProjection(session)
  )
  val recommendedStreamersProjection = projectionFactory.createProjection(
    ProjectionId("recommended-streamers-projection", "rs-chat"), "chat-tag", () => RecommendedStreamersProjection(session)
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
          complete(s"Event received for channel: $channelId")
        }
      }
    }

  // KAFKA
  private val kafkaConfig = KafkaConfig()
  //kafkaConfig.createTopic("messages", numPartitions = 50, replicationFactor = 1.shortValue)

  private val kafkaConsumerConfig = KafkaConsumerConfig()
  kafkaConsumerConfig.consumeMessages("messages", "messages-group")
    .runForeach { record =>
      val channel = record.key()
      val message = record.value()

      val entityRef = sharding.entityRefFor(TypeKey, channel)

      import concurrent.duration.DurationInt
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
