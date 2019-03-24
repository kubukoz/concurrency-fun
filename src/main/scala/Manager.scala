import cats.effect.concurrent.{Deferred, Ref}
import cats.effect._
import cats.implicits._
import cats.effect.implicits._

import scala.concurrent.duration._

trait Manager[F[_]] {
  def safeStart[A](fa: F[A]): F[Unit]
}

object Manager {

  def unbounded[F[_]: Concurrent]: Resource[F, Manager[F]] = {
    val id: F[Unique] = Sync[F].delay(new Unique)

    Resource {
      Ref[F].of(Map.empty[Unique, Fiber[F, Unit]]).map { tasks =>
        val cancelAllTasks = tasks.get.flatMap {
          _.toList.traverse_ {
            case (_, task) =>
              task.cancel: F[Unit]
          }
        }

        new Manager[F] {
          override def safeStart[A](fa: F[A]): F[Unit] =
            Deferred[F, Unit].flatMap { isManaged =>
              val markManaged = isManaged.complete(())

              id.flatMap { taskId =>
                val unregister = tasks.update(_ - taskId)
                val runJob     = isManaged.get >> fa.attempt >> unregister

                runJob.start.flatMap { fiber =>
                  val register = tasks.update(_ + (taskId -> fiber.void))

                  register >> markManaged
                }.uncancelable
              }
            }
        } -> cancelAllTasks
      }
    }
  }

  private class Unique
}

//object ManagerDemo extends IOApp {
//  override def run(args: List[String]): IO[ExitCode] =
//    Manager
//      .unbounded[IO]
//      .use { man =>
//        man.safeStart(IO.sleep(10.seconds)) >> man.safeStart(IO.sleep(3.seconds)) >> IO.sleep(5.seconds) >> IO(
//          println("done"))
//      }
//      .as(ExitCode.Success)
//}
