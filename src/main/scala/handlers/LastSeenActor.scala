package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef
import sttp.client3.{Response, ResponseException, SttpBackend, UriContext, basicRequest}
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import io.circe.generic.auto.*
import sttp.client3.circe.asJson

import java.time.{ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import scala.util.{Failure, Success}

object LastSeenActor {

  case class GetLastSeen(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  case class StreamerData(login: String)
  case class LastSeenData(login: String, timestamp: String, streamerId: String, streamer: StreamerData)
  case class ChatEntry(lastSeen: Option[LastSeenData])
  case class PageProps(chatEntry: ChatEntry)
  case class ApiResponse(pageProps: PageProps)

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      implicit val ec = context.executionContext
      implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

      Behaviors.receiveMessage {
        case GetLastSeen(message, writeActorRef) =>
          val words = message.content.split("\\s+").toList
          val channel = words.dropWhile(_ != "$lastseen").drop(1).headOption.getOrElse(message.user)

          val request = basicRequest
            .get(uri"https://xayo.pl/_next/data/MpaNPjZLX7mONwTFr5zeA/$channel.json")
            .response(asJson[ApiResponse])

          val responseFuture: Future[Response[Either[ResponseException[String, io.circe.Error], ApiResponse]]] =
            request.send(backend)

          responseFuture.onComplete {
            case Success(response) =>
              response.body match {
                case Right(apiResponse) =>
                  val lastSeenMessage = apiResponse.pageProps.chatEntry.lastSeen match {
                    case Some(lastSeen) =>
                      val parsedDate = ZonedDateTime.parse(lastSeen.timestamp, DateTimeFormatter.ISO_DATE_TIME)
                      val formattedDate = parsedDate.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
                      s"@${message.user} $channel był ostatnio widziany u ${lastSeen.streamer.login}, $formattedDate"
                    case None =>
                      s"@${message.user} Brak ostatniej aktywności na czacie dla $channel."
                  }

                  writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = lastSeenMessage, sender = message.user)

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
