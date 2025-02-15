package pl.sknikod.streamscout
package handlers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import pl.sknikod.streamscout.ChannelActor.Command
import pl.sknikod.streamscout.ChannelWriteActor.SendMessage
import pl.sknikod.streamscout.infrastructure.kafka.Message

object TestPingActor {

  case class PingMessage(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command
  case class TestMessage(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(): Behavior[Command] =
    Behaviors.receiveMessage {
      case PingMessage(message, writeActorRef) =>
        println(s"Ping received: ${message.content}")
        writeActorRef ! SendMessage(channel = message.channel, message = message.content, sender = message.user)
        Behaviors.same
      case TestMessage(message, writeActorRef) =>
        //writeActorRef ! SendMessage(channel = message.channel, message = message.content, sender = message.user)
        Behaviors.same
    }
}
