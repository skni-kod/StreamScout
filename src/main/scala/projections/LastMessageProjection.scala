package pl.sknikod.streamscout
package projections

import infrastructure.kafka.Message

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement

import java.time.{Instant, ZoneId}
import scala.concurrent.{ExecutionContext, Future}

class LastMessageProjection(session: CassandraSession)(implicit ec: ExecutionContext)
  extends Handler[EventEnvelope[ChannelActor.Event]] {

  private val updateStatementFuture: Future[PreparedStatement] = session.prepare(
    """
      |INSERT INTO streamscout.last_user_messages
      |(channel, username, content, date, client_id)
      |VALUES (?, ?, ?, ?, ?)
      |""".stripMargin
  ).recover {
    case ex: Exception =>
      println(s"Failed to prepare statement: ${ex.getMessage}")
      throw ex
  }

  override def process(envelope: EventEnvelope[ChannelActor.Event]): Future[Done] = {
    envelope.event match {
      case ChannelActor.MessageAdded(message) if message.clientId != null =>
        processMessage(message).recover {
          case ex: Exception =>
            println(s"Failed to process message: ${ex.getMessage}")
            Done
        }

      case ChannelActor.MessageAdded(message) if message.clientId == null =>
        println(s"Skipping message with null clientId: $message")
        Future.successful(Done)

      case _ =>
        Future.successful(Done)
    }
  }

  private def processMessage(message: Message): Future[Done] = {
    val instant = message.date.atZone(ZoneId.systemDefault()).toInstant
    for {
      updateStatement <- updateStatementFuture
      boundStatement = updateStatement.bind()
        .setString("channel", message.channel)
        .setString("username", message.user)
        .setString("content", message.content)
        .set("date", instant, classOf[Instant])
        .setString("client_id", message.clientId)
      _ <- session.executeWrite(boundStatement)
    } yield {
      println(s"Successfully inserted message: ${message.clientId}")
      Done
    }
  }
}