package pl.sknikod.streamscout

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import akka.util.Timeout
import pl.sknikod.streamscout.TwitchApiActor.{Command, GetChannelId, Start, TokenUpdated}
import pl.sknikod.streamscout.token.{TwitchToken, TwitchTokenActor}
import sttp.client3.circe.asJson
import sttp.client3.{HttpClientSyncBackend, HttpURLConnectionBackend, Identity, Response, ResponseException, SttpBackend, UriContext, basicRequest}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import io.circe.generic.auto.*
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend

class TwitchApiActor(clientId: String, sharding: ClusterSharding, context: ActorContext[TwitchApiActor.Command])(implicit system: ActorSystem[_]) {

  private case class State(token: Option[TwitchToken])

  implicit val ec: ExecutionContext = system.executionContext

  private val tokenActor: EntityRef[TwitchTokenActor.Command] =
    sharding.entityRefFor(TwitchTokenActor.TypeKey, clientId)

  tokenActor ! TwitchTokenActor.Subscribe(
    context.messageAdapter(response => TwitchApiActor.TokenUpdated(response.token))
  )

  private def getTokenFromActor: Future[TwitchToken] = {
    import concurrent.duration.DurationInt
    implicit val timeout: Timeout = Timeout(5.seconds)
    tokenActor.ask[TwitchTokenActor.TokenResponse](replyTo => TwitchTokenActor.GetToken(replyTo)).map { response =>
      response.token
    }
  }

  private def getChannelId(username: String, replyTo: ActorRef[Option[String]]): Future[Unit] =
    case class TwitchUserResponse(data: List[TwitchUser])
    case class TwitchUser(id: String, login: String)

    val request = basicRequest
      .get(uri"https://api.twitch.tv/helix/users?login=$username")
      .header("Authorization", s"Bearer ${state.token.get.accessToken}")
      .header("Client-Id", clientId)
      .response(asJson[TwitchUserResponse])

    implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

    val responseFuture: Future[Response[Either[ResponseException[String, io.circe.Error], TwitchUserResponse]]] =
      request.send(backend)

    responseFuture.map { response =>
      response.body match {
        case Right(twitchUserResponse) =>
          twitchUserResponse.data.headOption.map(_.id) match {
            case Some(channelId) => replyTo ! Some(channelId)
            case None => replyTo ! None
          }
        case Left(_) =>
          replyTo ! None
      }
    }.recover {
      case _ => replyTo ! None
    }

  private var state = State(None)

  def behavior(): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Start() =>
        getTokenFromActor.onComplete {
          case Success(token) =>
            state = state.copy(token = Some(token))
          case Failure(exception) =>
            println(s"Failed to get token from TwitchTokenActor: ${exception.getMessage}")
        }
        Behaviors.same

      case TokenUpdated(token) =>
        state = state.copy(token = Some(token))
        Behaviors.same

      case GetChannelId(username, replyTo) =>
        getChannelId(username, replyTo)
        Behaviors.same
    }
  }
}


object TwitchApiActor {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("TwitchApiActor")

  sealed trait Command
  case class Start() extends Command
  case class TokenUpdated(token: TwitchToken) extends Command
  case class GetChannelId(username: String, replyTo: ActorRef[Option[String]]) extends Command

  def apply(clientId: String, sharding: ClusterSharding)(implicit system: ActorSystem[_]): Behavior[Command] = {
    Behaviors.setup { context =>
      new TwitchApiActor(clientId, sharding, context).behavior()
    }
  }
}
