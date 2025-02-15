package pl.sknikod.streamscout

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import pl.sknikod.streamscout.token.{TwitchToken, TwitchTokenActor}
import sttp.client3.circe.asJson
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend, UriContext, basicRequest}

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import akka.util.Timeout
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.github.cdimascio.dotenv.Dotenv

import scala.util.{Failure, Success}

object ChannelWriteActor {
  sealed trait Command
  case class SendMessage(channel: String, message: String, sender: String) extends Command
  case class TokenUpdated(token: TwitchToken) extends Command
  private case object CheckPending extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("TwitchWriteActor")

  private case class State(token: Option[String], messageTimestamps: List[Long] = List.empty,
                           channelLastSent: Map[String, Long] = Map.empty, pendingMessages: Queue[(String, String, String)] = Queue.empty)

  private var shard: Option[ClusterSharding] = None

  def apply(clientId: String, sharding: ClusterSharding)(implicit ec: ExecutionContext): Behavior[Command] = {
    shard = Some(sharding)
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val tokenActor = sharding.entityRefFor(TwitchTokenActor.TypeKey, clientId)

        tokenActor ! TwitchTokenActor.Subscribe(
          context.messageAdapter(response => TokenUpdated(response.token))
        )

        var state = State(None)

        val tokenFuture: Future[String] = getTokenFromActor(tokenActor)
        tokenFuture.onComplete {
          case Success(token) =>
            state = state.copy(token = Some(token))
            println(s"Received token: $token")
          case Failure(exception) =>
            println(s"Failed to retrieve token: ${exception.getMessage}")
        }

        timers.startTimerWithFixedDelay(CheckPending, 1.second)
        process(clientId, state, timers)
      }
    }
  }

  private def process(clientId: String, state: State, timers: TimerScheduler[Command])
                     (implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.receiveMessage {
      case SendMessage(channel, message, sender) =>
        val newState = trySendMessage(clientId, channel, message, state, sender)
          .getOrElse(state.copy(pendingMessages = state.pendingMessages.enqueue((channel, message, sender))))
        process(clientId, newState, timers)

      case TokenUpdated(token) =>
        val newState = state.copy(token = Some(token.accessToken))
        val (updatedState, _) = processPendingMessages(clientId, newState)
        process(clientId, updatedState, timers)

      case CheckPending =>
        val (updatedState, _) = processPendingMessages(clientId, state)
        process(clientId, updatedState, timers)
    }
  }

  private def getTokenFromActor(tokenActor: EntityRef[TwitchTokenActor.Command])
                               (implicit ec: ExecutionContext): Future[String] = {
    implicit val timeout: Timeout = Timeout(5.seconds)
    tokenActor.ask[TwitchTokenActor.TokenResponse](replyTo => TwitchTokenActor.GetToken(replyTo)).map { response =>
      response.token.accessToken
    }
  }

  private def trySendMessage(clientId: String, channel: String, message: String, state: State, sender: String)
                            (implicit ec: ExecutionContext): Option[State] = {
    val currentTime = System.currentTimeMillis()

    val globalLimitOk = state.messageTimestamps.size < 20 ||
      (currentTime - state.messageTimestamps.head) > 30000

    val channelLimitOk = state.channelLastSent.get(channel)
      .forall(lastSent => currentTime - lastSent >= 1000)

    if (globalLimitOk && channelLimitOk && state.token.isDefined) {
      sendHttpRequest(clientId, channel, message, state.token.get, sender)
      val newTimestamps = (state.messageTimestamps :+ currentTime).takeRight(20)
      val newChannelLastSent = state.channelLastSent.updated(channel, currentTime)

      Some(state.copy(
        messageTimestamps = newTimestamps,
        channelLastSent = newChannelLastSent
      ))
    } else {
      None
    }
  }

  private def processPendingMessages(clientId: String, state: State)(implicit ec: ExecutionContext): (State, Boolean) = {
    state.pendingMessages.dequeueOption match {
      case Some(((channel, message, sender), remainingQueue)) =>
        trySendMessage(clientId, channel, message, state, sender) match {
          case Some(updatedState) =>
            (updatedState.copy(pendingMessages = remainingQueue), true)
          case None =>
            (state.copy(pendingMessages = state.pendingMessages.enqueue((channel, message, sender))), false)
        }
      case None => (state, false)
    }
  }

  private def sendHttpRequest(clientId: String, channel: String, message: String, token: String, sender: String)(implicit ec: ExecutionContext): Unit = {
    case class TwitchMessageResponse(data: List[MessageData])
    case class MessageData(message_id: String, is_sent: Boolean, drop_reason: Option[String])
    implicit val messageDataDecoder: Decoder[MessageData] = deriveDecoder
    implicit val twitchMessageResponseDecoder: Decoder[TwitchMessageResponse] = deriveDecoder


    val twitchApiActor = shard.get.entityRefFor(TwitchApiActor.TypeKey, clientId)

    implicit val timeout: Timeout = Timeout(5.seconds)
    val futureSender: Future[Option[String]] = twitchApiActor.ask(replyTo => TwitchApiActor.GetChannelId(sender, replyTo))
    val futureBroadcaster: Future[Option[String]] = twitchApiActor.ask(replyTo => TwitchApiActor.GetChannelId(channel, replyTo))

    val combinedFuture = Future.sequence(Seq(futureSender, futureBroadcaster)).map(results => (results(0), results(1)))

    combinedFuture.onComplete {
      case Success((Some(senderId), Some(broadcasterId))) =>
        run(senderId, broadcasterId)
      case Success((None, Some(id2))) =>
        println(s"Channel ID for user1 not found")
        println(s"Channel ID for user2: $id2")
      case Success((Some(id1), None)) =>
        println(s"Channel ID for user1: $id1")
        println(s"Channel ID for user2 not found")
      case Success((None, None)) =>
        println("Both channel IDs not found")
      case Failure(exception) =>
        println(s"Failed to retrieve channel IDs: ${exception.getMessage}")
    }

    // TODO: change broadcaster_id and sender_id to real data
    def run(senderId: String, broadcasterId: String) =
      // TODO: delete
      val dotenv: Dotenv = Dotenv.load()
      val channelId: String = dotenv.get("TWITCH_CHANNEL_ID")

      val request = basicRequest
        .post(uri"https://api.twitch.tv/helix/chat/messages")
        .header("Authorization", s"Bearer $token")
        .header("Client-Id", clientId)
        .header("Content-Type", "application/json")
        .body(
          s"""{
             |  "broadcaster_id": "$channelId",
             |  "sender_id": "$channelId",
             |  "message": "$message"
             |}""".stripMargin
        )
        .response(asJson[TwitchMessageResponse])

      implicit val backend: SttpBackend[Identity, Any] = HttpClientSyncBackend()
      request.send(backend).body match {
        case Right(_) =>
          println(s"Message sent to channel $channel: $message")
        case Left(error) =>
          println(s"Failed to send message to channel $channel: $error")
      }
  }
}