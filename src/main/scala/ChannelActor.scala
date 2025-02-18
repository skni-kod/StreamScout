package pl.sknikod.streamscout

import handlers.*
import infrastructure.kafka.Message

import akka.actor.typed.scaladsl.{Behaviors, Routers}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

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
        commandMappings.foreach { case (cmd, (routerKey, msgCreator)) =>
          if (message.content.contains(cmd)) {
            routers.get(routerKey).foreach(_ ! msgCreator(message, writeActorRef))
          }
        }
        Effect.persist(MessageAdded(message))
          .thenReply(replyTo)(_ => true)
    }

  def eventHandler(channelName: String): (State, Event) => State = (state, event) =>
    event match
      case MessageAdded(message) =>
        state.copy(messages = message :: state.messages)

  private val commandMappings = Map(
    "$uptime" -> ("uptime", (msg: Message, ref: EntityRef[ChannelWriteActor.Command]) => UptimeActor.GetUptime(msg, ref)),
    "$viewers" -> ("viewers", (msg, ref) => ViewersCountActor.GetViewersCount(msg, ref)),
    "$top10pl" -> ("top10pl", (msg, ref) => Top10PlActor.GetTop10Pl(msg, ref)),
    "$topwatchtime" -> ("topwatchtime", (msg, ref) => TopWatchtimeActor.GetTopWatchtime(msg, ref)),
    "$watchtime" -> ("watchtime", (msg, ref) => WatchtimeActor.GetWatchtime(msg, ref)),
    "$lastseen" -> ("lastseen", (msg, ref) => LastSeenActor.GetLastSeen(msg, ref)),
    "$help" -> ("help", (msg, ref) => HelpActor.GetHelp(msg, ref)),
    "$lastmessage" -> ("lastmessage", (msg, ref) => LastMessageActor.GetLastMessage(msg, ref)),
    "$recommended" -> ("recommended", (msg, ref) => RecommendedStreamersActor.GetRecommendedStreams(msg, ref)),
    "$usersentiment" -> ("usersentiment", (msg, ref) => UserSentimentActor.GetUserSentiment(msg, ref)),
    "$channelsentiment" -> ("channelsentiment", (msg, ref) => ChannelSentimentActor.GetChannelSentiment(msg, ref))
  )

  def apply(channelName: String, sharding: ClusterSharding, session: CassandraSession)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context =>
      val routers: Map[String, ActorRef[Command]] = Map(
        "uptime" -> context.spawn(Routers.pool(5)(UptimeActor(sharding)), "uptimeRouter"),
        "viewers" -> context.spawn(Routers.pool(5)(ViewersCountActor(sharding)), "viewersRouter"),
        "top10pl" -> context.spawn(Routers.pool(5)(Top10PlActor(sharding)), "top10plRouter"),
        "topwatchtime" -> context.spawn(Routers.pool(5)(TopWatchtimeActor()), "topwatchtimeRouter"),
        "watchtime" -> context.spawn(Routers.pool(5)(WatchtimeActor()), "watchtimeRouter"),
        "lastseen" -> context.spawn(Routers.pool(5)(LastSeenActor()), "lastseenRouter"),
        "help" -> context.spawn(Routers.pool(5)(HelpActor()), "helpRouter"),
        "lastmessage" -> context.spawn(Routers.pool(5)(LastMessageActor(session)), "lastmessageRouter"),
        "recommended" -> context.spawn(Routers.pool(5)(RecommendedStreamersActor(session)), "recommendedRouter"),
        "usersentiment" -> context.spawn(Routers.pool(5)(UserSentimentActor(session)), "usersentimentRouter"),
        "channelsentiment" -> context.spawn(Routers.pool(5)(ChannelSentimentActor(session)), "channelsentimentRouter"),
      )

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(channelName),
        emptyState = State(),
        commandHandler = commandHandler(routers)(channelName),
        eventHandler = eventHandler(channelName)
      ).withTagger(_ => Set("chat-tag"))
    }

}
