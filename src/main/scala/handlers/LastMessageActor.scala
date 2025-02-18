package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext

object LastMessageActor {

  case class GetLastMessage(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(session: CassandraSession)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetLastMessage(message, writeActorRef) =>
        val words = message.content.split("\\s+").toList
        val username = words.dropWhile(_ != "$lastmessage").drop(1).headOption.getOrElse(message.user)
        val channel = words.dropWhile(_ != "$lastmessage").drop(2).headOption.getOrElse(message.channel)

        val query =
          """
            |SELECT channel, username, content, date, client_id
            |FROM streamscout.last_user_messages
            |WHERE channel = ? AND username = ?
            |""".stripMargin

        session.selectOne(query, channel, username).map {
          case Some(row) =>
            val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            val content = row.getString("content")
            val date = row.getInstant("date").plus(1, ChronoUnit.HOURS).atZone(ZoneId.systemDefault()).format(dateFormatter)
            val response = s"@${message.user} Ostatnia wiadomość użytkownika $username na kanale $channel ($date): $content"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = response, sender = message.user)
          case None =>
            val response = s"@${message.user} Brak ostatniej wiadomości dla użytkownika $username na kanale $channel"
            writeActorRef ! ChannelWriteActor.SendMessage(channel = message.channel, message = response, sender = message.user)
        }.recover {
          case ex: Exception =>
            println(s"Error executing query: $ex")
        }

        Behaviors.same
    }

}
