package pl.sknikod.streamscout

import io.github.cdimascio.dotenv.Dotenv
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.MessageEvent
import org.pircbotx.{Configuration, PircBotX}

import java.util.concurrent.{Executors, TimeUnit}

// TODO: create config json file
class IrcBot(channels: List[String], delaySeconds: Int) {
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
    channels.zipWithIndex.foreach { case (channel, index) =>
      scheduler.schedule(
        new Runnable {
          override def run(): Unit =
            bot.sendIRC().joinChannel(channel)
            println(s"JOIN CHANNEL $channel")
        },
        index * delaySeconds,
        TimeUnit.SECONDS
      )
    }
}

object IrcBot {
  def apply(channels: List[String] = List("#h2p_gucio", "#demonzz1", "#delordione"), delaySeconds: Int = 3): IrcBot =
    new IrcBot(channels, delaySeconds)
}


class TwitchListener() extends ListenerAdapter {
  override def onMessage(event: MessageEvent): Unit =
    val channel = event.getChannel.getName
    val message = event.getMessage
    println(s"[$channel] ${event.getUser.getLogin}#${event.getUser.getUserId}:   $message")
}