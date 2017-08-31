package io.radicalbit.nsdb.web

import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import io.radicalbit.nsdb.actors.DatabaseActorsGuardian
import io.radicalbit.nsdb.core.CoreActors

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait Web extends StaticResources with WsResources with QueryResources { this: CoreActors =>

  val config                = system.settings.config
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher   = system.dispatcher
  implicit val timeout: Timeout

  (guardian ? DatabaseActorsGuardian.GetPublisher).mapTo[ActorRef].onComplete {
    case Success(publisher) =>
      val api: Route = staticResources ~ wsResources(publisher) ~ queryResources(publisher)

      val http =
        Http().bindAndHandle(api, config.getString("radicaldb.http.interface"), config.getInt("radicaldb.http.port"))

      scala.sys.addShutdownHook {
        http.flatMap(_.unbind()).onComplete { _ =>
          system.terminate()
        }

        Await.result(system.whenTerminated, 60 seconds)
      }
    case Failure(ex) =>
  }

}