package pl.sknikod.streamscout
package projections

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.{Projection, ProjectionId}
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.Handler
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import scala.concurrent.ExecutionContext

class ProjectionFactory(system: ActorSystem[_], session: CassandraSession)(implicit ec: ExecutionContext) {

  def createProjection(projectionId: ProjectionId, tag: String,
                       handlerFactory: () => Handler[EventEnvelope[ChannelActor.Event]]): Projection[EventEnvelope[ChannelActor.Event]] = {
    CassandraProjection.atLeastOnce(
      projectionId = projectionId,
      sourceProvider = EventSourcedProvider.eventsByTag[ChannelActor.Event](
        system,
        readJournalPluginId = CassandraReadJournal.Identifier,
        tag = tag
      ),
      handler = handlerFactory
    )
  }
}
