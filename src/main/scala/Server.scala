package pl.sknikod.streamscout

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}

object Server extends App {

  given ActorSystem = ActorSystem("StreamScoutSystem")
  given Materializer = Materializer(summon[ActorSystem])
  given ExecutionContext = summon[ActorSystem].dispatcher

  private val route =
    pathSingleSlash {
      get {
        complete("Hello, world!")
      }
    }

  private val bindingFuture: Future[Http.ServerBinding] = Http().newServerAt("localhost", 8080).bind(route)

  scala.io.StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => summon[ActorSystem].terminate())
}
