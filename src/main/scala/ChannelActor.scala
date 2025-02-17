package pl.sknikod.streamscout

import akka.actor.typed.scaladsl.{Behaviors, Routers}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.journal.EventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import pl.sknikod.streamscout.handlers.{HelpActor, LastMessageActor, LastSeenActor, RecommendedStreamersActor, TestPingActor, Top10PlActor, TopWatchtimeActor, UptimeActor, ViewersCountActor, WatchtimeActor}
import pl.sknikod.streamscout.infrastructure.kafka.Message

import scala.concurrent.ExecutionContext

object ChannelActor {

  case class State(messages: List[Message] = Nil)

  trait Command
  case class NewMessage(message: Message, replyTo: ActorRef[Boolean], writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  sealed trait Event
  case class MessageAdded(message: Message) extends Event

  def commandHandler(routers: Map[String, ActorRef[Command]])(channelName: String): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case NewMessage(message, replyTo, writeActorRef) =>
        routers.get("ping").foreach(_ ! TestPingActor.TestMessage(message, writeActorRef))
        if (message.content.contains("$uptime")) {
          routers.get("uptime").foreach(_ ! UptimeActor.GetUptime(message, writeActorRef))
        }
        if (message.content.contains("$viewers")) {
          routers.get("viewers").foreach(_ ! ViewersCountActor.GetViewersCount(message, writeActorRef))
        }
        if (message.content.contains("$top10pl")) {
          routers.get("top10pl").foreach(_ ! Top10PlActor.GetTop10Pl(message, writeActorRef))
        }
        if (message.content.contains("$topwatchtime")) {
          routers.get("topwatchtime").foreach(_ ! TopWatchtimeActor.GetTopWatchtime(message, writeActorRef))
        }
        if (message.content.contains("$watchtime")) {
          routers.get("watchtime").foreach(_ ! WatchtimeActor.GetWatchtime(message, writeActorRef))
        }
        if (message.content.contains("$lastseen")) {
          routers.get("lastseen").foreach(_ ! LastSeenActor.GetLastSeen(message, writeActorRef))
        }
        if (message.content.contains("$help")) {
          routers.get("help").foreach(_ ! HelpActor.GetHelp(message, writeActorRef))
        }
        if (message.content.contains("$lastmessage")) {
          routers.get("lastmessage").foreach(_ ! LastMessageActor.GetLastMessage(message, writeActorRef))
        }
        if (message.content.contains("$recommended")) {
          routers.get("recommended").foreach(_ ! RecommendedStreamersActor.GetRecommendedStreams(message, writeActorRef))
        }
        Effect.persist(MessageAdded(message))
          .thenReply(replyTo)(_ => true)
    }

  def eventHandler(channelName: String): (State, Event) => State = (state, event) =>
    event match
      case MessageAdded(message) =>
        state.copy(messages = message :: state.messages)

  def apply(channelName: String, sharding: ClusterSharding, session: CassandraSession)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context =>
      val routers: Map[String, ActorRef[Command]] = Map(
        "ping" -> context.spawn(Routers.pool(5)(TestPingActor()), "pingRouter"),
        "uptime" -> context.spawn(Routers.pool(5)(UptimeActor(sharding)), "uptimeRouter"),
        "viewers" -> context.spawn(Routers.pool(5)(ViewersCountActor(sharding)), "viewersRouter"),
        "top10pl" -> context.spawn(Routers.pool(5)(Top10PlActor(sharding)), "top10plRouter"),
        "topwatchtime" -> context.spawn(Routers.pool(5)(TopWatchtimeActor()), "topwatchtimeRouter"),
        "watchtime" -> context.spawn(Routers.pool(5)(WatchtimeActor()), "watchtimeRouter"),
        "lastseen" -> context.spawn(Routers.pool(5)(LastSeenActor()), "lastseenRouter"),
        "help" -> context.spawn(Routers.pool(5)(HelpActor()), "helpRouter"),
        "lastmessage" -> context.spawn(Routers.pool(5)(LastMessageActor(session)), "lastmessageRouter"),
        "recommended" -> context.spawn(Routers.pool(5)(RecommendedStreamersActor(session)), "recommendedRouter"),
      )

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(channelName),
        emptyState = State(),
        commandHandler = commandHandler(routers)(channelName),
        eventHandler = eventHandler(channelName)
      ).withTagger(_ => Set("chat-tag"))
    }

}
