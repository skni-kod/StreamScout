package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import concurrent.duration.DurationInt

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

object Top10PlActor {

  implicit val timeout: Timeout = Timeout(5.seconds)

  case class GetTop10Pl(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(sharding: ClusterSharding)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetTop10Pl(message, writeActorRef) =>
        val twitchApiActor = sharding.entityRefFor(TwitchApiActor.TypeKey, message.clientId)
        val futureTop10Pl: Future[Map[String, Int]] = twitchApiActor.ask(replyTo => TwitchApiActor.GetTop10PL(replyTo))


        futureTop10Pl.onComplete {
          case Success(channels: Map[String, Int]) =>
            val sortedChannels = channels.toList.sortBy(-_._2)
            val msg = sortedChannels
              .zipWithIndex
              .map { case ((streamer, viewers), index) => s"${index + 1}. $streamer - $viewers widzów" }
              .mkString(" | ")
            val twitchMessage = s"@${message.user} 10 PL: $msg"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = twitchMessage, sender = message.user)
        }
        Behaviors.same
    }


}
