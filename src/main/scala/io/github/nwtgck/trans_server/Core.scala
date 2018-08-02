package io.github.nwtgck.trans_server

import java.io.File
import java.nio.file.StandardOpenOption

import akka.actor.ActorSystem
import akka.event.Logging
import javax.crypto.Cipher
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.model.headers.{HttpOrigin, RawHeader}
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directive0, Route}
import akka.stream.scaladsl.{Broadcast, Compression, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape, IOResult}
import akka.util.ByteString

import io.github.nwtgck.trans_server.Tables.OriginalTypeImplicits._
import io.github.nwtgck.trans_server.digest.{Algorithm, Digest, DigestCalculator}
import slick.driver.H2Driver.api._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}


/**
  * Available parameters
  * @param duration
  * @param nGetLimitOpt
  * @param idLengthOpt
  * @param isDeletable
  * @param deleteKeyOpt
  * @param usesSecureChar
  * @param getKeyOpt
  */
private [this] case class Params(duration       : FiniteDuration,
                                 nGetLimitOpt   : Option[Int],
                                 idLengthOpt    : Option[Int],
                                 isDeletable    : Boolean,
                                 deleteKeyOpt   : Option[String],
                                 usesSecureChar : Boolean,
                                 getKeyOpt      : Option[String])

class Core(db: Database, fileDbPath: String, enableTopPageHttpsRedirect: Boolean)(implicit materializer: ActorMaterializer){

  // Logger
  private[this] val logger = Logging.getLogger(materializer.system, this)


  // Secure random generator
  // (from: https://qiita.com/suin/items/bfff121c8481990e1507)
  private val secureRandom: Random = new Random(new java.security.SecureRandom())

  def storeBytes(byteSource: Source[ByteString, Any], specifiedFileIdOpt: Option[FileId], duration: FiniteDuration, getKeyOpt: Option[String], nGetLimitOpt: Option[Int], idLengthOpt: Option[Int], isDeletable: Boolean, deleteKeyOpt: Option[String], usesSecureChar: Boolean)(implicit ec: ExecutionContext, materializer: ActorMaterializer): Future[(FileId, Digest[Algorithm.`MD5`.type], Digest[Algorithm.`SHA-1`.type], Digest[Algorithm.`SHA-256`.type])] = {

    if(false) {// NOTE: if-false outed
      require(
        !isDeletable && deleteKeyOpt.isEmpty || // deleteKeyOpt should be None if not isDeletable
        isDeletable                             // deleteKeyOpt can be None or Some() if isDeletable
      )
    }

    import TimestampUtil.RichTimestampImplicit._

    def store(fileId: FileId, storeFilePath: String) = {
      // Adjust duration (big duration is not good)
      val adjustedDuration: FiniteDuration = duration.min(Setting.MaxStoreDuration)
      logger.debug(s"adjustedDuration: ${adjustedDuration}")

      logger.debug(s"isDeletable: ${isDeletable}")

      // Generate hashed get-key
      val hashedGetKeyOpt: Option[String] =
        getKeyOpt.map(key => Util.generateHashedKey1(key, Setting.KeySalt))

      // Generate hashed delete key
      val hashedDeleteKeyOpt: Option[String] =
        if(isDeletable)
          deleteKeyOpt.map(key => Util.generateHashedKey1(key, Setting.KeySalt))
        else
          None


      // Create a file-store graph
      val storeGraph: RunnableGraph[Future[(IOResult, Long, Digest[Algorithm.`MD5`.type], Digest[Algorithm.`SHA-1`.type], Digest[Algorithm.`SHA-256`.type])]] = {

        // Store file
        val fileStoreSink: Sink[ByteString, Future[IOResult]] =
          FileIO.toPath(new File(storeFilePath).toPath, options = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE))

        // Calc length of ByteString
        val lengthSink   : Sink[ByteString, Future[Long]] =
          Sink.fold(0l)(_ + _.length)

        // Sink for calculating MD5
        val md5Sink: Sink[ByteString, Future[Digest[Algorithm.`MD5`.type]]] =
          Flow.fromGraph(new DigestCalculator(Algorithm.`MD5`)).toMat(Sink.head)(Keep.right)

        // Sink for calculating SHA-1
        val sha1Sink: Sink[ByteString, Future[Digest[Algorithm.`SHA-1`.type]]] =
          Flow.fromGraph(new DigestCalculator(Algorithm.`SHA-1`)).toMat(Sink.head)(Keep.right)

        // Sink for calculating SHA-256
        val sha256Sink: Sink[ByteString, Future[Digest[Algorithm.`SHA-256`.type]]] =
          Flow.fromGraph(new DigestCalculator(Algorithm.`SHA-256`)).toMat(Sink.head)(Keep.right)

        // Zip 5 futures
        def futureZip5[A, B, C, D, E](aFut: Future[A], bFut: Future[B], cFut: Future[C], dFut: Future[D], eFut: Future[E]): Future[(A, B, C, D, E)] =
          aFut.zip(bFut).zip(cFut).zip(dFut).zip(eFut).map{case ((((a, b), c), d), e) => (a, b, c, d, e)}

        // Store key
        val storeKey: String =
          getKeyOpt.getOrElse("") + Setting.FileEncryptionKey

        RunnableGraph.fromGraph(GraphDSL.create(fileStoreSink, lengthSink, md5Sink, sha1Sink, sha256Sink)(futureZip5){implicit builder =>
          (fileStoreSink, lengthSink, md5Sink, sha1Sink, sha256Sink) =>

            import GraphDSL.Implicits._
            val bcast = builder.add(Broadcast[ByteString](5))

            // * compress ~> encrypt ~> store
            // * calc length
            // * calc MD5
            // * calc SHA-1
            // * calc SHA-256
            byteSource ~> bcast ~> Compression.gzip ~> CipherFlow.flow(genEncryptCipher(storeKey)) ~> fileStoreSink
            bcast ~> lengthSink
            bcast ~> md5Sink
            bcast ~> sha1Sink
            bcast ~> sha256Sink
            ClosedShape
        })
      }


      // Store future
      val storeFuture = storeGraph.run()

      // Remove file if failed
      storeFuture.onFailure{case e =>
        logger.error("Error in store", e)
        logger.debug(s"Deleting '${storeFilePath}'...")
        val res: Boolean = new File(storeFilePath).delete()
        logger.debug(s"Deleted(${fileId.value}): ${res}")
      }

      for {
        // Store a file and get content length
        (ioResult, rawLength, md5Digest, sha1Digest, sha256Digest) <- storeFuture

        // Create file store object
        fileStore = FileStore(
          fileId             = fileId,
          storePath          = storeFilePath,
          rawLength          = rawLength,
          createdAt          = TimestampUtil.now(),
          deadline           = TimestampUtil.now + adjustedDuration,
          hashedGetKeyOpt    = hashedGetKeyOpt,
          nGetLimitOpt       = nGetLimitOpt,
          isDeletable        = isDeletable,
          hashedDeleteKeyOpt = hashedDeleteKeyOpt,
          md5Digest          = md5Digest,
          sha1Digest         = sha1Digest,
          sha256Digest       = sha256Digest
        )
        // Store to the database
        // TODO Check fileId collision (but if collision happens database occurs an error because of primary key)
        _          <- db.run(Tables.allFileStores += fileStore)
        _ <- Future.successful {
          logger.debug(s"IOResult: ${ioResult}")
          logger.debug(s"rawLength: ${rawLength}")
          logger.debug(s"md5Digest: ${md5Digest}")
          logger.debug(s"sha1Digest: ${sha1Digest}")
          logger.debug(s"sha256Digest: ${sha256Digest}")
        }
      } yield (fileId, md5Digest, sha1Digest, sha256Digest)
    }


    specifiedFileIdOpt match {
      // If file ID is specified
      case Some(fileId) =>
        // TODO: Notify the following error to users
        // Length of specified File ID should be equal or greater than `minSpecifiedFileIdLength`
        require(fileId.value.length >= Setting.minSpecifiedFileIdLength)
        // File ID value should be in candidate characters
        require(fileId.value.forall((Setting.candidateChars ++ Setting.secureCandidateChars).contains(_)))

        val storeFilePath: String = generateStoreFilePath(fileId)
        if(new File(storeFilePath).exists()){
          Future.failed(
            // NOTE: This information is OK to tell users because users can know whether File ID exists by HEAD method easily.
            new Exception(s"'${fileId.value}' is already used")
          )
        } else {
          store(fileId, storeFilePath)
        }
      case _ =>
        // Get ID length
        val idLength: Int =
          idLengthOpt
            .getOrElse(Setting.DefaultIdLength)
            .max(Setting.MinIdLength)
            .min(Setting.MaxIdLength)
        logger.debug(s"idLength: ${idLength}")

        // Generate File ID and storeFilePath
        generateNoDuplicatedFiledIdAndStorePathOpt(idLength, usesSecureChar) match {
          case Some((fileId, storeFilePath)) => {
            store(fileId, storeFilePath)
          }
          case _ =>
            Future.failed(
              new FileIdGenFailedException(s"Length=${idLength} of File ID might run out")
            )
        }
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

  // This directive allow cross origin
  // (from: https://gist.github.com/jeroenr/5261fa041d592f37cd80#file-corshandler-scala-L16)
  private val allowCrossOrigin: Directive0 = {
    import akka.http.scaladsl.server.Directives._
    respondWithHeaders(
      headers.`Access-Control-Allow-Origin`(headers.HttpOriginRange.*)
    )
  }

  // Store byteSource and return HTTP response
  private def sendingRoute(byteSource: Source[ByteString, _], specifiedFileIdOpt: Option[FileId])(implicit ec: ExecutionContext): Route = {
    import akka.http.scaladsl.server.Directives._

    processParamsRoute { params => // Process parameters
      // Store bytes to DB
      val storeFut: Future[(FileId, Digest[Algorithm.`MD5`.type], Digest[Algorithm.`SHA-1`.type], Digest[Algorithm.`SHA-256`.type])] =
        storeBytes(byteSource, specifiedFileIdOpt, params.duration, params.getKeyOpt, params.nGetLimitOpt, params.idLengthOpt, params.isDeletable, params.deleteKeyOpt, params.usesSecureChar)

      onComplete(storeFut) {
        case Success((fileId, md5Digest, sha1Digest, sha256Digest)) =>
          respondWithHeaders(
            headers.RawHeader(Setting.Md5HttpHeaderName, md5Digest.value),
            headers.RawHeader(Setting.Sha1HttpHeaderName, sha1Digest.value),
            headers.RawHeader(Setting.Sha256HttpHeaderName, sha256Digest.value)
          ) {
            complete(s"${fileId.value}\n")
          }
        case Failure(e) =>
          logger.error("Error in storing data", e)
          e match {
            case e: FileIdGenFailedException =>
              complete(StatusCodes.InternalServerError, e.getMessage)
            case _ =>
              complete(StatusCodes.InternalServerError, "Upload failed")
          }
      }
    }
  }

  /**
    * Http Server's Routing
    */
  def route(implicit materializer: ActorMaterializer): Route = allowCrossOrigin {
    // for routing DSL
    import akka.http.scaladsl.server.Directives._
    // for using XML
    // for using settings
    // for Futures
    import concurrent.ExecutionContext.Implicits.global

    // For Route.seal
    implicit val system: ActorSystem = materializer.system

    get {
      // "Get /" for confirming whether the server is running
      pathSingleSlash {
        extractUri { uri =>
          // If top-page redirect is enable and scheme is HTTP
          if (enableTopPageHttpsRedirect && uri.scheme == "http") {
            // Redirect to HTTPs page
            redirect(
              uri.copy(scheme = "https"),
              StatusCodes.PermanentRedirect
            )
          } else {
            // Redirect http to https in Heroku or IBM Cloud (Bluemix)
            Util.xForwardedProtoHttpsRedirectRoute(
              getFromResource("index.html") // (from: https://doc.akka.io/docs/akka-http/current/scala/http/routing-dsl/directives/file-and-resource-directives/getFromResource.html)
            )
          }
        }
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
                |===== curl ======
                |# Send  : curl ${urlStr} -T test.txt
                |# Get   : curl ${urlStr}/a3h > mytest.txt
                |# Delete: curl -X DELETE ${urlStr}/a3h
                |(Suppose 'a3h' is File ID of test.txt)
                |
                |====== wget =====
                |# Send  : wget -q -O - ${urlStr} --post-file=test.txt
                |# Get   : wget ${urlStr}/a3h
                |# Delete: wget --method=DELETE ${urlStr}/a3h
                |(Suppose 'a3h' is File ID of test.txt)
                |
                |===== Option Example =====
                |# Send (duration: 30 sec, Download limit: once, ID length: 16, Delete key: 'mykey1234')
                |curl '${urlStr}/?duration=30s&get-times=1&id-length=16&delete-key=mykey1234&secure-char' -T ./test.txt
                |
                |# Delete with delete key
                |curl -X DELETE '${urlStr}/a3h?delete-key=mykey1234'
                |
                |------ Tip: directory sending (zip) ------
                |zip -q -r - ./mydir | curl -T - ${urlStr}
                |
                |------ Tip: directory sending (tar.gz) ------
                |tar zfcp - ./mydir/ | curl -T - ${urlStr}
                |
                |------ Installation of trans-cli ------
                |# Mac
                |brew install nwtgck/homebrew-trans/trans
                |
                |# Other
                |go get github.com/nwtgck/trans-cli-go
                |""".stripMargin
          )
        }

      } ~
      // Send data via GET method
      // TODO: Impl File ID fixation for /send routing
      path(Setting.GetRouteName.Send) {
        parameter("data".?) {dataStrOpt =>
          // ByteString data
          val data: ByteString = dataStrOpt.map(ByteString(_)).getOrElse(ByteString.empty)
          // Store the data and return a response
          sendingRoute(
            Source.single(data),
            specifiedFileIdOpt = None
          )
        }
      } ~
      path(RemainingPath) { path =>
        // Get file ID string
        val fileIdStr = path.head.toString
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

                    def fileSource(storeKey: String) =
                      FileIO.fromPath(file.toPath)
                        // Decrypt data
                        .via(CipherFlow.flow(genDecryptCipher(storeKey)))
                        // Decompress data
                        .via(Compression.gunzip())

                      respondWithHeaders(
                        headers.RawHeader(Setting.Md5HttpHeaderName, fileStore.md5Digest.value),
                        headers.RawHeader(Setting.Sha1HttpHeaderName, fileStore.sha1Digest.value),
                        headers.RawHeader(Setting.Sha256HttpHeaderName, fileStore.sha256Digest.value)
                      ){
                        // File response
                        def fileResponse(storeKey: String) =
                          complete(HttpEntity(ContentTypes.NoContentType, fileStore.rawLength, fileSource(storeKey)))

                        fileStore.hashedGetKeyOpt match {
                          // Has get-key
                          case Some(hashedGetKey) =>
                            // Authenticator for get-key
                            def getKeyAuthenticator(credentials: Credentials): Option[Unit] =
                              credentials match {
                                case p @ Credentials.Provided(id) if p.verify(hashedGetKey, k => Util.generateHashedKey1(k, Setting.KeySalt)) => Some(())
                                case _ => None
                              }

                            // (from: https://github.com/spray/spray/issues/1131)
                            // (from: https://github.com/akka/akka-http/pull/412)
                            Route.seal {
                              // Basic authentication
                              authenticateBasic(realm = "", authenticator = getKeyAuthenticator) { _ =>
                                Util.extractBasicAuthUserAndPasswordOpt{
                                  case Some((_, getKey)) =>
                                    fileResponse(storeKey = getKey + Setting.FileEncryptionKey)
                                  case None =>
                                    logger.error("Critical Error: getting password of Basic Authentication (This will never happen)")
                                    complete(StatusCodes.InternalServerError, s"Server error in Basic Authentication\n")
                                }
                              }
                            }
                          // Not have get-key
                          case None =>
                            // Just response
                            fileResponse(Setting.FileEncryptionKey)
                        }
                      }

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
    (post & path("multipart")) {
      // Process GET Parameters
      processParamsRoute{ params =>
        // (hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities)
        withoutSizeLimit {
          entity(as[Multipart.FormData]) {formData =>
            val fileIdsSource: Source[FileId, Any] = formData.parts.mapAsync(1) { bodyPart: BodyPart =>
              // Get data bytes
              val bytes: Source[ByteString, Any] = bodyPart.entity.dataBytes

              // Store bytes to DB
              storeBytes(bytes, None, params.duration, params.getKeyOpt, params.nGetLimitOpt, params.idLengthOpt, params.isDeletable, params.deleteKeyOpt, params.usesSecureChar)
                .map(_._1)
            }

            val fileIdsFut: Future[List[FileId]] = fileIdsSource.runFold(List.empty[FileId])((l, s) => l :+ s)


            onComplete(fileIdsFut) {
              case Success(fileIds) =>
                complete(fileIds.map(_.value).mkString("\n"))
              case Failure(e) =>
                logger.error("Error in storing data in multipart", e)
                e match {
                  case e : FileIdGenFailedException =>
                    complete(StatusCodes.InternalServerError, e.getMessage)
                  case _ =>
                    complete(StatusCodes.InternalServerError, "Upload failed")

                }
            }
          }

        }
      }
    } ~
    {
      // Route of sending
      def sendingRouteWithBody(specifiedFileIdOpt: Option[FileId]): Route =
        // Get a file from client and store it
        // hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities
        withoutSizeLimit {
          extractDataBytes { bytes =>
            sendingRoute(bytes, specifiedFileIdOpt)
          }
        }

      // Send by POST
      (post & pathSingleSlash)(sendingRouteWithBody(specifiedFileIdOpt = None)) ~
      // Send by POST by specifing File ID
      (post & path(Remaining)){ fileIdStr => sendingRouteWithBody(specifiedFileIdOpt = Some(FileId(fileIdStr)))} ~
      // Send by PUT
      (put & path(RemainingPath)){path =>
        // (e.g. In case of "/hogehoge.txt/abc1234", "abc1234" is a specified File ID)
        val specifiedFileIdOpt: Option[FileId] = Try(FileId(path.tail.tail.head.toString)).toOption
        sendingRouteWithBody(specifiedFileIdOpt = specifiedFileIdOpt)
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
    * Process paramters
    * @param f
    * @return
    */
  def processParamsRoute(f: Params => Route): Route = {
    // for routing DSL
    import akka.http.scaladsl.server.Directives._

    parameter("duration".?, "get-times".?, "id-length".?, "deletable".?, "delete-key".?, "secure-char".?) { (durationStrOpt: Option[String], nGetLimitStrOpt: Option[String], idLengthStrOpt: Option[String], isDeletableStrOpt: Option[String], deleteKeyOpt: Option[String], usesSecureCharStrOpt: Option[String]) =>

      logger.debug(s"durationStrOpt: ${durationStrOpt}")

      logger.debug(s"isDeletableStrOpt: ${isDeletableStrOpt}")

      // Get duration
      val duration: FiniteDuration =
        (for {
          durationStr <- durationStrOpt
          durationSec <- strToDurationSecOpt(durationStr)
        } yield durationSec.seconds)
          .getOrElse(Setting.DefaultStoreDuration)
      logger.debug(s"Duration: ${duration}")

      // Generate nGetLimitOpt
      val nGetLimitOpt: Option[Int] = for {
        nGetLimitStr <- nGetLimitStrOpt
        nGetLimit <- Try(nGetLimitStr.toInt).toOption
      } yield nGetLimit
      logger.debug(s"nGetLimitOpt: ${nGetLimitOpt}")

      // Generate idLengthOpt
      val idLengthOpt: Option[Int] = for {
        idLengthStr <- idLengthStrOpt
        idLength <- Try(idLengthStr.toInt).toOption
      } yield idLength
      logger.debug(s"idLengthOpt: ${idLengthOpt}")

      // Convert to Option[String] to Option[Boolean]
      def convertBoolStrOptBoolOpt(boolStrOpt: Option[String]): Option[Boolean] =
        for {
          boolStr <- boolStrOpt
          b <- boolStr.toLowerCase match {
            case ""      => Some(true)
            case "true"  => Some(true)
            case "false" => Some(false)
            case _       => Some(false)
          }
        } yield b

      // Generate isDeletable
      val isDeletable: Boolean =
        convertBoolStrOptBoolOpt(isDeletableStrOpt)
          .getOrElse(true) // NOTE: Default isDeletable is true

      // Generate usesSecureChar
      val usesSecureChar: Boolean =
        convertBoolStrOptBoolOpt(usesSecureCharStrOpt)
          .getOrElse(false) // NOTE: Default usesSecureChar is false

      // Get-get key by basic authentication
      Util.extractBasicAuthUserAndPasswordOpt{userAndPasswordOpt =>
        // Get get-key
        val getKeyOpt: Option[String] = userAndPasswordOpt.map(_._2)
        f(Params(duration=duration, nGetLimitOpt=nGetLimitOpt, idLengthOpt=idLengthOpt, isDeletable=isDeletable, deleteKeyOpt=deleteKeyOpt, usesSecureChar=usesSecureChar, getKeyOpt = getKeyOpt))
      }
    }
  }

  private def genEncryptCipher(key: String): Cipher = {
    // Initialize Cipher
    // (from: http://www.suzushin7.jp/entry/2016/11/25/aes-encryption-and-decryption-in-java/)
    // (from: https://stackoverflow.com/a/17323025/2885946)
    // (from: https://stackoverflow.com/a/21155176/2885946)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
    cipher.init(
      Cipher.ENCRYPT_MODE,
      new SecretKeySpec(key.getBytes.take(16), "AES"),
      new IvParameterSpec(new Array[Byte](cipher.getBlockSize))
    )
    cipher
  }

  private def genDecryptCipher(key: String): Cipher = {
    // Initialize Cipher
    // (from: http://www.suzushin7.jp/entry/2016/11/25/aes-encryption-and-decryption-in-java/)
    // (from: https://stackoverflow.com/a/17323025/2885946)
    // (from: https://stackoverflow.com/a/21155176/2885946)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
    cipher.init(
      Cipher.DECRYPT_MODE,
      new SecretKeySpec(key.getBytes.take(16), "AES"),
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
            logger.error("Error in file deletion", e)
          }
        }
      }

      // Print for debugging
      _         <- Future{logger.debug(s"Cleanup ${deadFiles.size} dead files")}
    } yield ()
  }

  /**
    * Generate store-path by File ID
    * @param fileId
    * @return
    */
  def generateStoreFilePath(fileId: FileId): String =
    List(fileDbPath, fileId.value).mkString(File.separator)

  /**
    * Generate non-duplicated File ID and store path
    * @return Return FileId
    */
  def generateNoDuplicatedFiledIdAndStorePathOpt(idLength: Int, usesSecureChar: Boolean): Option[(FileId, String)] = synchronized {
    var fileIdStr    : String = null
    var storeFilePath: String = null
    var tryNum       : Int    = 0

    // Generate File ID and storeFilePath
    do {
      tryNum    += 1
      fileIdStr = generateRandomFileId(idLength, usesSecureChar)
      storeFilePath = generateStoreFilePath(fileId=FileId(fileIdStr))
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
  def generateRandomFileId(idLength: Int, usesSecureChar: Boolean): String = {

    // Candidate chars
    val candidateChars =
      if(usesSecureChar) {
        Setting.secureCandidateChars
      } else {
        Setting.candidateChars
      }

    val i = (1 to idLength).map{_ =>
      val idx = secureRandom.nextInt(candidateChars.length)
      candidateChars(idx)
    }
    i.mkString
  }
}