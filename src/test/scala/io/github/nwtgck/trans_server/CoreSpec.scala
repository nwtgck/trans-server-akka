package io.github.nwtgck.trans_server

import java.nio.file.Files
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpChallenge, `WWW-Authenticate`, Location}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.util.ByteString
import io.github.nwtgck.trans_server.digest.Algorithm
import org.scalatest.{BeforeAndAfter, FunSpec, Matchers, PrivateMethodTester}
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random


class CoreSpec extends FunSpec with ScalatestRouteTest with Matchers with BeforeAndAfter with PrivateMethodTester{


  var db  : Database = _
  var core: Core     = _

  // Set default timeout
  // (from: https://stackoverflow.com/a/39669891/2885946)
  implicit def defaultTimeout(implicit system: ActorSystem): RouteTestTimeout = RouteTestTimeout(5.second)

  before {
    // Temp directory for file DB
    val tmpDbPath: String = Files.createTempDirectory("db_").toString
    // Create a file-base db
    db = Database.forURL(s"jdbc:h2:${tmpDbPath}", driver="org.h2.Driver")
    // Create a tables
    Await.ready(Tables.createTablesIfNotExist(db), Duration.Inf)
    // Temp directory for file DB
    val tmpFileDbPath: String = Files.createTempDirectory("file_db_").toString
    // Create a core system
    core = new Core(db, fileDbPath = tmpFileDbPath, enableTopPageHttpsRedirect = false)
  }

  // Calculate digest string
  def calcDigestString(bytes: Array[Byte], algorithm: Algorithm): String = {
    val m = MessageDigest.getInstance(algorithm.name)
    m.update(bytes, 0, bytes.length)
    m.digest().map("%02x".format(_)).mkString
  }

  describe("strToDurationSecOpt") {
    it("should parse duration string if the string is valid") {
      val strToDurationSecOpt = PrivateMethod[Option[Int]]('strToDurationSecOpt)

      core invokePrivate strToDurationSecOpt("1s")   shouldBe Some(1)
      core invokePrivate strToDurationSecOpt("57s")  shouldBe Some(57)
      core invokePrivate strToDurationSecOpt("2m")   shouldBe Some(120)
      core invokePrivate strToDurationSecOpt("3h")   shouldBe Some(10800)
      core invokePrivate strToDurationSecOpt("5d")   shouldBe Some(432000)
      core invokePrivate strToDurationSecOpt("Xs")   shouldBe None
      core invokePrivate strToDurationSecOpt("Ym")   shouldBe None
      core invokePrivate strToDurationSecOpt("hoge") shouldBe None
    }
  }

  describe("route") {
    // TODO: Remove and use `it`
    val test = it

    describe("top page") {
      it("should show the top page") {
        Get(s"/") ~> core.route ~> check {
          // Just check status code
          status.intValue() shouldBe 200
        }
      }

      it("should show redirect page if X-Forwarded-Proto header is specified") {
        Get(s"/") ~> addHeader("X-Forwarded-Proto", "http") ~> core.route ~> check {
          // Status should be "Permanent Redirect"
          status shouldBe StatusCodes.PermanentRedirect
        }
      }

      it("should redirect from top page if enable-top-page-https-redirect is true") {
        // Create a memory-base db
        val db = Database.forConfig("h2mem-trans")
        // Create a tables
        Await.ready(Tables.createTablesIfNotExist(db), Duration.Inf)
        // Temp directory for file DB
        val tmpFileDbPath: String = Files.createTempDirectory("file_db_").toString
        // Create a core system
        val core = new Core(db, fileDbPath = tmpFileDbPath, enableTopPageHttpsRedirect = true)

        Get("/") ~> core.route ~> check {
          // Status should be "Permanent Redirect"
          status shouldBe StatusCodes.PermanentRedirect
        }
      }
    }

    describe("help page") {
      it("should show help page") {
        Get(s"/help") ~> core.route ~> check {
          // Just check status code
          status.intValue() shouldBe 200
        }
      }
    }

    describe("version page") {
      it("should show version page") {
        Get(s"/version") ~> core.route ~> check {
          val versionStr: String = responseAs[String].trim
          // Check version page
          versionStr shouldBe BuildInfo.version
        }
      }
    }

    it("should allow user to send data") {
      val fileContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      Post("/").withEntity(fileContent) ~> core.route ~> check {
        // Get file ID
        val fileId: String = responseAs[String].trim
        // File ID length should be DefaultIdLength
        fileId.length shouldBe Setting.DefaultIdLength

        // Verify Checksum
        header(Setting.Md5HttpHeaderName).get.value shouldBe calcDigestString(fileContent.getBytes, Algorithm.MD5)
        header(Setting.Sha1HttpHeaderName).get.value shouldBe calcDigestString(fileContent.getBytes, Algorithm.`SHA-1`)
        header(Setting.Sha256HttpHeaderName).get.value shouldBe calcDigestString(fileContent.getBytes, Algorithm.`SHA-256`)
      }
    }


    it("should allow user to send and get data") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileId: String =
        Post("/").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      Get(s"/${fileId}") ~> core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent

        // Verify Checksum
        header(Setting.Md5HttpHeaderName).get.value shouldBe calcDigestString(originalContent.getBytes, Algorithm.MD5)
        header(Setting.Sha1HttpHeaderName).get.value shouldBe calcDigestString(originalContent.getBytes, Algorithm.`SHA-1`)
        header(Setting.Sha256HttpHeaderName).get.value shouldBe calcDigestString(originalContent.getBytes, Algorithm.`SHA-256`)
      }

      Get(s"/${fileId}/hoge.txt") ~> core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }

      Get(s"/${fileId}/foo.dummyextension") ~> core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }
    }

    describe("File ID fixation") {
      it("should allow user send by specifying a File ID and get it") {

        val fileId: String = "myfileid123"

        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        Post(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val resFileId = responseAs[String].trim
          // Response of File ID should be the specified File ID
          resFileId shouldBe fileId
        }

        Get(s"/${fileId}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }

      it("should not allow user to send by specifying too SHORT File ID") {
        val fileId: String = "abc"
        require(fileId.length < Setting.minSpecifiedFileIdLength)

        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        Post(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      // TODO: Use boundary values
      it("should not allow user send by specifying too LONG File ID") {
        val fileId: String = "a" * 500
        require(fileId.length > Setting.MaxIdLength)

        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        Post(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      it("should not allow user to send by specifying INVALID File ID") {
        // NOTE: File ID contains invalid character
        val fileId: String = "myfileid~123"

        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        Post(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      it("should not allow user send by specifying DUPLICATE File ID") {
        val fileId: String = "myfileid123"

        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        Post(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val resFileId = responseAs[String].trim
          // Response of File ID should be the specified File ID
          resFileId shouldBe fileId
        }

        // NOTE: Send twice by the same File ID
        Post(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }
    }

    describe("multipart") {
      it("should allow user to send data by multipart and get it") {
        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        // (from: https://blog.knoldus.com/2016/06/01/a-basic-application-to-handle-multipart-form-data-using-akka-http-with-test-cases-in-scala/)
        val fileData = Multipart.FormData.BodyPart.Strict("dummy_name", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent))
        val formData = Multipart.FormData(fileData)

        val fileId: String =
          Post("/multipart", formData) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Get(s"/${fileId}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }

      it("should allow user to send many data by multipart and get them") {
        val originalContent0: String = "this is 1st. this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val originalContent1: String = "this is 2nd. this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val originalContent2: String = "this is 3rd. this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val originalContent3: String = "this is 4th. this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        val formData = Multipart.FormData(
          Multipart.FormData.BodyPart.Strict("dummy_name1", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent0)),
          Multipart.FormData.BodyPart.Strict("dummy_name2", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent1)),
          Multipart.FormData.BodyPart.Strict("dummy_name3", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent2)),
          Multipart.FormData.BodyPart.Strict("dummy_name4", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent3))
        )

        // Send via multipart and get file IDs
        val fileIds: IndexedSeq[String] = Post("/multipart", formData) ~> core.route ~> check {
          // Get file ID
          val fileIds = responseAs[String].split("\\n").toIndexedSeq
          // The number of IDs should be 4
          fileIds.length shouldBe 4
          // Length of each file ID should be 3
          fileIds.forall(_.length == 3) shouldBe true
          fileIds
        }

        Get(s"/${fileIds(0)}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent0
        }

        Get(s"/${fileIds(1)}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent1
        }

        Get(s"/${fileIds(2)}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent2
        }

        Get(s"/${fileIds(3)}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent3
        }
      }

      it("should allow user to send by multipart with Basic Authentication and get it") {
        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        // (from: https://blog.knoldus.com/2016/06/01/a-basic-application-to-handle-multipart-form-data-using-akka-http-with-test-cases-in-scala/)
        val fileData = Multipart.FormData.BodyPart.Strict("dummy_name", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent))
        val formData = Multipart.FormData(fileData)

        val credentials1 = BasicHttpCredentials("dummy user", "p4ssw0rd")

        val fileId: String =
          Post("/multipart", formData) ~> addCredentials(credentials1) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        // Get the file without user and password
        // (from: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/security-directives/authenticateBasic.html)
        Get(s"/${fileId}") ~> core.route ~> check {
          status shouldEqual StatusCodes.Unauthorized
          header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some(""), Map("charset" → "UTF-8"))
        }

        // Get the file with user and password
        Get(s"/${fileId}") ~> addCredentials(credentials1) ~>  core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }
    }

    describe("PUT-method sending") {
      it("should send by PUT and get") {
        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val fileId1: String =
          Put("/").withEntity(originalContent) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Get(s"/${fileId1}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }

        val fileId2: String =
          Put("/hoge.txt").withEntity(originalContent) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Get(s"/${fileId2}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }

      it("should allow user to send by PUT specifying File ID and get") {

        val fileId: String = "myfileid123"

        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

        Put(s"/fix/${fileId}").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val resFileId = responseAs[String].trim
          // Response of File ID should be the specified File ID
          resFileId shouldBe fileId
        }

        Get(s"/${fileId}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }
    }

    describe("GET-method sending") {
      it("should allow user to send by GET method and get") {
        val fileId1: String =
          Get("/send?data=hello%2C%20world") ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Get(s"/${fileId1}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe "hello, world"
        }

        // Send data with option
        val idLenght: Int = 16
        val data: String = "hello"
        Get(s"/send?data=${data}&id-length=${idLenght}") ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be the specified value
          fileId.length shouldBe idLenght
          fileId
        }
      }

      // FIXME: The following test is meaningless
      // This means the test is not related to `akka.http.parsing.max-uri-length`
      // because RouteTest does not check `max-uri-length`.
      // There may be some setting to check `max-uri-length`.
      it("should allow user send big data by GET method and get it") {

        val longData: String = "A" * 10000000
        val fileId2: String =
          Get(s"/send?data=${longData}") ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Get(s"/${fileId2}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe longData
        }
      }

      it("should allow user to send by GET method specifying File ID and get it") {
        val fileId: String = "myfileid123"

        Get(s"/send/fix/${fileId}?data=hello%2C%20world") ~> core.route ~> check {
          // Get file ID
          val resFileId = responseAs[String].trim
          // Response of File ID should be the specified File ID
          resFileId shouldBe fileId
        }

        Get(s"/${fileId}") ~> core.route ~> check {
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe "hello, world"
        }
      }
    }


    it("should allow user to send and get big data") {

      // Create random 10MB bytes
      val originalBytes: Array[Byte] = {
        val bytes = new Array[Byte](10000000)
        Random.nextBytes(bytes)
        bytes
      }
      val originalContent: ByteString = ByteString(originalBytes)

      val fileId: String =
        Post("/").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3
          // Verify Checksum
          header(Setting.Md5HttpHeaderName).get.value shouldBe calcDigestString(originalBytes, Algorithm.MD5)
          header(Setting.Sha1HttpHeaderName).get.value shouldBe calcDigestString(originalBytes, Algorithm.`SHA-1`)
          header(Setting.Sha256HttpHeaderName).get.value shouldBe calcDigestString(originalBytes, Algorithm.`SHA-256`)

          fileId
        }

      Get(s"/${fileId}") ~> core.route ~> check {
        val resContent: ByteString = responseAs[ByteString]
        // response should be original
        resContent shouldBe originalContent
      }
    }

    it("should allow user to send and get with Basic Authentication") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

      val getKey: String = "p4ssw0rd"

      val credentials1 = BasicHttpCredentials("dummy user", getKey)

      val fileId: String =
        Post("/").withEntity(originalContent) ~> addCredentials(credentials1) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get the file without user and password
      // (from: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/security-directives/authenticateBasic.html)
      Get(s"/${fileId}") ~> core.route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some(""), Map("charset" → "UTF-8"))
      }

      // Get the file with user and password
      Get(s"/${fileId}") ~> addCredentials(credentials1) ~>  core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }

      // Get the file with different user and password
      // NOTE: User name should be ignored
      val credentials2 = BasicHttpCredentials("hoge hoge user", getKey)
      Get(s"/${fileId}") ~> addCredentials(credentials2) ~>  core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }
    }

    it("should not allow user to get by Basic Authentication with a wrong password") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

      val getKey: String = "p4ssw0rd"

      val credentials1 = BasicHttpCredentials("dummy user", getKey)
      val fileId: String =
        Post("/").withEntity(originalContent) ~> addCredentials(credentials1) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get the file with user and WRONG password
      Get(s"/${fileId}") ~> addCredentials(credentials1.copy(password = "this is wrong password!")) ~>  core.route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some(""), Map("charset" → "UTF-8"))
      }
    }

    describe("'duration' parameter") {
      it("should allow user to send with duration and get it in the duration") {
        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val durationSec: Int    = 5
        val fileId: String =
          Post(s"/?duration=${durationSec}s").withEntity(originalContent) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Thread.sleep((durationSec - 2)*1000)

        Get(s"/${fileId}") ~> core.route ~> check {
          // Get response file content
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }

      it("should not allow user to get out of the duration") {
        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val durationSec: Int    = 5
        val fileId: String =
          Post(s"/?duration=${durationSec}s").withEntity(originalContent) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Thread.sleep((durationSec + 2)*1000)

        Get(s"/${fileId}") ~> core.route ~> check {
          // The status should be 404
          response.status shouldBe StatusCodes.NotFound
        }
      }
    }

    describe("'time' parameter") {
      it("should allow user to get n-times and not allow to get over n-times") {
        val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
        val times      : Int    = 5
        val fileId: String =
          Post(s"/?get-times=${times}").withEntity(originalContent) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        // Success to get
        for(_ <- 1 to times){
          Get(s"/${fileId}") ~> core.route ~> check {
            // Get response file content
            val resContent: String = responseAs[String]
            // response should be original
            resContent shouldBe originalContent
          }
        }

        // Fail to get because of times-limit
        Get(s"/${fileId}") ~> core.route ~> check {
          // The status should be 404
          response.status shouldBe StatusCodes.NotFound
        }
      }
    }

    test("[positive] send with length=16") {
      val fileContent : String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileIdLength: Int    = 16
      Post(s"/?id-length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
        // Get file ID
        val fileId: String = responseAs[String].trim
        // File ID length should be fileIdLength
        fileId.length shouldBe fileIdLength
      }
    }

    test("[positive] send with length=100000 (too big)") {
      val fileContent : String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileIdLength: Int    = 100000
      val fileId: String =
        Post(s"/?id-length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be MaxIdLength
          fileId.length shouldBe Setting.MaxIdLength

          fileId
        }
      Get(s"/${fileId}") ~> core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe fileContent
      }
    }

    test("[positive] send with length=1 (too small)") {
      val fileContent : String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileIdLength: Int    = 1
      val fileId: String =
        Post(s"/?length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be MinIdLength
          fileId.length shouldBe Setting.MinIdLength

          fileId
        }
      Get(s"/${fileId}") ~> core.route ~> check {
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe fileContent
      }
    }

    test("[positive] send/delete with deletable") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileId: String =
        Post("/?deletable").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Delete the file
      Delete(s"/${fileId}") ~> core.route ~> check {
        // Status should be OK
        response.status shouldBe StatusCodes.OK
      }

      // Fail to get because of deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // The status should be 404
        response.status shouldBe StatusCodes.NotFound
      }
    }

    test("[positive] send/delete with deletable=true") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileId: String =
        Post("/?deletable=true").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Delete the file
      Delete(s"/${fileId}") ~> core.route ~> check {
        // Status should be OK
        response.status shouldBe StatusCodes.OK
      }

      // Fail to get because of deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // The status should be 404
        response.status shouldBe StatusCodes.NotFound
      }
    }

    test("[positive] send/delete without deletable") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileId: String =
        Post("/").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Success to delete the file
      Delete(s"/${fileId}") ~> core.route ~> check {
        // Status should be OK
        response.status shouldBe StatusCodes.OK
      }

      // Fail to get because of deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // The status should be 404
        response.status shouldBe StatusCodes.NotFound
      }
    }

    test("[negative] send/delete with deletable=false") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val fileId: String =
        Post("/?deletable=false").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Fail to delete the file
      Delete(s"/${fileId}") ~> core.route ~> check {
        // Status should be NotFound because not deletable
        response.status shouldBe StatusCodes.NotFound
      }

      // Success to get because of no deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // Get response file content
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }
    }


    test("[positive] send/delete with deletable with key") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val deleteKey: String = "mykey1234"
      val fileId: String =
        Post(s"/?deletable&delete-key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Delete the file
      Delete(s"/${fileId}?delete-key=${deleteKey}") ~> core.route ~> check {
        // Status should be OK
        response.status shouldBe StatusCodes.OK
      }

      // Fail to get because of deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // The status should be 404
        response.status shouldBe StatusCodes.NotFound
      }
    }

    test("[negative] send/delete with deletable with empty key") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val deleteKey: String = "mykey1234"
      val fileId: String =
        Post(s"/?deletable&delete-key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Fail to delete the file
      Delete(s"/${fileId}") ~> core.route ~> check {
        // Status should be NotFound because key is empty
        response.status shouldBe StatusCodes.NotFound
      }

      // Success to get because of no deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // Get response file content
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }
    }

    test("[negative] send/delete with deletable with wrong key") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val deleteKey: String = "mykey1234"
      val wrongKey : String = "hogehoge"
      val fileId: String =
        Post(s"/?deletable&delete-key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim

          fileId
        }

      // Fail to delete the file
      Delete(s"/${fileId}?delete-key=${wrongKey}") ~> core.route ~> check {
        // Status should be NotFound because of wrong key
        response.status shouldBe StatusCodes.NotFound
      }

      // Success to get because of no deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // Get response file content
        val resContent: String = responseAs[String]
        // response should be original
        resContent shouldBe originalContent
      }
    }

    test("[positive] send/delete with Basic Authentication") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
      val getKey: String = "p4ssw0rd"

      val credentials1 = BasicHttpCredentials("dummy user", getKey)

      val fileId: String =
        Post("/").withEntity(originalContent) ~> addCredentials(credentials1) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // NOTE: Basic Authentication doesn't related to deletion
      //       related to get file only

      // Delete the file
      Delete(s"/${fileId}") ~> core.route ~> check {
        // Status should be OK
        response.status shouldBe StatusCodes.OK
      }

      // Fail to get because of deletion
      Get(s"/${fileId}") ~> core.route ~> check {
        // The status should be 404
        response.status shouldBe StatusCodes.NotFound
      }
    }

    test("[positive] send/get with secure-char") {
      val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

      // This value should be big enough for iteration
      val N = 30
      var concatedFileId: String = ""
      for (_ <- 1 to N) {
        val fileId: String =
          Post("/?secure-char").withEntity(originalContent) ~> core.route ~> check {
            // Get file ID
            val fileId = responseAs[String].trim
            // Concat file ID
            concatedFileId += fileId
            // File ID length should be 3
            fileId.length shouldBe 3

            fileId
          }

        Get(s"/${fileId}") ~> core.route ~> check {
          // Get response file content
          val resContent: String = responseAs[String]
          // response should be original
          resContent shouldBe originalContent
        }
      }

      // Some file ID contains some characters which is not contained in regular candidate chars
      // Because of "secure-char"
      concatedFileId.toCharArray.exists(c => !Setting.candidateChars.contains(c)) shouldBe true
    }

    test("[positive] send/get URI") {
      val urlContent: String = "https://hogehoge.io"

      val fileId: String =
        Post("/").withEntity(urlContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get and redirect
      Get(s"/r/${fileId}") ~> core.route ~> check {
        // The status should be redirect code
        response.status shouldBe StatusCodes.TemporaryRedirect
        header[Location].get.value shouldBe urlContent
      }
    }

    test("[positive] send/get URI with white spaces") {
      val urlContent: String = " \t \n https://hogehoge.io \n  "

      val fileId: String =
        Post("/").withEntity(urlContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get and redirect
      Get(s"/r/${fileId}") ~> core.route ~> check {
        // The status should be redirect code
        response.status shouldBe StatusCodes.TemporaryRedirect
        header[Location].get.value shouldBe "https://hogehoge.io"
      }
    }

    test("[positive] send/get URI whose length is max") {
      // Max URL
      val maxUrlContent: String = {
        val prefix = "https://hogehoge.io/"
        // (from: https://stackoverflow.com/a/2099896/2885946)
        val tail   = Stream.continually('a' to 'z').flatten.take(Setting.MaxRedirectionUriLength - prefix.length).mkString
        require(prefix.length + tail.length == Setting.MaxRedirectionUriLength)
        prefix + tail
      }

      val fileId: String =
        Post("/").withEntity(maxUrlContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get and redirect
      Get(s"/r/${fileId}") ~> core.route ~> check {
        // The status should be redirect code
        response.status shouldBe StatusCodes.TemporaryRedirect
        header[Location].get.value shouldBe maxUrlContent
      }
    }

    test("[negative] send/get URI whose length is max+1") {
      // Max URL
      val maxUrlContent: String = {
        val prefix = "https://hogehoge.io/"
        // (from: https://stackoverflow.com/a/2099896/2885946)
        val tail   = Stream.continually('a' to 'z').flatten.take(Setting.MaxRedirectionUriLength - prefix.length + 1).mkString
        require(prefix.length + tail.length == Setting.MaxRedirectionUriLength + 1)
        prefix + tail
      }

      val fileId: String =
        Post("/").withEntity(maxUrlContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get and redirect
      Get(s"/r/${fileId}") ~> core.route ~> check {
        // The status should be bad request because the URI length is over max
        response.status shouldBe StatusCodes.BadRequest
      }
    }

    test("[negative] send/get invalid URI") {
      // Invalid URL
      val urlContent: String = "invalid URI"

      val fileId: String =
        Post("/").withEntity(urlContent) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get and redirect
      Get(s"/r/${fileId}") ~> core.route ~> check {
        // The status should be bad request because the URI is invalid
        response.status shouldBe StatusCodes.BadRequest
      }
    }

    test("[positive] send/get with Basic Authentication in URL redirection") {
      val urlContent: String = "https://hogehoge.io"

      val getKey: String = "p4ssw0rd"

      val credentials1 = BasicHttpCredentials("dummy user", getKey)

      val fileId: String =
        Post("/").withEntity(urlContent) ~> addCredentials(credentials1) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get the file without user and password
      // (from: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/security-directives/authenticateBasic.html)
      Get(s"/r/${fileId}") ~> core.route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some(""), Map("charset" → "UTF-8"))
      }

      // Get the file with user and password
      Get(s"/r/${fileId}") ~> addCredentials(credentials1) ~>  core.route ~> check {
        // The status should be redirect code
        response.status shouldBe StatusCodes.TemporaryRedirect
        header[Location].get.value shouldBe urlContent
      }

      // Get the file with different user and password
      // NOTE: User name should be ignored
      val credentials2 = BasicHttpCredentials("hoge hoge user", getKey)
      Get(s"/r/${fileId}") ~> addCredentials(credentials2) ~>  core.route ~> check {
        // The status should be redirect code
        response.status shouldBe StatusCodes.TemporaryRedirect
        header[Location].get.value shouldBe urlContent
      }
    }

    test("[negative] send/get with Basic Authentication in URL redirection") {
      val urlContent: String = "https://hogehoge.io"

      val getKey: String = "p4ssw0rd"

      val credentials1 = BasicHttpCredentials("dummy user", getKey)
      val fileId: String =
        Post("/").withEntity(urlContent) ~> addCredentials(credentials1) ~> core.route ~> check {
          // Get file ID
          val fileId = responseAs[String].trim
          // File ID length should be 3
          fileId.length shouldBe 3

          fileId
        }

      // Get the file with user and WRONG password
      Get(s"/r/${fileId}") ~> addCredentials(credentials1.copy(password = "this is wrong password!")) ~>  core.route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some(""), Map("charset" → "UTF-8"))
      }
    }
  }
}
