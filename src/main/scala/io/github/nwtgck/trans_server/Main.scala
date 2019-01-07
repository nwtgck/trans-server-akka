package io.github.nwtgck.trans_server

import java.io.File

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.stream.ActorMaterializer
import scopt.OptionParser
import slick.driver.H2Driver.api._

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by Ryo on 2017/04/23.
  */
object Main {

  // Command line option
  case class TransOption(httpPort: Int,
                         httpsPortOpt: Option[Int],
                         dbUrlOpt: Option[String],
                         enableTopPageHttpsRedirect: Boolean)

  // Option parser
  val optParser: OptionParser[TransOption] = new scopt.OptionParser[TransOption]("") {
    opt[Int]("http-port") action {(v, option) =>
      option.copy(httpPort = v)
    } text "HTTP port"

    opt[Int]("https-port") action {(v, option) =>
      option.copy(httpsPortOpt = Some(v))
    } text "HTTPS port (server does not use HTTPS if not specified)"

    opt[String]("db-url") action {(v, option) =>
      option.copy(dbUrlOpt = Some(v))
    } text "Database URL (e.g. 'jdbc:h2:tcp://localhost/~/h2db/trans')"

    opt[Unit]("top-page-https-redirect") action {(_, option) =>
      option.copy(enableTopPageHttpsRedirect = true)
    } text "Enable Top-page HTTPS redirect"
  }


  def main(args: Array[String]): Unit = {

    // Import Settings
    import Setting._

    // Parse option
    optParser.parse(args, TransOption(
      httpPort     = DEFAULT_HTTP_PORT,
      httpsPortOpt = None,
      dbUrlOpt     = None,
      enableTopPageHttpsRedirect = false
    )) match {
      case Some(option) =>

        implicit val system: ActorSystem = ActorSystem("trans-server")
        implicit val materializer: ActorMaterializer = ActorMaterializer()

        // Logger
        val logger = Logging.getLogger(system, this)

        // Create db instance
        val db = option.dbUrlOpt match {
          case Some(dbUrl) =>
            // Create by URL
            Database.forURL(dbUrl, driver="org.h2.Driver")
          case None =>
            // Create a file-base db
          Database.forConfig("h2file-trans")
        }

        import concurrent.ExecutionContext.Implicits.global

        // Create core system of trans server
        val core: Core = new Core(db, fileDbPath = Setting.File_DB_PATH, option.enableTopPageHttpsRedirect)


        // Create File DB if non
        val fileDbDir = new File(File_DB_PATH)
        if (!fileDbDir.exists()) {
          fileDbDir.mkdirs()
        }

        // Scheduling for cleaning up dead files
        // (from: https://doc.akka.io/docs/akka/2.5/scheduler.html?language=scala)
        system.scheduler.schedule(CleanupDuration, CleanupDuration, new Runnable {
          override def run(): Unit = {
            // Clean up dead files
            core.removeDeadFiles()
          }
        })

        // Get http port
        val httpPort: Int = option.httpPort

        (for {
          // Create a table if not exist
          _ <- Tables.createTablesIfNotExist(db)

          // Run the HTTP server
          _ <- Http().bindAndHandle(core.route, HOST, httpPort).map(_ =>
            logger.info(s"Listening HTTP  on ${httpPort}...")
          )
          _ <- {
            option.httpsPortOpt match {
              case Some(httpsPort) if new File(Setting.KEY_STORE_PATH).exists() =>
                // Generate a HttpsConnectionContext
                val httpsConnectionContext: HttpsConnectionContext = Util.generateHttpsConnectionContext(
                  Setting.KEY_STORE_PASSWORD,
                  Setting.KEY_STORE_PATH
                )
                // Run the HTTPS server
                Http().bindAndHandle(core.route, HOST, httpsPort, connectionContext = httpsConnectionContext).map(_ =>
                  logger.info(s"Listening HTTPS on ${httpsPort}...")
                )
              case _ =>
                Future.successful()
            }
          }
        } yield ()).onComplete{
          case Success(_) =>
            logger.info("Running server!")
          case Failure(e) =>
            logger.error(s"Error in running server\n${Util.getStackTraceString(e)}", e)
        }


      case None =>
        // Command line parser error
        sys.exit(1)
    }
  }

}
