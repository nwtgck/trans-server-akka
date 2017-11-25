import java.io.File
import java.nio.file.StandardOpenOption

import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import slick.driver.H2Driver.api._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}
import Tables.OriginalTypeImplicits._


class Core(db: Database, fileDbPath: String){
  def storeBytes(byteSource: Source[ByteString, Any], duration: FiniteDuration, nGetLimitOpt: Option[Int])(implicit ec: ExecutionContext, materializer: ActorMaterializer): Future[FileId] = {

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
      fileStore = FileStore(fileId=fileId, storePath=storeFilePath, createdAt=TimestampUtil.now(), deadline = TimestampUtil.now + adjustedDuration, nGetLimitOpt = nGetLimitOpt)
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
  def route(implicit materializer: ActorMaterializer): Route = {
    // for routing DSL
    import akka.http.scaladsl.server.Directives._
    // for using XML
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
        parameter('duration.?, 'times.?) { (durationStrOpt: Option[String], nGetLimitStrOpt: Option[String]) =>

          println(s"durationStrOpt: ${durationStrOpt}")

          // Get duration
          val duration: FiniteDuration =
            (for{
              durationStr <- durationStrOpt
              durationSec <- strToDurationSecOpt(durationStr)
            } yield durationSec.seconds)
              .getOrElse(DefaultStoreDuration)
          println(s"Duration: ${duration}")

          // Generate nGetLimitOpt
          val nGetLimitOpt: Option[Int] = for {
            nGetLimitStr <- nGetLimitStrOpt
            nGetLimit    <- Try(nGetLimitStr.toInt).toOption
          } yield nGetLimit
          println(s"nGetLimitOpt: ${nGetLimitOpt}")

          // Generate File ID and storeFilePath
          val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()

          // Get a file from client and store it
          // hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities
          withoutSizeLimit {
            extractDataBytes { bytes =>
              // Store bytes to DB
              val storeFut: Future[FileId] = storeBytes(bytes, duration, nGetLimitOpt)

              onComplete(storeFut){
                case Success(fileId) =>
                  complete(s"${fileId.value}\n")
                case f =>
                  println(f)
                  complete("Upload failed") // TODO Change response
              }
            }
          }
        }
      } ~
      // "Post /" for client-sending a file
      (post & path("multipart") & entity(as[Multipart.FormData])) { formData =>

        parameter('duration.?, 'times.?) { (durationStrOpt: Option[String], nGetLimitStrOpt: Option[String]) =>

          // Get duration
          val duration: FiniteDuration =
            (for{
              durationStr <- durationStrOpt
              durationSec <- strToDurationSecOpt(durationStr)
            } yield durationSec.seconds)
              .getOrElse(DefaultStoreDuration)
          println(s"Duration: ${duration}")

          // Generate nGetLimitOpt
          val nGetLimitOpt: Option[Int] = for {
            nGetLimitStr <- nGetLimitStrOpt
            nGetLimit    <- Try(nGetLimitStr.toInt).toOption
          } yield nGetLimit
          println(s"nGetLimitOpt: ${nGetLimitOpt}")

          val fileIdsSource: Source[FileId, Any] = formData.parts.mapAsync(1) { bodyPart: BodyPart =>
            // Generate File ID and storeFilePath
            val (fileId, storeFilePath) = generateNoDuplicatedFiledIdAndStorePath()
            // Get data bytes
            val bytes: Source[ByteString, Any] = bodyPart.entity.dataBytes

            // Store bytes to DB
            storeBytes(bytes, duration, nGetLimitOpt = None) // TODO nGetLimitOpt should be impled
          }

          val fileIdsFut: Future[List[FileId]] = fileIdsSource.runFold(List.empty[FileId])((l, s) => l :+ s)


          onComplete(fileIdsFut) {
            case Success(fileIds) =>
              complete(fileIds.map(_.value).mkString("\n"))
            case _ =>
              complete("Upload failed") // TODO Change response
          }
        }
      } ~
      // "Get /xyz" for client-getting the specified file
      (get & path(Remaining)) { fileIdStr =>

        // Generate file ID instance
        val fileId: FileId = FileId(fileIdStr)

        // Check existence of valid(=not expired) file store
        val existsFileStoreFut: Future[Option[FileStore]] =  db.run(
          Tables.allFileStores
            .filter(fileStore =>  fileStore.fileId === fileId && isAliveFileStore(fileStore))
            .result
            .headOption
        )
        onComplete(existsFileStoreFut){

          // If file is alive (not expired and exist)
          case Success(Some(fileStore)) =>
            // Generate file instance
            val file = new File(fileStore.storePath)
            // File exists
            if (file.exists()) {
              withRangeSupport { // Range support for `pget`
                // Create decrement-nGetLimit-DBIO (TODO decrementing nGetLimit may need mutual execution)
                val decrementDbio = for {
                  // Find file store
                  fileStore <- Tables.allFileStores.filter(_.fileId === fileId).result.head
                  // Decrement nGetLimit
                  _         <- Tables.allFileStores.filter(_.fileId === fileId).map(_.nGetLimitOpt).update(fileStore.nGetLimitOpt.map(_ - 1))
                } yield ()

                onComplete(db.run(decrementDbio)){
                  case Success(_) =>
                    complete(
                      HttpEntity.fromPath(ContentTypes.NoContentType, file.toPath)
                    )
                  case _ =>
                    complete(StatusCodes.InternalServerError, s"Server error in decrement nGetLimit\n")
                }
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
    * File store is alive for Slick query
    */
  private def isAliveFileStore(fileStore: Tables.FileStores): Rep[Option[Boolean]] =
      fileStore.deadline >= new java.sql.Timestamp(System.currentTimeMillis()) &&
      (fileStore.nGetLimitOpt.isEmpty || fileStore.nGetLimitOpt > 0)


  /**
    * Cleanup dead files
    * @param ec
    * @return
    */
  def removeDeadFiles()(implicit ec: ExecutionContext): Future[Unit] = {

    // Generate deadFiles query
    val deadFilesQuery =
      Tables.allFileStores
        .filter(fileStore => !isAliveFileStore(fileStore))

    for{
      // Get dead files
      deadFiles <- db.run(deadFilesQuery.result)
      // Delete dead fileStore in DB
      _         <- db.run(deadFilesQuery.delete)
      // Delete files in file DB
      _         <- Future{
        deadFiles.foreach{file =>
          try {
            new File(file.storePath).delete()
          } catch {case e: Throwable =>
            println(e)
          }
        }
      }

      // Print for debugging
      _         <- Future{println(s"Cleanup ${deadFiles.size} dead files")}
    } yield ()
  }

  /**
    * Generate non-duplicated File ID and store path
    * @return
    */
  def generateNoDuplicatedFiledIdAndStorePath(): (FileId, String) = {
    var fileIdStr: String = null
    var storeFilePath: String = null

    // Generate File ID and storeFilePath
    do {
      fileIdStr = generateRandomFileId()
      storeFilePath = List(fileDbPath, fileIdStr).mkString(File.separator)
    } while (new File(storeFilePath).exists())
    return (FileId(fileIdStr), storeFilePath)
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
}