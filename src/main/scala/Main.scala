import java.io.{File, FileInputStream, InputStream}
import java.nio.file.StandardOpenOption
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Source}

import scala.concurrent.Future
import scala.util.{Success, Try}

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
      // Run the HTTP server
      _ <- Http().bindAndHandle(route, HOST, httpPort)
      _ <- Http().bindAndHandle(route, HOST, httpsPort, connectionContext = httpsConnectionContext)
      _ <- Future.successful{println(s"Listening HTTP  on ${httpPort}...")}
      _ <- Future.successful{println(s"Listening HTTPS on ${httpsPort}...")}
    } yield ()
  }


  /**
    * Http Server's Routing
    */
  def route(implicit materializer: ActorMaterializer): Route = {
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

      // Generate File ID and storeFilePath
      val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()

      // Get a file from client and store it
      // hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities
      withoutSizeLimit {
        extractDataBytes { bytes =>
          val finshiedWriting = bytes.runWith(FileIO.toPath(new File(storeFilePath).toPath, options = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))
          onComplete(finshiedWriting) { ioResult =>
            println(ioResult)
            complete(s"${fileId}\n")
          }
        }
      }
    } ~
    // "Post /" for client-sending a file
    (post & path("multipart") & entity(as[Multipart.FormData])) { formData =>

      val fileIdsSource: Source[String, Any] = formData.parts.mapAsync(1) { bodyPart: BodyPart =>
        // Generate File ID and storeFilePath
        val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()
        // Get data bytes
        val bytes = bodyPart.entity.dataBytes
        for {
          // Store the file
          ioResult <- bytes.runWith(FileIO.toPath(new File(storeFilePath).toPath, options = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))
          _ <- Future.successful {println(s"IOResult: ${ioResult}")}
        } yield fileId
      }

      val fileIdsFut: Future[List[String]] = fileIdsSource.runFold(List.empty[String])((l, s) => l :+ s)


      onComplete(fileIdsFut) {
        case Success(fileIds) =>
          complete(fileIds.mkString("\n"))
        case _ =>
          complete("Upload failed") // TODO Change response
      }

    } ~
    // "Get /xyz" for client-getting the specified file
    (get & path(Remaining)) { fileId =>
      val gettingFilePath = List(File_DB_PATH, fileId).mkString(File.separator)

      val file = new File(gettingFilePath)

      // File exists
      if (file.exists()) {
        complete(
          HttpEntity.fromPath(ContentTypes.NoContentType, file.toPath)
        )
      } else {
        complete(StatusCodes.NotFound, s"File ID '${fileId}' not found\n")
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
