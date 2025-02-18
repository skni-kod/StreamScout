package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

object ViewersCountActor {

  implicit val timeout: Timeout = Timeout(5.seconds)

  case class GetViewersCount(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(sharding: ClusterSharding)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetViewersCount(message, writeActorRef) =>
        val words = message.content.split("\\s+").toList
        val username = words.dropWhile(_ != "$viewers").drop(1).headOption.getOrElse(message.channel)

        val twitchApiActor = sharding.entityRefFor(TwitchApiActor.TypeKey, message.clientId)
        val futureViewersCount: Future[Option[Int]] = twitchApiActor.ask(replyTo => TwitchApiActor.GetViewersCount(username, replyTo))

        futureViewersCount.onComplete {
          case Success(Some(viewersCount: Int)) =>
            val content = s"@${message.user} $username ma aktualnie $viewersCount widzów"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = content, sender = message.user)
          case Success(None) =>
            val content = s"@${message.user} $username obecnie nie streamuje"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = content, sender = message.user)
        }
        Behaviors.same
    }

}
