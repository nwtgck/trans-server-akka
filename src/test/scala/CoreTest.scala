import java.nio.file.Files

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random


class CoreTest extends FunSuite with ScalatestRouteTest with Matchers with BeforeAndAfter {


  var db  : Database = _
  var core: Core     = _

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

  test("[positive] send") {
    val fileContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    Post("/").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      val fileId: String = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be DefaultIdLength
      fileId.length shouldBe Setting.DefaultIdLength
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
    }
  }
  test("[positive] send/get big data") {

    // Create random 10MB bytes
    val originalContent: ByteString = ByteString({
      val bytes = new Array[Byte](10000000)
      Random.nextBytes(bytes)
      bytes
    })

    var fileId: String = null
    Post("/").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
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
    Post(s"/?id-length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      val fileId: String = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be MaxIdLength
      fileId.length shouldBe Setting.MaxIdLength
    }
  }

  test("[positive] send with length=1 (too small)") {
    val fileContent : String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    val fileIdLength: Int    = 1
    Post(s"/?length=${fileIdLength}").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      val fileId: String = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be MinIdLength
      fileId.length shouldBe Setting.MinIdLength
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
    Post(s"/?deletable&key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
    }

    // Delete the file
    Delete(s"/${fileId}?key=${deleteKey}") ~> core.route ~> check {
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
    Post(s"/?deletable&key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
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
    Post(s"/?deletable&key=${deleteKey}").withEntity(originalContent) ~> core.route ~> check {
      // Get file ID
      fileId = responseAs[String].trim
    }

    // Fail to delete the file
    Delete(s"/${fileId}?key=${wrongKey}") ~> core.route ~> check {
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

}
