import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import cats.effect.implicits._
import fs2.concurrent.Queue
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import scala.concurrent.duration._

class Actor[F[_]: Concurrent, +State, -Input, Output](q: Queue[F, (Input, Deferred[F, Either[Throwable, Output]])]) {

  private def enqueueWithPromise(input: Input): F[Deferred[F, Either[Throwable, Output]]] =
    Deferred[F, Either[Throwable, Output]].flatTap { promise =>
      q.enqueue1(input -> promise)
    }

  def tell(input: Input): F[Unit] = enqueueWithPromise(input).void

  def ask(input: Input): F[Output] = enqueueWithPromise(input).flatMap(_.get.rethrow)
}

object Actor {

  //rename to Behavior?
  trait Receive[F[_], State, Input, Output] {

    def receive(
      state: State,
      input: Input,
      self: Actor[F, State, Input, Output]
    ): F[(State, Output, Receive[F, State, Input, Output] => Receive[F, State, Input, Output])]
  }

  def make[F[_]: Concurrent, State, Input, Output](
    initialReceive: Receive[F, State, Input, Output]
  )(initialState: State): Resource[F, Actor[F, State, Input, Output]] = Resource {
    Queue.bounded[F, (Input, Deferred[F, Either[Throwable, Output]])](1000).flatMap { q =>
      //todo these two can be flattened as they are updated and selected together
      Ref[F].of(initialState).flatMap { state =>
        Ref[F].of(initialReceive).flatMap { receiveRef =>
          val actor = new Actor[F, State, Input, Output](q)

          val processInbox = q.dequeue.evalMap {
            case (input, promise) =>
              (state.get, receiveRef.get).mapN { (currentState, receive) =>
                receive.receive(currentState, input, actor)
              }.flatten.flatMap {
                case (newState, output, modifyReceive) =>
                  state.set(newState) >>
                    receiveRef.update(modifyReceive).as(output)
              }.attempt.flatMap(promise.complete)
          }.compile.drain

          processInbox.start.map { fiber =>
            actor -> (fiber.cancel: F[Unit])
          }
        }
      }
    }
  }
}

/* object ActorDemo extends IOApp {
  case class Ping(replyTo: Actor[IO, _, Pong, _])

  case class Pong(replyTo: Actor[IO, _, Ping, _])

  val pingR = Actor.make {
    val receive1: Actor.Receive[IO, Int, Ping, Unit] = {
      case (state, Ping(whom), self) =>
        IO(println("Ping " + "*" * state)) >>
          whom.tell(Pong(self)) >>
          IO.pure((state + 1, (), identity))
    }
    receive1

  }(initialState = 0)

  val pongR = Actor.make {
    val receive1: Actor.Receive[IO, Int, Pong, Unit] = {
      case (state, Pong(whom), self) =>
        IO(println("Pong " + "*" * state)) >>
          whom.tell(Ping(self)) >>
          IO.pure((state + 1, (), identity))
    }
    receive1

  }(initialState = 0)

  val changingBehavior = Actor.make {
    lazy val receive1: Actor.Receive[IO, Int, Int, Int] = { (state, incrementBy, _) =>
      val changeBehavior: IO[Actor.Receive[IO, Int, Int, Int] => Actor.Receive[IO, Int, Int, Int]] =
        if (state < 10)
          IO.pure(identity)
        else
          IO.pure(_ => receive2)

      changeBehavior.map(change => (state + incrementBy, state, change))
    }

    lazy val receive2: Actor.Receive[IO, Int, Int, Int] = {
      case (state, _, _) =>
        IO.pure((0, state, _ => receive1))
    }

    receive1
  }(initialState = 0)

  def prog(finish: IO[Unit]) = (pingR, pongR).tupled.use {
    case (ping, pong) =>
      ping.tell(Ping(pong)) >> finish
  }

  override def run(args: List[String]): IO[ExitCode] =
    Deferred[IO, Unit].flatMap { promise =>
      val end = IO(scala.io.StdIn.readLine()) >> IO(println("Requested shutdown")) >> promise.complete(())

      end race List
        .fill(100 * 1000)(prog(promise.get))
        .parSequence_
    }.as(ExitCode.Success)
//     a1.use { actor =>
//       fs2.Stream
//         .emits(1 to 100000)
//         .covary[IO]
//         .as(fs2.Stream.eval(actor.ask(1)))
//         .flatten
//         .compile
//         .lastOrError
//         .flatMap { last =>
//           IO(println(s"Last result: $last"))
//         }
//     }.as(ExitCode.Success)
}
 */
