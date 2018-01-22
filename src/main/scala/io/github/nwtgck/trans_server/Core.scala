package io.github.nwtgck.trans_server

import java.io.File
import java.nio.file.StandardOpenOption
import javax.crypto.Cipher

import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Broadcast, Compression, FileIO, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape, IOResult}
import akka.util.ByteString
import io.github.nwtgck.trans_server.Tables.OriginalTypeImplicits._
import slick.driver.H2Driver.api._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}


/**
  * Available GET parameters
  * @param duration
  * @param nGetLimitOpt
  * @param idLengthOpt
  * @param isDeletable
  * @param deleteKeyOpt
  */
private [this] case class GetParams(duration     : FiniteDuration,
                                    nGetLimitOpt : Option[Int],
                                    idLengthOpt  : Option[Int],
                                    isDeletable  : Boolean,
                                    deleteKeyOpt : Option[String])

class Core(db: Database, fileDbPath: String){


  // Secure random generator
  // (from: https://qiita.com/suin/items/bfff121c8481990e1507)
  private val secureRandom: Random = new Random(new java.security.SecureRandom())


  def storeBytes(byteSource: Source[ByteString, Any], duration: FiniteDuration, nGetLimitOpt: Option[Int], idLengthOpt: Option[Int], isDeletable: Boolean, deleteKeyOpt: Option[String])(implicit ec: ExecutionContext, materializer: ActorMaterializer): Future[FileId] = {

    if(false) {// NOTE: if-false outed
      require(
        !isDeletable && deleteKeyOpt.isEmpty || // deleteKeyOpt should be None if not isDeletable
        isDeletable                             // deleteKeyOpt can be None or Some() if isDeletable
      )
    }

    import TimestampUtil.RichTimestampImplicit._

    // Get ID length
    val idLength: Int =
      idLengthOpt
        .getOrElse(Setting.DefaultIdLength)
        .max(Setting.MinIdLength)
        .min(Setting.MaxIdLength)
    println(s"idLength: ${idLength}")

    // Generate File ID and storeFilePath
    generateNoDuplicatedFiledIdAndStorePathOpt(idLength) match {
      case Some((fileId, storeFilePath)) => {
        // Adjust duration (big duration is not good)
        val adjustedDuration: FiniteDuration = duration.min(Setting.MaxStoreDuration)
        println(s"adjustedDuration: ${adjustedDuration}")

        println(s"isDeletable: ${isDeletable}")

        // Generate hashed delete key
        val hashedDeleteKeyOpt: Option[String] =
          if(isDeletable)
            deleteKeyOpt.map(key => Util.generateHashedKey1(key, Setting.KeySalt))
          else
            None


        // Create a file-store graph
        val storeGraph: RunnableGraph[Future[(IOResult, Long)]] = {

          // Store file
          val fileStoreSink: Sink[ByteString, Future[IOResult]] =
            FileIO.toPath(new File(storeFilePath).toPath, options = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE))

          // Calc length of ByteString
          val lengthSink   : Sink[ByteString, Future[Long]] =
            Sink.fold(0l)(_ + _.length)

          RunnableGraph.fromGraph(GraphDSL.create(fileStoreSink, lengthSink)(_.zip(_)){implicit builder => (fileStoreSink, lengthSink) =>
            import GraphDSL.Implicits._
            val bcast = builder.add(Broadcast[ByteString](2))

            // * compress ~> encrypt ~> store
            // * calc length
            byteSource ~> bcast ~> Compression.gzip ~> CipherFlow.flow(genEncryptCipher()) ~> fileStoreSink
                          bcast ~> lengthSink
            ClosedShape
          })
        }


        for {
          // Store a file and get content length
          (ioResult, rawLength) <- storeGraph.run()

          // Create file store object
          fileStore = FileStore(
            fileId             = fileId,
            storePath          = storeFilePath,
            rawLength          = rawLength,
            createdAt          = TimestampUtil.now(),
            deadline           = TimestampUtil.now + adjustedDuration,
            nGetLimitOpt       = nGetLimitOpt,
            isDeletable        = isDeletable,
            hashedDeleteKeyOpt = hashedDeleteKeyOpt
          )
          // Store to the database
          // TODO Check fileId collision (but if collision happens database occurs an error because of primary key)
          _          <- db.run(Tables.allFileStores += fileStore)
          fileStores <- db.run(Tables.allFileStores.result)
          _ <- Future.successful {
            println(s"IOResult: ${ioResult}")
            println(s"rawLength: ${rawLength}")
          }
        } yield fileId
      }
      case _ =>
        Future.failed(
          new FileIdGenFailedException(s"Length=${idLength} of File ID might run out")
        )
    }


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
    // for Futures
    import concurrent.ExecutionContext.Implicits.global

    get {
      // "Get /" for confirming whether the server is running
      pathSingleSlash {
        getFromResource("index.html") // (from: https://doc.akka.io/docs/akka-http/current/scala/http/routing-dsl/directives/file-and-resource-directives/getFromResource.html)
      } ~
      // Version routing
      path(Setting.GetRouteName.Version) {
        // Response server's version
        complete(s"${BuildInfo.version}\n")
      } ~
      path(Setting.GetRouteName.Help) {
        extractUri {uri =>
          // NOTE: uri.authority contains both host and port
          val urlStr: String = s"${uri.scheme}://${uri.authority}"

          complete( // TODO Should(?) move text content somewhere
            s"""|Help for trans (${BuildInfo.version})
                |(Repository: https://github.com/nwtgck/trans-server-akka)
                |
                |====== wget =====
                |# Send  : wget -q -O - https://hogehoge.io --post-file=test.txt
                |# Get   : wget https://hogehoge.io/a3h
                |# Delete: wget --method=DELETE ${urlStr}/a3h
                |('a3h' is File ID of test.txt)
                |
                |===== curl ======
                |# Send  : curl ${urlStr} --data-binary @test.txt
                |# Get   : curl https://hogehoge.io/a3h > mytest.txt
                |# Delete: curl -X DELETE https://hogehoge.io/a3h
                |('a3h' is File ID of test.txt)
                |
                |===== Option Example =====
                |# Send (duration: 30 sec, Download limit: once, ID length: 16, Delete key: 'mykey1234')
                |wget -q -O - '${urlStr}/?duration=30s&get-times=1&id-length=16&delete-key=mykey1234' --post-file=./test.txt
                |
                |# Delete with delete key
                |wget --method DELETE 'https://hogehoge.io/a3h?delete-key=mykey1234'
                |
                |
                |------ Installation of trans-cli ------
                |pip3 install --upgrade git+https://github.com/nwtgck/trans-cli-python@master
                |
                |------ Tip: directory sending (zip) ------
                |zip -q -r - ./mydir | curl -X POST ${urlStr} --data-binary @-
                |
                |------ Tip: directory sending (tar.gz) ------
                |tar zfcp - ./mydir/ | curl -X POST ${urlStr} --data-binary @-
                |""".stripMargin
          )
        }

      } ~
      path(Remaining) { fileIdStr =>
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

                    val source =
                      FileIO.fromPath(file.toPath)
                        // Decrypt data
                        .via(CipherFlow.flow(genDecryptCipher()))
                        // Decompress data
                        .via(Compression.gunzip())

                    complete(HttpEntity(ContentTypes.NoContentType, fileStore.rawLength, source))

                  case _ =>
                    complete(StatusCodes.InternalServerError, s"Server error in decrement nGetLimit\n")
                }
              }
            } else {
              complete(StatusCodes.NotFound, s"File ID '${fileId.value}' is not found\n")
            }
          case _ =>
            complete(StatusCodes.NotFound, s"File ID '${fileId.value}' is not found or expired\n")
        }
      }

    } ~
    // "Post /" for client-sending a file
    (post & pathSingleSlash) {

      // Process GET Parameters
      processGetParamsRoute{getParams =>
        // Get a file from client and store it
        // hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities
        withoutSizeLimit {
          extractDataBytes { bytes =>
            // Store bytes to DB
            val storeFut: Future[FileId] = storeBytes(bytes, getParams.duration, getParams.nGetLimitOpt, getParams.idLengthOpt, getParams.isDeletable, getParams.deleteKeyOpt)

            onComplete(storeFut){
              case Success(fileId) =>
                complete(s"${fileId.value}\n")
              case Failure(e) =>
                println(e)
                e match {
                  case e : FileIdGenFailedException =>
                    complete(e.getMessage)
                  case _ =>
                    complete("Upload failed") // TODO Change response

                }
            }
          }
        }
      }

    } ~
    // "Post /" for client-sending a file
    (post & path("multipart") & entity(as[Multipart.FormData])) { formData =>

      // Process GET Parameters
      processGetParamsRoute{getParams =>

        // (hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities)
        withoutSizeLimit {
          val fileIdsSource: Source[FileId, Any] = formData.parts.mapAsync(1) { bodyPart: BodyPart =>
            // Get data bytes
            val bytes: Source[ByteString, Any] = bodyPart.entity.dataBytes

            // Store bytes to DB
            storeBytes(bytes, getParams.duration, getParams.nGetLimitOpt, getParams.idLengthOpt, getParams.isDeletable, getParams.deleteKeyOpt)
          }

          val fileIdsFut: Future[List[FileId]] = fileIdsSource.runFold(List.empty[FileId])((l, s) => l :+ s)


          onComplete(fileIdsFut) {
            case Success(fileIds) =>
              complete(fileIds.map(_.value).mkString("\n"))
            case Failure(e) =>
              println(e)
              e match {
                case e : FileIdGenFailedException =>
                  complete(e.getMessage)
                case _ =>
                  complete("Upload failed") // TODO Change response

              }
          }
        }
      }
    } ~
    // Delete file by ID
    (delete & path(Remaining)) { fileIdStr =>
      parameter("delete-key".?) { (deleteKeyOpt: Option[String]) =>

        // Generate file ID instance
        val fileId: FileId = FileId(fileIdStr)

        // Generate hashed delete key
        val hashedDeleteKeyOpt: Option[String] =
          deleteKeyOpt.map(key => Util.generateHashedKey1(key, Setting.KeySalt))

        // Check existence of valid(=not expired and deletable and has the same key) file store
        val existsFileStoreFut: Future[Option[FileStore]] =  db.run(
          Tables.allFileStores
            .filter(fileStore =>
              fileStore.fileId === fileId &&
              isAliveFileStore(fileStore) &&
              fileStore.isDeletable &&

              // NOTE: THIS if-else is for equality of NULL
              (if(hashedDeleteKeyOpt.isDefined)
                (fileStore.hashedDeleteKeyOpt === hashedDeleteKeyOpt).getOrElse(false) // NOTE: I'm not sure that .getOrElse(false) is correct
                else
                 fileStore.hashedDeleteKeyOpt.isEmpty
              )
            )
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

              val fut: Future[Unit] = for{
                // Delete the file store by ID
                _ <- db.run(Tables.allFileStores.filter(_.fileId === fileId).delete)
                // Delete the file
                _ <- Future.successful{
                  new File(fileStore.storePath).delete()
                }
              } yield ()
              onComplete(fut){
                case Success(_) =>
                  complete("Deleted successfully\n")
                case _ =>
                  complete(StatusCodes.InternalServerError, s"Server error in delete a file\n")
              }
            } else {
              complete(StatusCodes.NotFound, s"File ID '${fileId.value}' is not found\n")
            }
          case _ =>
            complete(StatusCodes.NotFound, s"File ID '${fileId.value}' is not found, expired or not deletable\n")
        }


      }
    }
  }

  /**
    * Process GET paramters
    * @param f
    * @return
    */
  def processGetParamsRoute(f: GetParams => Route): Route = {
    // for routing DSL
    import akka.http.scaladsl.server.Directives._

    parameter("duration".?, "get-times".?, "id-length".?, "deletable".?, "delete-key".?) { (durationStrOpt: Option[String], nGetLimitStrOpt: Option[String], idLengthStrOpt: Option[String], isDeletableStrOpt: Option[String], deleteKeyOpt: Option[String]) =>

      println(s"durationStrOpt: ${durationStrOpt}")

      println(s"isDeletableStrOpt: ${isDeletableStrOpt}")

      // Get duration
      val duration: FiniteDuration =
        (for {
          durationStr <- durationStrOpt
          durationSec <- strToDurationSecOpt(durationStr)
        } yield durationSec.seconds)
          .getOrElse(Setting.DefaultStoreDuration)
      println(s"Duration: ${duration}")

      // Generate nGetLimitOpt
      val nGetLimitOpt: Option[Int] = for {
        nGetLimitStr <- nGetLimitStrOpt
        nGetLimit <- Try(nGetLimitStr.toInt).toOption
      } yield nGetLimit
      println(s"nGetLimitOpt: ${nGetLimitOpt}")

      // Generate idLengthOpt
      val idLengthOpt: Option[Int] = for {
        idLengthStr <- idLengthStrOpt
        idLength <- Try(idLengthStr.toInt).toOption
      } yield idLength
      println(s"idLengthOpt: ${idLengthOpt}")

      // Generate isDeletable
      val isDeletable: Boolean = (for {
        deletableStr <- isDeletableStrOpt
        b <- deletableStr.toLowerCase match {
          case "" => Some(true)
          case "true" => Some(true)
          case "false" => Some(false)
          case _ => Some(false)
        }
      } yield b)
        .getOrElse(true) // NOTE: Default isDeletable is true

      f(GetParams(duration=duration, nGetLimitOpt=nGetLimitOpt, idLengthOpt=idLengthOpt, isDeletable=isDeletable, deleteKeyOpt=deleteKeyOpt))
    }
  }

  private def genEncryptCipher(): Cipher = {
    // Initialize Cipher
    // (from: http://www.suzushin7.jp/entry/2016/11/25/aes-encryption-and-decryption-in-java/)
    // (from: https://stackoverflow.com/a/17323025/2885946)
    // (from: https://stackoverflow.com/a/21155176/2885946)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
    cipher.init(
      Cipher.ENCRYPT_MODE,
      new SecretKeySpec(Setting.FileEncryptionKey.getBytes, "AES"),
      new IvParameterSpec(new Array[Byte](cipher.getBlockSize))
    )
    cipher
  }

  private def genDecryptCipher(): Cipher = {
    // Initialize Cipher
    // (from: http://www.suzushin7.jp/entry/2016/11/25/aes-encryption-and-decryption-in-java/)
    // (from: https://stackoverflow.com/a/17323025/2885946)
    // (from: https://stackoverflow.com/a/21155176/2885946)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
    cipher.init(
      Cipher.DECRYPT_MODE,
      new SecretKeySpec(Setting.FileEncryptionKey.getBytes, "AES"),
      new IvParameterSpec(new Array[Byte](cipher.getBlockSize))
    )
    cipher
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
    * @return Return FileId
    */
  def generateNoDuplicatedFiledIdAndStorePathOpt(idLength: Int): Option[(FileId, String)] = synchronized {
    var fileIdStr    : String = null
    var storeFilePath: String = null
    var tryNum       : Int    = 0

    // Generate File ID and storeFilePath
    do {
      tryNum    += 1
      fileIdStr = generateRandomFileId(idLength)
      storeFilePath = List(fileDbPath, fileIdStr).mkString(File.separator)
      if(tryNum > Setting.FileIdGenTryLimit){
        return None
      }
    } while (
      Setting.GetRouteName.allSet.contains(fileIdStr) || // File ID is reserved route name
      new File(storeFilePath).exists()                   // Path already exists
    )
    return Some(FileId(fileIdStr), storeFilePath)
  }

  /**
    * Generate random File ID
    * @return Random File ID
    */
  def generateRandomFileId(idLength: Int): String = {
    // 1 ~ 9 + 'a' ~ 'z'
    val candidates: Seq[String] = ((0 to 9) ++ ('a' to 'z')).map(_.toString)
    val i = (1 to idLength).map{_ =>
      val idx = secureRandom.nextInt(candidates.length)
      candidates(idx)
    }
    i.mkString
  }
}