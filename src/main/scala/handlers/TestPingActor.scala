package pl.sknikod.streamscout
package handlers

import akka.actor.typed.Behavior
import pl.sknikod.streamscout.ChannelActor.Command

object TestPingActor {

  case class PingMessage(content: String) extends Command

  def apply(): Behavior[Command] =
    akka.actor.typed.scaladsl.Behaviors.receiveMessage {
      case PingMessage(text) =>
        println(s"Ping received: $text")
        akka.actor.typed.scaladsl.Behaviors.same
    }
}
