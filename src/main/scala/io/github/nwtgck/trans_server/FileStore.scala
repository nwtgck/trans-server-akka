package io.github.nwtgck.trans_server

import io.github.nwtgck.trans_server.digest.{Algorithm, Digest}

/**
  * File store
  *
  * @param fileId             ID
  * @param storePath          Path of a file
  * @param rawLength          Content length of raw data
  * @param createdAt          Created datetime
  * @param deadline           Dead datetime
  * @param hashedGetKeyOpt    Hashed get-key
  * @param nGetLimitOpt       Limit of download
  * @param hashedDeleteKeyOpt Hashed delete key
  * @param md5Digest          MD5 digest
  * @param sha1Digest         SHA-1 digest
  * @param sha256Digest       SHA-256 digest
  */
case class FileStore(fileId            : FileId,
                     storePath         : String,
                     rawLength         : Long,
                     createdAt         : java.sql.Timestamp,
                     deadline          : java.sql.Timestamp,
                     hashedGetKeyOpt   : Option[String],
                     nGetLimitOpt      : Option[Int],
                     isDeletable       : Boolean,
                     hashedDeleteKeyOpt: Option[String],
                     md5Digest: Digest[Algorithm.`MD5`.type],
                     sha1Digest: Digest[Algorithm.`SHA-1`.type],
                     sha256Digest: Digest[Algorithm.`SHA-256`.type])