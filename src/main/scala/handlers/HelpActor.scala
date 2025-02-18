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
        topwatchtime [channel]  /
        lastseen [channel]  /
        lastmessage [channel] [broadcaster]  /
        uptime [channel] - ile trwa stream  /
        viewers [channel]  /
        top10pl - top 10 aktualnych polskich kanałów  /
        recommended [channel] - top 5 polecanych kanałów  /
        usersentiment [channel]  /
        channelsentiment [broadcaster]
        """
        val cleanMessage = helpMessage.replace("\n", " ").replace("\r", " ")
        writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = cleanMessage, sender = message.user)
        Behaviors.same
    }

}
