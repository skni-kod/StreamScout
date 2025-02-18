package pl.sknikod.streamscout
package handlers

import ChannelActor.Command
import infrastructure.kafka.Message

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import scala.concurrent.ExecutionContext

object ChannelSentimentActor {

  case class GetChannelSentiment(message: Message, writeActorRef: EntityRef[ChannelWriteActor.Command]) extends Command

  def apply(session: CassandraSession)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetChannelSentiment(message, writeActorRef) =>
        val words = message.content.split("\\s+").toList
        val channel = words.dropWhile(_ != "$channelsentiment").drop(1).headOption.getOrElse(message.channel)

        val query =
          """SELECT AVG(sentiment) AS avg_sentiment, COUNT(*) AS message_count
            |FROM streamscout.sentiment_analysis
            |WHERE channel = ?""".stripMargin

        session.selectOne(query, channel).map {
          case Some(row) =>
            if (row.getLong("message_count") == 0) {
              val response = s"@${message.user} Brak danych do obliczenia średniego sentymentu dla kanału $channel"
              writeActorRef ! ChannelWriteActor.SendMessage(message.channel, response, message.user)
            } else {
              val avg = row.getFloat("avg_sentiment") * 100
              val formatted = f"$avg%.2f"
              val response = s"@${message.user} Średni sentyment kanału $channel wynosi: $formatted%"
              writeActorRef ! ChannelWriteActor.SendMessage(message.channel, response, message.user)
            }
          case None =>
            val response = s"@${message.user} Brak danych dla kanału $channel"
            writeActorRef ! ChannelWriteActor.SendMessage(message.channel, response, message.user)
        }.recover {
          case ex: Exception =>
            println(s"Error executing query: $ex")
        }

        Behaviors.same
    }

}
