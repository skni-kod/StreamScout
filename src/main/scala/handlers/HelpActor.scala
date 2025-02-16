package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef

object HelpActor {

  case class GetHelp(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetHelp(message, writeActorRef) =>
        val helpMessage = s"@${message.user} dostępne komendy:\n" +
        """
        watchtime [channel] [broadcaster] - watchtime na kanale  /
        topwatchtime [channel] - ranking kanałów z największym watchtimem  /
        lastseen [channel] - gdzie i kiedy był ostatnio widziany kanał  /
        lastmessage [channel] [broadcaster] - ostatnia wiadomość  /
        uptime [channel] - ile trwa stream  /
        viewers [channel] - ilu widzów ma kanał  /
        top10pl - top 10 aktualnych polskich kanałów
        """
        val cleanMessage = helpMessage.replace("\n", " ").replace("\r", " ")
        writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = cleanMessage, sender = message.user)
        Behaviors.same
    }

}
