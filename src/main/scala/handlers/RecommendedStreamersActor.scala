package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import pl.sknikod.streamscout.token.TwitchTokenActor.RefreshToken
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend

import java.time.{Duration, ZoneId}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.DurationInt

object RecommendedStreamersActor {

  case class LoadWatchtimeFromDb() extends Command
  case class GetRecommendedStreams(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(session: CassandraSession): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        implicit val ec = context.executionContext
        implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

        var watchtime: Map[String, Map[String, Int]] = Map()

        timers.startTimerWithFixedDelay(LoadWatchtimeFromDb(), 0.minutes, 60.minutes)

        Behaviors.receiveMessage {
          case LoadWatchtimeFromDb() =>
            val query = "SELECT username, watchtime FROM streamscout.user_watchtime"
            session.selectAll(query).map { rows =>
              watchtime = rows.map { row =>
                val username = row.getString("username")
                val watchtime = row.getMap("watchtime", classOf[String], classOf[Integer]).asScala.toMap.view.mapValues(_.toInt).toMap
                username -> watchtime
              }.toMap
            }

            Behaviors.same

          case GetRecommendedStreams(message, writeActorRef) =>
            val words = message.content.split("\\s+").toList

            val currentUser = words.dropWhile(_ != "$recommended").drop(1).headOption.getOrElse(message.user)
            val currentUserWatch = watchtime.getOrElse(currentUser, Map.empty)

            val allStreamers = watchtime.values.flatMap(_.keys).toSet

            val unwatched = allStreamers.diff(currentUserWatch.keySet)

            def calculateSimilarity(user1: String, user2: String): Int = {
              val user1Watch = watchtime.getOrElse(user1, Map.empty)
              val user2Watch = watchtime.getOrElse(user2, Map.empty)
              (user1Watch.keySet intersect user2Watch.keySet).map { s =>
                math.min(user1Watch(s), user2Watch(s))
              }.sum
            }

            val recommendations = unwatched.toList.flatMap { streamer =>
              var score = 0

              watchtime.foreach { case (otherUser, otherWatch) =>
                if (otherUser != currentUser && otherWatch.contains(streamer)) {
                  val similarity = calculateSimilarity(currentUser, otherUser)
                  score += similarity * otherWatch(streamer)
                }
              }

              if (score > 0) Some(streamer -> score) else None
            }
            .sortBy(-_._2)
            .take(5)
            .map(_._1)

            val formattedRecommendations = recommendations match {
              case Nil => s"@${message.user} aktualnie brak rekomendacji dla użytkownika $currentUser"
              case recs =>
                s"@${message.user} Top 5 polecanych streamerów dla użytkownika $currentUser: " +
                  recs.zipWithIndex.map { case (streamer, idx) =>
                    s"${idx + 1}. $streamer"
                  }.mkString(" | ")
            }
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = formattedRecommendations, sender = message.user)
            Behaviors.same
        }
      }
    }

}