package io.github.nwtgck.trans_server

import java.nio.file.Files
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.util.ByteString
import io.github.nwtgck.trans_server.digest.Algorithm
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random


class CoreTest extends FunSuite with ScalatestRouteTest with Matchers with BeforeAndAfter {


  var db  : Database = _
  var core: Core     = _

  // Set default timeout
  // (from: https://stackoverflow.com/a/39669891/2885946)
  implicit def defaultTimeout(implicit system: ActorSystem): RouteTestTimeout = RouteTestTimeout(5.second)

  before {
    // Create a memory-base db
    db = Database.forConfig("h2mem-trans")
    // Create a tables
    Await.ready(Tables.createTablesIfNotExist(db), Duration.Inf)
    // Temp directory for file DB
    val tmpFileDbPath: String = Files.createTempDirectory("file_db_").toString
    println(s"tmpFileDbPath: ${tmpFileDbPath}")
    // Create a core system
    core = new Core(db, fileDbPath = tmpFileDbPath)
  }

  // Calculate digest string
  def calcDigestString(bytes: Array[Byte], algorithm: Algorithm): String = {
    val m = MessageDigest.getInstance(algorithm.name)
    m.update(bytes, 0, bytes.length)
    m.digest().map("%02x".format(_)).mkString
  }

  test("[positive] send") {
    val fileContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    Post("/").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      val fileId: String = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be DefaultIdLength
      fileId.length shouldBe Setting.DefaultIdLength

      // Verify Checksum
      header(Setting.Md5HttpHeaderName).get.value shouldBe calcDigestString(fileContent.getBytes, Algorithm.MD5)
      header(Setting.Sha1HttpHeaderName).get.value shouldBe calcDigestString(fileContent.getBytes, Algorithm.`SHA-1`)
      header(Setting.Sha256HttpHeaderName).get.value shouldBe calcDigestString(fileContent.getBytes, Algorithm.`SHA-256`)
    }
  }

  test("[positive] send/get") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId: String = null
    Post("/").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
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

  test("[positive] send/get by multipart") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

    // (from: https://blog.knoldus.com/2016/06/01/a-basic-application-to-handle-multipart-form-data-using-akka-http-with-test-cases-in-scala/)
    val fileData = Multipart.FormData.BodyPart.Strict("dummy_name", HttpEntity(ContentTypes.`text/plain(UTF-8)`, originalContent))
    val formData = Multipart.FormData(fileData)

    var fileId: String = null
    Post("/multipart", formData) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
    }

    Get(s"/${fileId}") ~> core.route ~> check {
      val resContent: String = responseAs[String]
      // response should be original
      resContent shouldBe originalContent
    }
  }

  test("[positive] send/get many by multipart") {
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

  test("[positive] send/get by PUT") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId: String = null
    Put("/").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
    }

    Get(s"/${fileId}") ~> core.route ~> check {
      val resContent: String = responseAs[String]
      // response should be original
      resContent shouldBe originalContent
    }

    Put("/hoge.txt").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
    }

    Get(s"/${fileId}") ~> core.route ~> check {
      val resContent: String = responseAs[String]
      // response should be original
      resContent shouldBe originalContent
    }
  }


  test("[positive] send/get big data") {

    // Create random 10MB bytes
    val originalBytes: Array[Byte] = {
      val bytes = new Array[Byte](10000000)
      Random.nextBytes(bytes)
      bytes
    }
    val originalContent: ByteString = ByteString(originalBytes)

    var fileId: String = null
    Post("/").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
      // Verify Checksum
      header(Setting.Md5HttpHeaderName).get.value shouldBe calcDigestString(originalBytes, Algorithm.MD5)
      header(Setting.Sha1HttpHeaderName).get.value shouldBe calcDigestString(originalBytes, Algorithm.`SHA-1`)
      header(Setting.Sha256HttpHeaderName).get.value shouldBe calcDigestString(originalBytes, Algorithm.`SHA-256`)
    }

    Get(s"/${fileId}") ~> core.route ~> check {
      val resContent: ByteString = responseAs[ByteString]
      // response should be original
      resContent shouldBe originalContent
    }
  }

  test("[positive] send/get with duration") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId     : String = null
    val durationSec: Int    = 5
    Post(s"/?duration=${durationSec}s").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
    }

    Thread.sleep((durationSec - 2)*1000)

    Get(s"/${fileId}") ~> core.route ~> check {
      // Get response file content
      val resContent: String = responseAs[String]
      // response should be original
      resContent shouldBe originalContent
    }
  }

  test("[negative] send/get with duration") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId     : String = null
    val durationSec: Int    = 5
    Post(s"/?duration=${durationSec}s").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
    }

    Thread.sleep((durationSec + 2)*1000)

    Get(s"/${fileId}") ~> core.route ~> check {
      // The status should be 404
      response.status shouldBe StatusCodes.NotFound
    }
  }

  test("[positive/negative] send/get with times") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId     : String = null
    val times      : Int    = 5
    Post(s"/?get-times=${times}").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
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

  test("[positive] send with length=16") {
    val fileContent : String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    val fileIdLength: Int    = 16
    Post(s"/?id-length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      val fileId: String = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be fileIdLength
      fileId.length shouldBe fileIdLength
    }
  }

  test("[positive] send with length=100000 (too big)") {
    val fileContent : String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    val fileIdLength: Int    = 100000
    var fileId: String = null
    Post(s"/?id-length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be MaxIdLength
      fileId.length shouldBe Setting.MaxIdLength
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
    var fileId: String = null
    Post(s"/?length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be MinIdLength
      fileId.length shouldBe Setting.MinIdLength
    }
    Get(s"/${fileId}") ~> core.route ~> check {
      val resContent: String = responseAs[String]
      // response should be original
      resContent shouldBe fileContent
    }
  }

  test("[positive] send/delete with deletable") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId: String = null
    Post("/?deletable").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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
    var fileId: String = null
    Post("/?deletable=true").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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
    var fileId: String = null
    Post("/").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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
    var fileId: String = null
    Post("/?deletable=false").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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
    var fileId   : String = null
    val deleteKey: String = "mykey1234"
    Post(s"/?deletable&delete-key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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
    var fileId   : String = null
    val deleteKey: String = "mykey1234"
    Post(s"/?deletable&delete-key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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
    var fileId   : String = null
    val deleteKey: String = "mykey1234"
    val wrongKey : String = "hogehoge"
    Post(s"/?deletable&delete-key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
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

  test("[positive] send/get with secure-char") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"

    // This value should be big enough for iteration
    val N = 30
    var concatedFileId: String = ""
    for (_ <- 1 to N) {
      var fileId: String = null
      Post("/?secure-char").withEntity(originalContent) ~> core.route ~> check {
        // Get file ID
        fileId = responseAs[String].trim
        println(s"fileId: ${fileId}")
        // Concat file ID
        concatedFileId += fileId
        // File ID length should be 3
        fileId.length shouldBe 3
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
}
