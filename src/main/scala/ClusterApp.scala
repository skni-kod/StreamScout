package pl.sknikod.streamscout

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.*
import akka.stream.Materializer
import io.github.cdimascio.dotenv.Dotenv

import scala.concurrent.{ExecutionContext, Future}

object ApiConfig {
  private val dotenv: Dotenv = Dotenv.load()

  val botToken: String = dotenv.get("BOT_TOKEN")
  val apiKey: String = dotenv.get("API_KEY")
}

object TwitchClusterApp extends App {

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "StreamScoutSystem")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContext = system.executionContext

  private val TypeKey: EntityTypeKey[ChannelActor.Command] = EntityTypeKey[ChannelActor.Command]("ChannelActor")
  private val sharding = ClusterSharding(system)

  sharding.init(Entity(TypeKey) { entityContext =>
    println(s"Initializing entity for ${entityContext.entityId}")
    ChannelActor(entityContext.entityId)
  })

  val route =
    path("test") {
      get {
        parameter("channelId") { channelId =>
          val entityRef = sharding.entityRefFor(TypeKey, channelId)

          entityRef ! ChannelActor.ProcessEvent(s"Event for channel $channelId")
          complete(s"Event received for channel: $channelId")
        }
      }
    }

  Channels.fromJsonFile("channels.json")
    .fold(error => println(s"Error loading channels: $error"),
          channels => IrcBot(channels=channels).start())

  val bindingFuture: Future[Http.ServerBinding] = Http().newServerAt("0.0.0.0", 8080).bind(route)
}

object ChannelActor {

  sealed trait Command
  case class ProcessEvent(body: String) extends Command

  def apply(channelId: String): Behavior[Command] = Behaviors.receiveMessage {
    case ProcessEvent(body) =>
      println(s"Received event for channel $channelId: $body")
      Behaviors.same
  }
}
