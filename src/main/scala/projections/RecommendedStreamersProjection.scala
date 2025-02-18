package pl.sknikod.streamscout
package projections

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import io.circe.generic.auto.*
import sttp.client3.*
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.client3.circe.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

case class StreamerData(streamer: String, count: Int)

class RecommendedStreamersProjection(session: CassandraSession)(implicit ec: ExecutionContext, system: ActorSystem[_])
  extends Handler[EventEnvelope[ChannelActor.Event]] {

  implicit val backend: SttpBackend[Future, Any] = AsyncHttpClientFutureBackend()

  private val insertStatementFuture: Future[PreparedStatement] = session.prepare(
    "INSERT INTO streamscout.user_watchtime (username, watchtime) VALUES (?, ?)"
  )

  private val selectStatementFuture: Future[PreparedStatement] = session.prepare(
    "SELECT watchtime FROM streamscout.user_watchtime WHERE username = ?"
  )

  private val requestQueue: mutable.Queue[String] = mutable.Queue()

  system.scheduler.scheduleAtFixedRate(0.seconds, 1.second) { () =>
    if (requestQueue.nonEmpty) {
      val user = requestQueue.dequeue()
      println(s"Fetching watchtime for user: $user")
      fetchWatchtimeFromAPI(user).onComplete {
        case Success(Some(watchtime)) => processMessage(user, watchtime)
        case Success(None) =>
          println(s"Failed to fetch watchtime for user $user")
        case Failure(exception) =>
          println(s"Error fetching data from API: ${exception.getMessage}")
      }
    }
  }

  override def process(envelope: EventEnvelope[ChannelActor.Event]): Future[Done] = {
    envelope.event match {
      case ChannelActor.MessageAdded(message) =>
        val user = message.user
        checkUserExists(user).onComplete {
          case Success(Some(existingWatchtime)) =>
            println(s"User $user found in DB, updating watchtime.")
            processMessage(user, existingWatchtime)

          case Success(None) =>
            println(s"User $user not found, adding to API queue...")
            requestQueue.enqueue(user)

          case Failure(exception) =>
            println(s"Error checking user in DB: ${exception.getMessage}")
        }
        Future.successful(Done)

      case _ => Future.successful(Done)
    }
  }

  private def checkUserExists(user: String): Future[Option[java.util.Map[String, Integer]]] = {
    for {
      selectStatement <- selectStatementFuture
      boundStatement = selectStatement.bind().setString("username", user)
      resultSet <- session.selectOne(boundStatement)
    } yield {
      resultSet.map(row => row.getMap("watchtime", classOf[String], classOf[Integer]))
    }
  }

  private def fetchWatchtimeFromAPI(user: String): Future[Option[java.util.Map[String, Integer]]] = {
    val request = basicRequest
      .get(uri"https://xayo.pl/api/mostWatched/$user")
      .response(asJson[List[StreamerData]])

    val responseFuture = request.send(backend)

    responseFuture.map { response =>
      response.body match {
        case Right(streamers) if streamers.nonEmpty =>
          Some(streamers.map(s => s.streamer -> Integer.valueOf(s.count)).toMap.asJava)
        case _ => None
      }
    }
  }

  private def processMessage(user: String, watchtime: java.util.Map[String, Integer]): Unit = {
    insertStatementFuture.onComplete {
      case Success(insertStatement) =>
        val boundStatement = insertStatement.bind()
          .setString("username", user)
          .setMap("watchtime", watchtime, classOf[String], classOf[Integer])

        session.executeWrite(boundStatement).onComplete {
          case Success(_) =>
            println(s"Successfully inserted/updated message: $user -> $watchtime")
          case Failure(exception) =>
            println(s"Failed to insert message: ${exception.getMessage}")
        }

      case Failure(exception) =>
        println(s"Failed to prepare insert statement: ${exception.getMessage}")
    }
  }
}
