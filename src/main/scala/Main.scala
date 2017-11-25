import java.io.{File, FileInputStream, InputStream}
import java.nio.file.StandardOpenOption
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, IOResult}
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}
import slick.driver.H2Driver.api._

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Created by Ryo on 2017/04/23.
  */
object Main {


  def main(args: Array[String]): Unit = {

    // Import Settings
    import Setting._

    // Decide ports
    val (httpPort: Int, httpsPort: Int) = args match {
      case Array(httpPortStr, httpsPortStr) =>
        (
          Try(httpPortStr.toInt).getOrElse(DEFAULT_HTTP_PORT),
          Try(httpsPortStr.toInt).getOrElse(DEFAULT_HTTPS_PORT)
        )
      case _ =>
        (DEFAULT_HTTP_PORT, DEFAULT_HTTPS_PORT)
    }

//     Create a memory-base db
//    val db = Database.forConfig("h2mem-trans")

    // Create a file-base db
    val db = Database.forConfig("h2file-trans")

    implicit val system = ActorSystem("trans-server-actor-system")
    implicit val materializer = ActorMaterializer()
    import concurrent.ExecutionContext.Implicits.global


    // Create File DB if non
    {
      val fileDbDir = new File(File_DB_PATH)
      if (!fileDbDir.exists()) {
        fileDbDir.mkdirs()
      }
    }

    // Generate a HttpsConnectionContext
    val httpsConnectionContext: HttpsConnectionContext = generateHttpsConnectionContext()

    for {
      // Create a table if not exist
      _ <- Tables.createTablesIfNotExist(db)

      // Run the HTTP server
      _ <- Http().bindAndHandle(route(db), HOST, httpPort)
      _ <- Http().bindAndHandle(route(db), HOST, httpsPort, connectionContext = httpsConnectionContext)
      _ <- Future.successful{println(s"Listening HTTP  on ${httpPort}...")}
      _ <- Future.successful{println(s"Listening HTTPS on ${httpsPort}...")}
    } yield ()
  }

  def storeBytes(db: Database, byteSource: Source[ByteString, Any], duration: FiniteDuration)(implicit ec: ExecutionContext, materializer: ActorMaterializer): Future[String] = {

    import TimestampUtil.RichTimestampImplicit._

    // Generate File ID and storeFilePath
    val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()

    // Adjust duration (big duration is not good)
    val adjustedDuration: FiniteDuration = duration.min(Setting.MaxStoreDuration)
    println(s"adjustedDuration: ${adjustedDuration}")

    for {
      // Store the file
      ioResult   <- byteSource.runWith(FileIO.toPath(new File(storeFilePath).toPath, options = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))
      // Create file store object
      fileStore = Tables.FileStore(fileId=fileId, createdAt=TimestampUtil.now(), deadline = TimestampUtil.now + adjustedDuration)
      // Store to the database
      // TODO Check fileId collision (but if collision happens database occurs an error because of primary key)
      _          <- db.run(Tables.allFileStores += fileStore)
      fileStores <- db.run(Tables.allFileStores.result)
      _ <- Future.successful {
        println(s"IOResult: ${ioResult}")
      }
    } yield fileId
  }


  /**
    * Convert a string to duration [sec]
    * @param str
    * @return duration [sec]
    */
  private def strToDurationSecOpt(str: String): Option[Int] = {
    val reg = """^(\d+)([dhms])$""".r
    str match {
      case reg(lengthStr, unitStr) =>
        Try {
          val length: Int = lengthStr.toInt
          unitStr match {
            case "s" => length
            case "m" => length * 60
            case "h" => length * 60 * 60
            case "d" => length * 60 * 60 * 24
          }
        }.toOption
      case _ => None
    }
  }

  /**
    * Http Server's Routing
    */
  def route(db: Database)(implicit materializer: ActorMaterializer): Route = {
    // for routing DSL
    import akka.http.scaladsl.server.Directives._
    // for using XML
    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
    // for using settings
    import Setting._
    // for Futures
    import concurrent.ExecutionContext.Implicits.global


    // "Get /" for confirming whether the server is running
    (get & pathSingleSlash) {
//      complete(<h1>trans server is runnning</h1>)
      complete {
        val indexFile = new File("public_html/index.html")
        HttpEntity.fromPath(ContentTypes.`text/html(UTF-8)`, indexFile.toPath)
      }
    } ~
    // "Post /" for client-sending a file
    (post & pathSingleSlash) {
      parameter('duration.?) { (durationStrOpt: Option[String]) =>

        println(s"durationStrOpt: ${durationStrOpt}")

        // Get duration
        val duration: FiniteDuration =
          (for{
            durationStr <- durationStrOpt
            durationSec <- strToDurationSecOpt(durationStr)
          } yield durationSec.seconds)
          .getOrElse(DefaultStoreDuration)
        println(s"Duration: ${duration}")


        // Generate File ID and storeFilePath
        val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()

        // Get a file from client and store it
        // hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities
        withoutSizeLimit {
          extractDataBytes { bytes =>
            // Store bytes to DB
            val storeFut: Future[String] = storeBytes(db, bytes, duration)

            onComplete(storeFut){
              case Success(fileId) =>
                complete(s"${fileId}\n")
              case _ =>
                complete("Upload failed") // TODO Change response
            }
          }
        }
      }
    } ~
    // "Post /" for client-sending a file
    (post & path("multipart") & entity(as[Multipart.FormData])) { formData =>

      parameter('duration.?) { (durationStrOpt: Option[String]) =>

        // Get duration
        val duration: FiniteDuration =
          (for{
            durationStr <- durationStrOpt
            durationSec <- strToDurationSecOpt(durationStr)
          } yield durationSec.seconds)
            .getOrElse(DefaultStoreDuration)
        println(s"Duration: ${duration}")

        val fileIdsSource: Source[String, Any] = formData.parts.mapAsync(1) { bodyPart: BodyPart =>
          // Generate File ID and storeFilePath
          val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()
          // Get data bytes
          val bytes: Source[ByteString, Any] = bodyPart.entity.dataBytes

          // Store bytes to DB
          storeBytes(db, bytes, duration)
        }

        val fileIdsFut: Future[List[String]] = fileIdsSource.runFold(List.empty[String])((l, s) => l :+ s)


        onComplete(fileIdsFut) {
          case Success(fileIds) =>
            complete(fileIds.mkString("\n"))
          case _ =>
            complete("Upload failed") // TODO Change response
        }
      }
    } ~
    // "Get /xyz" for client-getting the specified file
    (get & path(Remaining)) { fileId =>
      val gettingFilePath = List(File_DB_PATH, fileId).mkString(File.separator)

      val file = new File(gettingFilePath)


      // Check existence of valid(=not expired) file store
      val existsFileStoreFut: Future[Boolean] =  db.run(
        Tables.allFileStores
          .filter(fileStore => fileStore.fileId === fileId && fileStore.deadline >= new java.sql.Timestamp(System.currentTimeMillis()) )
          .exists
          .result
      )
      onComplete(existsFileStoreFut){

        // If file is alive (not expired and exist)
        case Success(true) =>
          // File exists
          if (file.exists()) {
            withRangeSupport { // Range support for `pget`
              complete(
                HttpEntity.fromPath(ContentTypes.NoContentType, file.toPath)
              )
            }
          } else {
            complete(StatusCodes.NotFound, s"File ID '${fileId}' is not found\n")
          }
        case _ =>
          complete(StatusCodes.NotFound, s"File ID '${fileId}' is not found or expired\n")
      }

    }
  }

  /**
    * Generate non-duplicated File ID and store path
    * @return
    */
  def generateNoDuplicatedFiledIdAndStorePath(): (String, String) = {
    var fileId: String = null
    var storeFilePath: String = null

    // Generate File ID and storeFilePath
    do {
      fileId = generateRandomFileId()
      storeFilePath = List(Setting.File_DB_PATH, fileId).mkString(File.separator)
    } while (new File(storeFilePath).exists())
    return (fileId, storeFilePath)
  }

  /**
    * Generate random File ID
    * @return Random File ID
    */
  def generateRandomFileId(): String = {
    // 1 ~ 9 + 'a' ~ 'z'
    val candidates: Seq[String] = ((0 to 9) ++ ('a' to 'z')).map(_.toString)
    val r = scala.util.Random
    val i = (1 to 3).map{_ =>
      val idx = r.nextInt(candidates.length)
      candidates(idx)
    }
    i.mkString
  }


  /**
    * Generate a HttpsConnectionContext
    *
    * hint from: http://doc.akka.io/docs/akka-http/current/scala/http/server-side-https-support.html
    * @return
    */
  def generateHttpsConnectionContext(): HttpsConnectionContext = {
    import Setting._

    // Manual HTTPS configuration
    val password: Array[Char] = KEY_STORE_PASSWORD.toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks: KeyStore = KeyStore.getInstance("jks")
    val keystore: InputStream = new FileInputStream(KEY_STORE_PATH)

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val httpsConnectionContext: HttpsConnectionContext = ConnectionContext.https(sslContext)

    httpsConnectionContext
  }
}
