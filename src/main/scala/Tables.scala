import slick.jdbc.meta.MTable

import scala.concurrent.{ExecutionContext, Future}

object Tables {
  import slick.driver.H2Driver.api._

  val allFileStores = TableQuery[FileStores]

  // All tables
  private val allTables = Seq(
    allFileStores
  )

  // All schemata
  private val allSchemata = allTables.map(_.schema).reduceLeft((s, t) => s ++ t)

  // Create tables if not exist
  def createTablesIfNotExist(db: Database)(implicit ec: ExecutionContext): Future[Seq[Unit]] = {
    Future.sequence(
      Tables.allTables.map{table =>
        db.run(MTable.getTables(table.baseTableRow.tableName)).flatMap { result =>
          if (result.isEmpty) {
            db.run(table.schema.create)
          } else {
            Future.successful(())
          }
        }
      }
    )
  }




  case class FileStore(fileId        : String,
                       createdAt     : java.sql.Timestamp,
                       deadline      : java.sql.Timestamp,
                       nGetLimitOpt  : Option[Int])

  class FileStores(tag: Tag) extends Table[FileStore](tag, "file_stores"){
    val fileId         = column[String]("file_id", O.PrimaryKey)
    val createdAt      = column[java.sql.Timestamp]("created_at")
    val deadline       = column[java.sql.Timestamp]("deadline")
    val nGetLimitOpt   = column[Option[Int]]("n_get_limit_opt")

    override def * = (fileId, createdAt, deadline, nGetLimitOpt) <> (FileStore.tupled, FileStore.unapply)
  }


}
