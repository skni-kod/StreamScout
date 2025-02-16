package pl.sknikod.streamscout
package projections

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.ProjectionId
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.Handler
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import pl.sknikod.streamscout.ChannelActor.Event

import scala.concurrent.{ExecutionContext, Future}

class TestProjection(session: CassandraSession)(implicit ec: ExecutionContext) extends Handler[EventEnvelope[ChannelActor.Event]] {
  override def process(envelope: EventEnvelope[ChannelActor.Event]): Future[Done] = {
    envelope.event match {
      case ChannelActor.MessageAdded(message) =>
        println(s"Projection: $message")
    }

    session.selectOne("SELECT COUNT(*) FROM streamscout.messages").map {
      case Some(row) =>
        val count = row.getLong("count")
        println(s"Number of records in messages: $count")
        Done
      case None =>
        println("No message found")
        Done
    }.recover {
      case ex: Exception =>
        println(s"Error executing query: $ex")
        Done
    }

    Future.successful(Done)
  }
}
