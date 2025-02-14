package pl.sknikod.streamscout
package handlers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import pl.sknikod.streamscout.ChannelActor.Command
import pl.sknikod.streamscout.ChannelWriteActor.SendMessage
import pl.sknikod.streamscout.infrastructure.kafka.Message

object TestPingActor {

  case class PingMessage(content: String) extends Command
  case class TestMessage(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(): Behavior[Command] =
    Behaviors.receiveMessage {
      case PingMessage(text) =>
        println(s"Ping received: $text")
        Behaviors.same
      case TestMessage(message, writeActorRef) =>
        writeActorRef ! SendMessage(channel = message.channel, message = message.content)
        Behaviors.same
    }
}
