import java.nio.file.Files

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration


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
      // File ID length should be 3
      fileId.length shouldBe 3
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

  test("[positive] send/get duration") {
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

  test("[negative] send/get duration") {
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

  test("[positive/negative] send/get times") {
    val originalContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    var fileId     : String = null
    val times      : Int    = 5
    Post(s"/?times=${times}").withEntity(originalContent) ~> core.route ~> check {
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

}
