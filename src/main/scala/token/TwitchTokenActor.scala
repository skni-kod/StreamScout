package pl.sknikod.streamscout
package token

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import sttp.client3.circe.asJson
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend, UriContext, basicRequest}
import io.circe.generic.auto._

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object TwitchTokenActor {

  sealed trait Command
  case object RefreshToken extends Command
  case object Stop extends Command

  case class RefreshResponse(access_token: String, refresh_token: String, expires_in: Int)

  def apply(token: TwitchToken, dao: TwitchTokenDAO)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      def refresh(): Unit = {
        val request = basicRequest
          .post(uri"https://id.twitch.tv/oauth2/token")
          .body(Map(
            "grant_type" -> "refresh_token",
            "refresh_token" -> token.refreshToken,
            "client_id" -> token.clientId,
            "client_secret" -> token.clientSecret
          ))
          .response(asJson[RefreshResponse])

        implicit val backend: SttpBackend[Identity, Any] = HttpClientSyncBackend()
        request.send(backend).body match {
          case Right(data: RefreshResponse) =>
            val newToken = TwitchToken(token.clientId, token.clientSecret, data.access_token, data.refresh_token, Instant.now().plusSeconds(data.expires_in))
            dao.saveToken(newToken)
            println(s"Token refreshed for ${token.clientId}!")
          case Left(error) =>
            println(s"Error refreshing token for ${token.clientId}: $error")
        }
      }

      // TODO: it's refresh after actor start
      //refresh()

      timers.startTimerWithFixedDelay(RefreshToken, 55.minutes)

      Behaviors.receiveMessage {
        case RefreshToken =>
          refresh()
          Behaviors.same
        case Stop =>
          Behaviors.stopped
      }
    }
  }
}