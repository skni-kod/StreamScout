package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import ChannelWriteActor.SendMessage
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout

import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

object UptimeActor {

  case class GetUptime(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  implicit val timeout: Timeout = Timeout(5.seconds)

  def formatDuration(duration: Duration): String = {
    val hours = duration.toHours
    val minutes = duration.toMinutes % 60
    val seconds = duration.getSeconds % 60

    val parts = Seq(
      if (hours > 0) Some(s"$hours godzin") else None,
      if (minutes > 0) Some(s"$minutes minut") else None,
      if (seconds > 0) Some(s"$seconds sekund") else None
    ).flatten

    if (parts.isEmpty) "0 sekund" else parts.mkString(", ")
  }

  def apply(sharding: ClusterSharding)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetUptime(message, writeActorRef) =>

        val words = message.content.split("\\s+").toList
        val username = words.dropWhile(_ != "$uptime").drop(1).headOption.getOrElse(message.channel)

        val twitchApiActor = sharding.entityRefFor(TwitchApiActor.TypeKey, message.clientId)
        val futureUptime: Future[Option[Duration]] = twitchApiActor.ask(replyTo => TwitchApiActor.GetUptime(username, replyTo))

        futureUptime.onComplete {
          case Success(Some(duration: Duration)) =>
            val content = s"@${message.user} $username streamuje od ${formatDuration(duration)}"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = content, sender = message.user)
          case Success(None) =>
            val content = s"@${message.user} $username obecnie nie streamuje"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = content, sender = message.user)
        }
        Behaviors.same
    }


}
