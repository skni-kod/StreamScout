package pl.sknikod.streamscout

import Channels.MAX_CHANNELS

import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.github.cdimascio.dotenv.Dotenv
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.MessageEvent
import org.pircbotx.{Configuration, PircBotX}

import java.util.concurrent.{Executors, TimeUnit}
import scala.io.Source

case class Channels (channelsName: List[String]) {
  require(channelsName.size <= MAX_CHANNELS, s"Cannot have more than ${MAX_CHANNELS} channels!")

  def addChannel(channel: String): Either[String, Channels] =
    if (channelsName.size >= MAX_CHANNELS)
      Left(s"Cannot add more than ${MAX_CHANNELS} channels!")
    else
      Right(Channels(channelsName :+ channel))
}

object Channels {
  private val MAX_CHANNELS: Int = 50

  def fromJsonFile(filename: String): Either[String, Channels] = {
    try {
      val source = Source.fromFile(filename)
      val jsonString = source.mkString
      source.close()

      decode[List[String]](jsonString) match {
        case Right(channels) if channels.size <= MAX_CHANNELS =>
          Right(Channels(channels))
        case Right(_) =>
          Left(s"JSON contains more than $MAX_CHANNELS channels!")
        case Left(error) =>
          Left(s"Error parsing JSON: ${error.getMessage}")
      }
    } catch {
      case e: Exception => Left(s"Error reading file: ${e.getMessage}")
    }
  }
}


// TODO: create config json file
class IrcBot(channels: Channels, delaySeconds: Int) {
  private val dotenv: Dotenv = Dotenv.load()
  private val oauth: String = dotenv.get("TWITCH_IRC_OAUTH")

  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  private val config: Configuration = new Configuration.Builder()
    .setName("my_bot")
    .setServerPassword(s"oauth:$oauth")
    .addServer("irc.chat.twitch.tv", 6667)
    .addListener(new TwitchListener())
    .buildConfiguration()

  private val bot: PircBotX = new PircBotX(config)

  def start(): Unit =
    val botThread = new Thread(() => bot.startBot())
    botThread.start()

    joinChannelWithDelay()

  private def joinChannelWithDelay(): Unit =
    channels.channelsName.zipWithIndex.foreach { case (channel, index) =>
      scheduler.schedule(
        new Runnable {
          override def run(): Unit = {
            bot.sendIRC().joinChannel(channel)
            println(s"JOIN CHANNEL $channel")
          }
        },
        index * delaySeconds,
        TimeUnit.SECONDS
      )
    }
}

object IrcBot {
  def apply(channels: Channels = Channels(List("#h2p_gucio", "#demonzz1", "#delordione")),
            delaySeconds: Int = 3): IrcBot =
    new IrcBot(channels, delaySeconds)
}


class TwitchListener() extends ListenerAdapter {
  override def onMessage(event: MessageEvent): Unit =
    val channel = event.getChannel.getName
    val message = event.getMessage
    println(s"[$channel] ${event.getUser.getLogin}#${event.getUser.getUserId}:   $message")
}