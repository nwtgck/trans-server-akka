package io.github.nwtgck.trans_server

import io.github.nwtgck.trans_server.digest.{Algorithm, Digest}
import slick.ast.BaseTypedType
import slick.jdbc.JdbcType
import slick.jdbc.meta.MTable

import scala.concurrent.{ExecutionContext, Future}

object Tables {
  import slick.driver.H2Driver.api._

  object OriginalTypeImplicits{
    implicit val fileIdType: JdbcType[FileId] with BaseTypedType[FileId] = MappedColumnType.base[FileId, String]({ id => id.value}, { str => FileId(str) })

    implicit val md5DigestType: JdbcType[Digest[Algorithm.`MD5`.type]] with BaseTypedType[Digest[Algorithm.`MD5`.type]] =
      MappedColumnType.base[Digest[Algorithm.`MD5`.type], String]({ digest: Digest[Algorithm.`MD5`.type] => digest.value}, { str => Digest(str) })

    implicit val sha1DigestType: JdbcType[Digest[Algorithm.`SHA-1`.type]] with BaseTypedType[Digest[Algorithm.`SHA-1`.type]] =
      MappedColumnType.base[Digest[Algorithm.`SHA-1`.type], String]({ digest: Digest[Algorithm.`SHA-1`.type] => digest.value}, { str => Digest(str) })

    implicit val sha2565DigestType: JdbcType[Digest[Algorithm.`SHA-256`.type]] with BaseTypedType[Digest[Algorithm.`SHA-256`.type]] =
      MappedColumnType.base[Digest[Algorithm.`SHA-256`.type], String]({ digest: Digest[Algorithm.`SHA-256`.type] => digest.value}, { str => Digest(str) })
  }
  import OriginalTypeImplicits._

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


  class FileStores(tag: Tag) extends Table[FileStore](tag, "file_stores"){
    val fileId             = column[FileId]("file_id", O.PrimaryKey)
    val storePath          = column[String]("store_path")
    val rawLength          = column[Long]("raw_length")
    val createdAt          = column[java.sql.Timestamp]("created_at")
    val deadline           = column[java.sql.Timestamp]("deadline")
    val nGetLimitOpt       = column[Option[Int]]("n_get_limit_opt")
    val isDeletable        = column[Boolean]("is_deletable")
    val hashedDeleteKeyOpt = column[Option[String]]("hashed_delete_key_opt")
    val md5Digest          = column[Digest[Algorithm.`MD5`.type]]("md5_digest")
    val sha1Digest         = column[Digest[Algorithm.`SHA-1`.type]]("sha1_digest")
    val sha256Digest       = column[Digest[Algorithm.`SHA-256`.type]]("sha256_digest")

    override def * = (fileId, storePath, rawLength, createdAt, deadline, nGetLimitOpt, isDeletable, hashedDeleteKeyOpt, md5Digest, sha1Digest, sha256Digest) <> (FileStore.tupled, FileStore.unapply)
  }


}
