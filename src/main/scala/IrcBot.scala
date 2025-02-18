package pl.sknikod.streamscout

import Channels.MAX_CHANNELS
import IrcBot.*
import infrastructure.kafka.{KafkaProducerConfig, Message}
import token.{TwitchToken, TwitchTokenActor}

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import io.circe.*
import io.circe.parser.*
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.MessageEvent
import org.pircbotx.{Configuration, PircBotX}

import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{BlockingQueue, Executors, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success}

case class Channels (channelsName: Set[String]) {
  require(channelsName.size <= MAX_CHANNELS, s"Cannot have more than ${MAX_CHANNELS} channels!")

  def addChannel(channel: String): Either[String, Channels] =
    if (channelsName.size >= MAX_CHANNELS)
      Left(s"Cannot add more than ${MAX_CHANNELS} channels!")
    else
      Right(Channels(channelsName + channel))

  def removeChannel(channel: String): Either[String, Channels] =
    if (!channelsName.contains(channel))
      Left(s"Channel $channel not found in the list!")
    else
      Right(Channels(channelsName - channel))

}

object Channels {
  val MAX_CHANNELS: Int = 50

  def fromJsonFile(filename: String): Either[String, Channels] = {
    Option(getClass.getClassLoader.getResourceAsStream(filename)) match {
      case Some(stream) =>
        val source = Source.fromInputStream(stream)
        val jsonString = source.mkString
        source.close()

        parser.decode[List[String]](jsonString) match {
          case Right(channels) if channels.size <= MAX_CHANNELS =>
            Right(Channels(channels.toSet))
          case Right(_) =>
            Left(s"JSON contains more than $MAX_CHANNELS channels!")
          case Left(error) =>
            Left(s"Error parsing JSON: ${error.getMessage}")
        }

      case None =>
        Left(s"File $filename not found in resources!")
    }
  }
}

class IrcBot(context: ActorContext[IrcBot.Command], channels: Channels, delaySeconds: Int,
             kafkaProducer: KafkaProducerConfig, clientId: String, sharding: ClusterSharding)(implicit system: ActorSystem[_]) {

  implicit val ec: ExecutionContext = system.executionContext

  private var bot: Option[PircBotX] = None
  private val channelQueue: BlockingQueue[String] = new LinkedBlockingQueue[String]()
  channels.channelsName.foreach(channel => channelQueue.put(channel))

  private val tokenActor: EntityRef[TwitchTokenActor.Command] =
    sharding.entityRefFor(TwitchTokenActor.TypeKey, clientId)

  tokenActor ! TwitchTokenActor.Subscribe(
    context.messageAdapter(response => IrcBot.TokenUpdated(response.token))
  )

  private def getTokenFromActor: Future[String] = {
    import concurrent.duration.DurationInt
    implicit val timeout: Timeout = Timeout(5.seconds)
    tokenActor.ask[TwitchTokenActor.TokenResponse](replyTo => TwitchTokenActor.GetToken(replyTo)).map { response =>
      response.token.accessToken
    }
  }

  private def initializeBot(oauthToken: String): Unit = {
    val config: Configuration = new Configuration.Builder()
      .setName("stream_scout_bot")
      .setServerPassword(s"oauth:$oauthToken")
      .addServer("irc.chat.twitch.tv", 6667)
      .addListener(new TwitchListener(kafkaProducer, clientId))
      .buildConfiguration()

    bot = Some(new PircBotX(config))
    val botThread = new Thread(() => bot.get.startBot())
    botThread.start()

    new Thread(() => {
      joinChannelsWithDelay()
    }).start()
  }

  @volatile private var isRunning = true

  private def joinChannelsWithDelay(): Unit = {
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val delay = new AtomicInteger(0)

    while (isRunning) {
      val channel = channelQueue.take()
      scheduler.schedule(
        new Runnable {
          override def run(): Unit = {
            bot.get.sendIRC().joinChannel(channel)
            println(s"JOIN CHANNEL $channel")
          }
        },
        delay.getAndAdd(delaySeconds),
        TimeUnit.SECONDS
      )
    }
    scheduler.shutdown()
  }

  def behavior(): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Start() =>
        getTokenFromActor.onComplete {
          case Success(oauthToken) =>
            initializeBot(oauthToken)
          case Failure(exception) =>
            println(s"Failed to get token from TwitchTokenActor: ${exception.getMessage}")
        }
        Behaviors.same

      case JoinChannel(channel) =>
        if (channels.channelsName.size < Channels.MAX_CHANNELS) {
          channels.addChannel(channel) match {
            case Right(updatedChannels) =>
              channelQueue.put(channel)
              println(s"Channel $channel added to queue.")
            case Left(error) =>
              println(error)
          }
        } else {
          println(s"Cannot add more than ${Channels.MAX_CHANNELS} channels!")
        }
        Behaviors.same

      case LeaveChannel(channelName) =>
        channels.removeChannel(channelName) match {
          case Right(_) =>
            bot.get.sendRaw().rawLineNow(s"PART $channelName")
            println(s"LEAVE CHANNEL $channelName")
          case _ =>
        }
        Behaviors.same

      case TokenUpdated(token) =>
        bot.foreach { b =>
          println(s"Stopping bot for clientId $clientId...")
          isRunning = false
          b.close()
        }

        Thread.sleep(2000)

        channelQueue.clear()
        channels.channelsName.foreach(channelQueue.put)

        println(s"Restarting bot with new token...")
        isRunning = true
        initializeBot(token.accessToken)
        Behaviors.same
    }
  }
}

object IrcBot {
  sealed trait Command
  case class Start() extends Command
  case class JoinChannel(channel: String) extends Command
  case class LeaveChannel(channelName: String) extends Command
  case class TokenUpdated(token: TwitchToken) extends Command

  def apply(
             channels: Channels,
             delaySeconds: Int,
             kafkaProducer: KafkaProducerConfig,
             clientId: String,
             sharding: ClusterSharding
           )(implicit system: ActorSystem[_]): Behavior[Command] = {
    Behaviors.setup { context =>
      new IrcBot(context, channels, delaySeconds, kafkaProducer, clientId, sharding).behavior()
    }
  }
}



class TwitchListener(kafkaProducer: KafkaProducerConfig, clientId: String)(implicit ec: ExecutionContext) extends ListenerAdapter {
  override def onMessage(event: MessageEvent): Unit =
    val channel = event.getChannel.getName.stripPrefix("#")
    val user = event.getUser.getLogin
    val messageContent = event.getMessage
    val timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimestamp), ZoneOffset.UTC)

    val message = Message(channel, user, messageContent, timestamp, clientId)

    val record = new ProducerRecord[String, Message]("messages", channel, message)

    kafkaProducer.sendMessage("messages", key = channel, value = message).andThen{
      case Failure(exception) =>
        println(s"Error sending message to Kafka: ${exception.getMessage}")
    }
}