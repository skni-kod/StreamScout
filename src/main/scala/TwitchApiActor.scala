package pl.sknikod.streamscout

import TwitchApiActor.*
import token.{TwitchToken, TwitchTokenActor}

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import akka.util.Timeout
import io.circe.generic.auto.*
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.client3.circe.asJson
import sttp.client3.{Response, ResponseException, SttpBackend, UriContext, basicRequest}

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


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

  private def getUptime(username: String, replyTo: ActorRef[Option[Duration]]): Future[Unit] = {
    case class TwitchStreamData(id: String, user_id: String, user_login: String, started_at: String)
    case class TwitchStreamResponse(data: List[TwitchStreamData])

    val request = basicRequest
      .get(uri"https://api.twitch.tv/helix/streams?user_login=$username")
      .header("Authorization", s"Bearer ${state.token.get.accessToken}")
      .header("Client-Id", clientId)
      .response(asJson[TwitchStreamResponse])

    implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

    val responseFuture: Future[Response[Either[ResponseException[String, io.circe.Error], TwitchStreamResponse]]] =
      request.send(backend)

    responseFuture.map { response =>
      response.body match {
        case Right(streamResponse) =>
          streamResponse.data.headOption match {
            case Some(streamData) =>
              val startedAt = Instant.parse(streamData.started_at)
              val now = Instant.now()
              val duration = Duration.between(startedAt, now)
              replyTo ! Some(duration)

            case None => replyTo ! None
          }
        case Left(_) => replyTo ! None
      }
    }.recover {
      case _ => replyTo ! None
    }
  }

  private def getViewersCount(username: String, replyTo: ActorRef[Option[Int]]): Future[Unit] = {
    case class TwitchStreamData(id: String, user_id: String, user_login: String, viewer_count: Int)
    case class TwitchStreamResponse(data: List[TwitchStreamData])

    val request = basicRequest
      .get(uri"https://api.twitch.tv/helix/streams?user_login=$username")
      .header("Authorization", s"Bearer ${state.token.get.accessToken}")
      .header("Client-Id", clientId)
      .response(asJson[TwitchStreamResponse])

    implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

    val responseFuture: Future[Response[Either[ResponseException[String, io.circe.Error], TwitchStreamResponse]]] =
      request.send(backend)

    responseFuture.map { response =>
      response.body match {
        case Right(streamResponse) =>
          streamResponse.data.headOption match {
            case Some(streamData) =>
              replyTo ! Some(streamData.viewer_count)

            case None => replyTo ! None
          }
        case Left(_) => replyTo ! None
      }
    }.recover {
      case _ => replyTo ! None
    }
  }

  private def getTop10PLStreams(replyTo: ActorRef[Map[String, Int]]): Future[Unit] = {
    case class TwitchStreamData(user_login: String, viewer_count: Int)
    case class TwitchStreamResponse(data: List[TwitchStreamData])

    val request = basicRequest
      .get(uri"https://api.twitch.tv/helix/streams?language=pl&first=10")
      .header("Authorization", s"Bearer ${state.token.get.accessToken}")
      .header("Client-Id", clientId)
      .response(asJson[TwitchStreamResponse])

    implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

    val responseFuture: Future[Response[Either[ResponseException[String, io.circe.Error], TwitchStreamResponse]]] =
      request.send(backend)

    responseFuture.map { response =>
      response.body match {
        case Right(streamResponse) =>
          val topStreams = streamResponse.data.map(stream => stream.user_login -> stream.viewer_count).toMap
          replyTo ! topStreams

        case Left(_) =>
          replyTo ! Map.empty
      }
    }.recover {
      case _ => replyTo ! Map.empty
    }
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

      case GetUptime(username, replyTo) =>
        getUptime(username, replyTo)
        Behaviors.same

      case GetViewersCount(username, replyTo) =>
        getViewersCount(username, replyTo)
        Behaviors.same

      case GetTop10PL(replyTo) =>
        getTop10PLStreams(replyTo)
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
  case class GetUptime(username: String, replyTo: ActorRef[Option[Duration]]) extends Command
  case class GetViewersCount(username: String, replyTo: ActorRef[Option[Int]]) extends Command
  case class GetTop10PL(replyTo: ActorRef[Map[String, Int]]) extends Command

  def apply(clientId: String, sharding: ClusterSharding)(implicit system: ActorSystem[_]): Behavior[Command] = {
    Behaviors.setup { context =>
      new TwitchApiActor(clientId, sharding, context).behavior()
    }
  }
}
