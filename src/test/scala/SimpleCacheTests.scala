import cats.effect._
import cats.effect.implicits._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class SimpleCacheTests extends WordSpec with Matchers {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]               = IO.timer(ExecutionContext.global)

  "SimpleCache" should {
    "complete the call that made a get request" in {
      val prog =
        SimpleCache.create[Unit, Unit](_ => IO.sleep(1.second)).use(_.getOrFetch(()))

      prog.timeout(2.seconds).unsafeRunSync()
    }

    "complete the call that joined a started request" in {
      val prog = Deferred[IO, Unit].flatMap { startedFetch =>
        SimpleCache.create[Unit, Unit](_ => startedFetch.complete(()) >> IO.sleep(1.second)).use { cache =>
          //start first get, wait until fetching started and start another get, wait for both
          cache.getOrFetch(()) &> (startedFetch.get >> cache.getOrFetch(()))
        }
      }

      prog.timeout(2.seconds).unsafeRunSync()
    }

    "run the action only once while waiting in multiple get calls" in {
      val concurrentGets = 5

      val prog = Ref[IO].of(0).flatMap { completions =>
        SimpleCache.create[String, Int](s => completions.update(_ + 1) >> IO.pure(s.length)).use { cache =>
          List.fill(concurrentGets)(cache.getOrFetch("foo bar")).parSequence_
        } >> completions.get.map(_ shouldBe 1)
      }

      prog.timeout(2.seconds).unsafeRunSync()
    }

    "complete a promise after a get was started, even if it's canceled" in {

      val prog = Deferred[IO, Unit].flatMap { fetched =>
        SimpleCache
          .create[Unit, Unit](_ => IO.sleep(1.second) >> fetched.complete(()))
          .use { cache =>
            Deferred[IO, Unit].flatMap { started =>
              (started.complete(()) >> cache.getOrFetch(())).start
                .flatMap(started.get >> _.cancel) >> fetched.get
            }
          }
      }

      prog
        .timeout(2.seconds)
        .unsafeRunSync()
    }
  }

  //better Concurrent.memoize (works with cancelable flatmaps)
  def memoize[F[_], A](f: F[A])(implicit F: Concurrent[F]): F[F[A]] =
    Ref.of[F, Option[Deferred[F, Either[Throwable, A]]]](None).map { ref =>
      Deferred[F, Either[Throwable, A]].flatMap { d =>
        ref
          .modify {
            case None =>
              Some(d) -> f.attempt.flatTap(d.complete).uncancelable.map(F.pure)
            case s @ Some(other) =>
              s -> F.pure(other.get)
          }
          .flatten.uncancelable.flatten
          .rethrow
      }
    }

  "foo" should {
    "timeout" in {
      def cancelAsap[A](ioa: IO[A]): IO[Unit] = Deferred.uncancelable[IO, Unit].flatMap { latch =>
        (latch.complete(()) >> ioa).start.flatMap {
          latch.get >> _.cancel
        }
      }

      val job = IO.sleep(1.second)
      val prog = Concurrent.memoize(job).flatMap { jobMemoized =>
        cancelAsap(jobMemoized) >> jobMemoized
      }

      List.fill(100)(prog.timeout(5.seconds)).parSequence_.unsafeRunSync()
    }
  }
}
