package pl.sknikod.streamscout

import io.github.cdimascio.dotenv.Dotenv
import org.pircbotx.{Configuration, PircBotX}
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.MessageEvent

import scala.jdk.CollectionConverters.*

object IrcBot {
  private val dotenv: Dotenv = Dotenv.load()
  private val oauth = dotenv.get("TWITCH_IRC_OAUTH")

  // TODO: create config json file
  private val channels = List("#h2p_gucio", "#demonzz1").asJava

  private val config = new Configuration.Builder()
    .setName("my_bot")
    .setServerPassword(s"oauth:$oauth")
    .addServer("irc.chat.twitch.tv", 6667)
    .addAutoJoinChannels(channels)
    .addListener(new TwitchListener())
    .buildConfiguration()

  private val bot = new PircBotX(config)

  def start(): Unit =
    bot.startBot()
}

class TwitchListener() extends ListenerAdapter {
  override def onMessage(event: MessageEvent): Unit = {
    val channel = event.getChannel.getName
    val message = event.getMessage
    println(s"[$channel] ${event.getUser.getLogin}#${event.getUser.getUserId}:   $message")
  }
}