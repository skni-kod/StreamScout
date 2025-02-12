package pl.sknikod.streamscout

import Channels.MAX_CHANNELS

import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.github.cdimascio.dotenv.Dotenv
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.MessageEvent
import org.pircbotx.{Configuration, PircBotX}

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{BlockingQueue, Executors, LinkedBlockingQueue, TimeUnit}
import scala.io.Source

import scala.jdk.CollectionConverters._

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

        io.circe.parser.decode[List[String]](jsonString) match {
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


class IrcBot(channels: Channels, delaySeconds: Int) {
  private val dotenv: Dotenv = Dotenv.load()
  private val oauth: String = dotenv.get("TWITCH_IRC_OAUTH")

  private val config: Configuration = new Configuration.Builder()
    .setName("my_bot")
    .setServerPassword(s"oauth:$oauth")
    .addServer("irc.chat.twitch.tv", 6667)
    .addListener(new TwitchListener())
    .buildConfiguration()

  private val bot: PircBotX = new PircBotX(config)

  private val channelQueue: BlockingQueue[String] = new LinkedBlockingQueue[String]()
  channels.channelsName.foreach(channel => channelQueue.put(channel))

  def start(): Unit =
    val botThread = new Thread(() => bot.startBot())
    botThread.start()

    new Thread(() => {
      joinChannelsWithDelay()
    }).start()


  def joinChannel(channel: String): Unit = {
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
  }

  def leaveChannel(channelName: String): Unit =
    channels.removeChannel(channelName) match
      case Right(_) =>
        bot.sendRaw().rawLineNow(s"PART $channelName")
        println(s"LEAVE CHANNEL $channelName")
      case _ =>

  private def joinChannelsWithDelay(): Unit =
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val delay = new AtomicInteger(0)

    while (true) {
      val channel = channelQueue.take()
      scheduler.schedule(
        new Runnable {
          override def run(): Unit = {
            bot.sendIRC().joinChannel(channel)
            println(s"JOIN CHANNEL $channel")
          }
        },
        delay.getAndAdd(delaySeconds),
        TimeUnit.SECONDS
      )
    }

}

object IrcBot {
  def apply(channels: Channels = Channels(Set("#h2p_gucio", "#demonzz1", "#delordione")),
            delaySeconds: Int = 3): IrcBot =
    new IrcBot(channels, delaySeconds)
}


class TwitchListener() extends ListenerAdapter {
  override def onMessage(event: MessageEvent): Unit =
    val channel = event.getChannel.getName
    val message = event.getMessage
    println(s"[$channel] ${event.getUser.getLogin}#${event.getUser.getUserId}:   $message")
}