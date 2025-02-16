package pl.sknikod.streamscout
package handlers

import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef
import pl.sknikod.streamscout.ChannelActor.Command
import sttp.client3.{Response, ResponseException, SttpBackend, UriContext, basicRequest}
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import io.circe.generic.auto.*
import sttp.client3.circe.asJson

import scala.concurrent.Future
import scala.util.{Failure, Success}

object WatchtimeActor {

  case class GetWatchtime(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  case class StreamerData(streamer: String, count: Int)

  def formatWatchtime(minutes: Int): String = {
    val days = minutes / (24 * 60)
    val hours = (minutes % (24 * 60)) / 60
    val remainingMinutes = minutes % 60
    s"${if (days > 0) s"${days}d " else ""}${if (hours > 0) s"${hours}h " else ""}${remainingMinutes}m"
  }

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      implicit val ec = context.executionContext
      implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

      Behaviors.receiveMessage {
        case GetWatchtime(message, writeActorRef) =>

          val words = message.content.split("\\s+").toList
          val channel = words.dropWhile(_ != "$watchtime").drop(1).headOption.getOrElse(message.user)
          val requestedStreamer = words.dropWhile(_ != "$watchtime").drop(2).headOption.getOrElse(message.channel)

          val request = basicRequest
            .get(uri"https://xayo.pl/api/mostWatched/$channel")
            .response(asJson[List[StreamerData]])

          val responseFuture: Future[Response[Either[ResponseException[String, io.circe.Error], List[StreamerData]]]] =
            request.send(backend)

          responseFuture.onComplete {
            case Success(response) =>
              response.body match {
                case Right(streamers) if streamers.nonEmpty =>
                  streamers.find(_.streamer.equalsIgnoreCase(requestedStreamer)) match {
                    case Some(foundStreamer) =>
                      val formattedTime = formatWatchtime(foundStreamer.count * 5)
                      val messageText = s"@${message.user} Watchtime użytkownika $channel na kanale $requestedStreamer: $formattedTime"
                      writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = messageText, sender = message.user)

                    case None =>
                      val notFoundMessage = s"@${message.user} Nie znaleziono użytkownika $requestedStreamer na liście watchtime dla $channel."
                      writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = notFoundMessage, sender = message.user)
                  }

                case Left(error) =>
                  context.log.error(s"Error processing JSON: $error")
              }

            case Failure(exception) =>
              context.log.error(s"Error fetching data: ${exception.getMessage}")
          }

          Behaviors.same
      }
    }
}
