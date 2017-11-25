/**
  * File store
  * @param fileId       ID
  * @param storePath    Path of a file
  * @param createdAt    Created datetime
  * @param deadline     Dead datetime
  * @param nGetLimitOpt Limit of download
  */
case class FileStore(fileId        : FileId,
                     storePath     : String,
                     createdAt     : java.sql.Timestamp,
                     deadline      : java.sql.Timestamp,
                     nGetLimitOpt  : Option[Int])