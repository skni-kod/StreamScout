package pl.sknikod.streamscout
package token

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import sttp.client3.circe.asJson
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend, UriContext, basicRequest}
import io.circe.generic.auto.*

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object TwitchTokenActor {

  sealed trait Command
  case object RefreshToken extends Command
  case object Stop extends Command
  case class GetToken(replyTo: ActorRef[TokenResponse]) extends Command
  case class Subscribe(replyTo: ActorRef[TokenUpdated]) extends Command
  case object Startup extends Command

  case class TokenResponse(token: TwitchToken)
  case class TokenUpdated(token: TwitchToken)

  case class RefreshResponse(access_token: String, refresh_token: String, expires_in: Int)

  case class State(token: TwitchToken, subscribers: Set[ActorRef[TokenUpdated]])

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("TwitchToken")

  def apply(initialToken: TwitchToken, dao: TwitchTokenDAO)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        var state = State(initialToken, Set.empty)

        def refresh(): Unit = {
          val request = basicRequest
            .post(uri"https://id.twitch.tv/oauth2/token")
            .body(Map(
              "grant_type" -> "refresh_token",
              "refresh_token" -> state.token.refreshToken,
              "client_id" -> state.token.clientId,
              "client_secret" -> state.token.clientSecret
            ))
            .response(asJson[RefreshResponse])

          implicit val backend: SttpBackend[Identity, Any] = HttpClientSyncBackend()
          request.send(backend).body match {
            case Right(data: RefreshResponse) =>
              val newToken = TwitchToken(
                state.token.clientId,
                state.token.clientSecret,
                data.access_token,
                data.refresh_token,
                Instant.now().plusSeconds(data.expires_in)
              )
              dao.saveToken(newToken)
              state = state.copy(token = newToken)
              state.subscribers.foreach(_ ! TokenUpdated(state.token))
              println(s"Token refreshed for ${state.token.clientId}!")
            case Left(error) =>
              println(s"Error refreshing token for ${state.token.clientId}: $error")
          }
        }

        timers.startTimerWithFixedDelay(RefreshToken, 0.minutes, 60.minutes)

        Behaviors.receiveMessage[Command] {
          case RefreshToken =>
            refresh()
            Behaviors.same
          case GetToken(replyTo: ActorRef[TokenResponse]) =>
            replyTo ! TokenResponse(state.token)
            Behaviors.same
          case Subscribe(replyTo) =>
            state = state.copy(subscribers = state.subscribers + replyTo)
            Behaviors.same
          case Stop =>
            Behaviors.stopped
        }.receiveSignal {
          case (_, akka.actor.typed.PostStop) =>
            println("TwitchTokenActor has stopped.")
            Behaviors.same
        }
      }
    }
  }
}