package pl.sknikod.streamscout

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import pl.sknikod.streamscout.token.TwitchTokenActor

import scala.concurrent.ExecutionContext

object ChannelWriteActor {

  sealed trait Command
  case class SendMessage(channel: String, message: String) extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("TwitchWriteActor")

  private case class State(tokenActor: EntityRef[TwitchTokenActor.Command])

  def apply(clientId: String, sharding: ClusterSharding)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      val tokenActor: EntityRef[TwitchTokenActor.Command] =
        sharding.entityRefFor(TwitchTokenActor.TypeKey, clientId)

      var state = State(tokenActor)

      Behaviors.receiveMessage {
        case SendMessage(channel, message) =>
          // TODO: WRITE TO CHAT
          println(s"WRITE TO CHAT => $channel: $message")
          Behaviors.same
      }
    }
  }
}
