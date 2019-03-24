import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import cats._
import fs2._
import fs2.concurrent._

trait UberCache[K, V] {
  def clear(k: K): IO[Unit]
  def getOrFetch(k: K): IO[V]
}

object UberCache {

  def create[K, V](fetch: K => IO[V])(implicit F: Concurrent[IO]): Resource[IO, UberCache[K, V]] = Resource.suspend {
    Ref[IO].of(Map.empty[K, State[V]]).flatMap { ref =>
      Queue.bounded[IO, K](maxSize = 100).map { q =>
        val stream: Stream[IO, Unit] = q.dequeue.map { k =>
          fetch(k).flatMap { v =>
            ref.update(_ + (k -> Found(v)))
          }
        }.map(Stream.eval).parJoinUnbounded

        val cache = new UberCache[K, V] {
          def getOrFetch(k: K): IO[V] =
            //this promise is only used in the None state. Allocating here because we can't do it later.
            Deferred[IO, V].flatMap { newPromise =>
              ref.modify { state =>
                state.get(k) match {
                  case None                => (state + (k -> Waiting(newPromise)), q.enqueue1(k) >> newPromise.get)
                  case Some(Waiting(deff)) => (state, deff.get)
                  case Some(Found(v))      => (state, v.pure[IO])
                }
              }.flatten
            }

          def clear(k: K): IO[Unit] = ref.update { state =>
            state.get(k) match {
              case Some(Found(_)) => state - k
              case _              => state
            }
          }
        }

        Resource
          .make(F.start(stream.compile.drain))(_.cancel)
          .as(cache)
      }
    }
  }

  sealed trait State[V]
  case class Waiting[V](promise: Deferred[IO, V]) extends State[V]
  case class Found[V](value: V)                   extends State[V]
}
