import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import cats._
import fs2._

object checks {

  trait CacheAccess[F[_], A] {
    def get: F[A]
    def set: Stream[F, A]
  }

  /**
    * The returned action is a getter for the cached value. If there is no value, setters will semantically block until there is one.
    * The returned stream will, when started, refresh the cached value.
    *
    * A value will be set every time `refreshSignals` provides one.
    * Note that if `refreshSignals` terminates or fails, it won't be restarted automatically and there will be no more updates.
    * */
  def cachedRefreshedLazy[F[_]: Concurrent, A](refreshSignals: Stream[F, A]): F[CacheAccess[F, A]] = {
    import data._

    Deferred[F, A].flatMap { initialPromise =>
      Ref[F].of[CacheState[F, A]](CacheState.WaitingForFirst()).map { ref =>
        val getStateAction: F[A] = ref.get.flatMap {
          case CacheState.WaitingForFirst() => initialPromise.get
          case CacheState.Known(a)          => a.pure[F]
        }

        def setStateAction(a: A): F[Unit] = {
          val newState = CacheState.Known[F, A](a)
          ref.modify {
            case CacheState.WaitingForFirst() => newState -> initialPromise.complete(a)
            case CacheState.Known(_)          => newState -> Applicative[F].unit
          }.flatten
        }

        new CacheAccess[F, A] {
          val get: F[A]         = getStateAction
          val set: Stream[F, A] = refreshSignals.evalTap(setStateAction)
        }
      }
    }
  }

  private object data {
    sealed trait CacheState[F[_], A] extends Product with Serializable

    object CacheState {
      case class WaitingForFirst[F[_], A]() extends CacheState[F, A]
      case class Known[F[_], A](a: A)       extends CacheState[F, A]
    }
  }
}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

object CachedRefreshed extends IOApp {

  val blockingEcResource: Resource[IO, ExecutionContext] =
    Resource
      .make(IO(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))(ec => IO(ec.shutdown()))
      .widen

  def showCancel[A](tag: String)(ioa: IO[A]): IO[A] =
    IO.unit.bracketCase { _ =>
      ioa
    } { case (_, ec) => IO(println(s"$tag Finished with case $ec")) }

  def addLogging[A](ioa: IO[A]): IO[A] =
    IO(println("Getting state...")) >> ioa.flatTap { s =>
      IO(println("Got state: " + s))
    }

  def readLineOrExit(blockingEc: ExecutionContext, exit: Deferred[IO, Unit]) =
    contextShift.evalOn(blockingEc)(IO(scala.io.StdIn.readLine())).flatMap {
      case "exit" => exit.complete(()) >> IO.sleep(1.second)
      case _      => IO.unit
    }

  def run(args: List[String]): IO[ExitCode] =
    blockingEcResource.use { blockingEc =>
      Deferred[IO, Unit].flatMap { exit =>
        val refresh = IO(println("Refreshing state!")) >> IO(scala.util.Random.nextInt())

        val heartbeat = Stream.sleep(1.second).repeat

        checks
          .cachedRefreshedLazy[IO, Int](Stream.repeatEval(readLineOrExit(blockingEc, exit)).evalMap(_ => refresh))
          .flatMap { cache =>
            IO(println("Using!")) >>
              Stream
                .iterate(0)(_ + 1)
                .evalMap(i => showCancel(s"Check no. $i")(addLogging(cache.get)))
                .zipLeft(heartbeat)
                .concurrently(cache.set)
                .compile
                .drain race exit.get
          }
      }
    }.as(ExitCode.Success)
}
