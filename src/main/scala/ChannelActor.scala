package pl.sknikod.streamscout

import akka.actor.typed.scaladsl.{Behaviors, Routers}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.journal.EventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.sknikod.streamscout.handlers.TestPingActor
import pl.sknikod.streamscout.infrastructure.kafka.Message

object ChannelActor {

  case class State(messages: List[Message] = Nil)

  trait Command
  case class NewMessage(message: Message, replyTo: ActorRef[Boolean]) extends Command

  sealed trait Event
  case class MessageAdded(message: Message) extends Event

  def commandHandler(routers: Map[String, ActorRef[Command]])(channelName: String): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case NewMessage(message, replyTo) =>
        if (message.content.contains("@")) {
          routers.get("ping").foreach(_ ! TestPingActor.PingMessage(message.content))
        }
        Effect.persist(MessageAdded(message))
          .thenReply(replyTo)(_ => true)
    }

  def eventHandler(channelName: String): (State, Event) => State = (state, event) =>
    event match
      case MessageAdded(message) =>
        state.copy(messages = message :: state.messages)

  def apply(channelName: String): Behavior[Command] =
    Behaviors.setup { context =>
      val routers: Map[String, ActorRef[Command]] = Map(
        "ping" -> context.spawn(Routers.pool(5)(TestPingActor()), "pingRouter"),
      )

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(channelName),
        emptyState = State(),
        commandHandler = commandHandler(routers)(channelName),
        eventHandler = eventHandler(channelName)
      ).withTagger(_ => Set("chat-tag"))
    }

}
