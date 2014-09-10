package scalaz
package netty

import concurrent._
import stream._
import syntax.monad._

import scodec.bits._

import scala.concurrent.duration._

import org.specs2.mutable._
import org.specs2.time.NoTimeConversions
import org.scalacheck._

import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ThreadFactory}

object NettySpecs extends Specification with NoTimeConversions {

  val scheduler = {
    Executors.newScheduledThreadPool(4, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName("scheduled-task-thread")
        t
      }
    })
  }

  "netty" should {
    "round trip some simple data" in {
      val addr = new InetSocketAddress("localhost", 9090)

      val server = Netty server addr take 1 flatMap {
        case (_, incoming) => {
          incoming flatMap { exchange =>
            exchange.read take 1 to exchange.write drain
          }
        }
      }

      val client = Netty connect addr flatMap { exchange =>
        val data = ByteVector(12, 42, 1)

        // type annotation required because of a bug in Scala 2.10.4
        val initiate = (Process(data): Process[Task, ByteVector]) to exchange.write

        val check = for {
          results <- exchange.read.runLog

          _ <- Task delay {
            results must haveSize(1)
            results must contain(data)
          }
        } yield ()

        Process.eval(initiate.run >> check).drain
      }

      val delay = Process.sleep(200 millis)(Strategy.DefaultStrategy, scheduler)
      Nondeterminism[Task].both(Task fork server.run, Task fork (delay fby client).run).run

      ok
    }

    "terminate a client process with an error if connection failed" in {
      val addr = new InetSocketAddress("localhost", 51235)         // hopefully no one is using this port...

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.run

      result must beLike {
        case -\/(_) => ok
      }
    }
  }
}
