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
    // Create a core system
    core = new Core(db)
  }

  test("[positive] send test") {
    val fileContent: String = "this is a file content.\nthis doesn't seem to be a file content, but it is.\n"
    Post("/").withEntity(fileContent) ~> core.route ~> check {
      // Get file ID
      val fileId: String = responseAs[String].trim
      println(s"fileId: ${fileId}")
      // File ID length should be 3
      fileId.length shouldBe 3
    }

    // TODO Remove db/file_db
  }

  test("[positive] send/get test") {
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

    // TODO Remove db/file_db
  }

}
