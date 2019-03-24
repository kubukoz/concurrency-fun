import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._

import scala.concurrent.duration._
import scala.util.Random
import java.util.concurrent.TimeUnit

object DeferredExample extends IOApp {
  val now = Clock[IO].realTime(TimeUnit.MILLISECONDS).map(_.millis)

  def measure[T](io: IO[T]): IO[(T, FiniteDuration)] =
    (now, io, now).mapN { (before, result, after) =>
      (result, after - before)
    }

  def maxTimeIO(limit: Int) = IO(Random.nextInt(limit - 5)).map(_ + 5).map(_.seconds)

  def racePairKeepLeft[A, B](left: IO[A], right: IO[B]): IO[A] = {
    Deferred[IO, Unit].flatMap { leftCompleted =>
      (left <* leftCompleted.complete(())) <& (right race leftCompleted.get)
    }
  }

  def racePairKeepLeft3[A, B](left: IO[A], right: IO[B]): IO[A] = {
    Concurrent.memoize(left).flatMap { leftM =>
      leftM <& (right race leftM)
    }
  }

  def racePairKeepLeft2[A, B](left: IO[A], right: IO[B]): IO[A] = {
    left
      .racePair(right)
      .bracketCase {
        case Left((left, rightFiber)) => rightFiber.cancel.as(left).uncancelable
        case Right((leftFiber, _)) =>
          leftFiber.join.guaranteeCase {
            case ExitCase.Canceled => leftFiber.cancel
            case _                 => IO.unit
          }
      } {
        case (Left((_, rightFiber)), ExitCase.Canceled) => rightFiber.cancel
        case (Right((leftFiber, _)), ExitCase.Canceled) => leftFiber.cancel
        case _                                          => IO.unit
      }
  }

  def job(wait: IO[Unit]): IO[(FiniteDuration, IO[Unit])] = maxTimeIO(limit = 20).fproduct { maxTime =>
    val decideResult: Boolean => IO[Unit] = {
      case true  => IO(println("Finished in time"))
      case false => IO.raiseError(new Throwable("Waiting took too much time! Aborting everything"))
    }

    IO(println(s"Starting wait, max time is $maxTime")) >>
      measure(wait)
        .map(_._2)
        .map(_ <= maxTime)
        .flatMap(decideResult)
  }

  override def run(args: List[String]): IO[ExitCode] =
    Deferred[IO, Unit].flatMap { stop =>
      val complete = IO(scala.io.StdIn.readLine()) >> stop.complete(())

      List.fill(10)(job(wait = stop.get)).parSequence.flatMap { jobs =>
        val minTime             = jobs.map(_._1).min
        val waitForFirstFailure = IO.sleep(minTime) >> IO(println("No chance of success now"))

        racePairKeepLeft2(jobs.parTraverse(_._2), waitForFirstFailure)
      } &> complete
    }.as(ExitCode.Success)
}
