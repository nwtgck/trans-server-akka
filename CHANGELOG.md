# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)

## [Unreleased]

## [1.19.1] - 2018-11-12
### Fixed
- Fix to notify an error to user when store process is failed

## [1.19.0] - 2018-11-10
### Added
- Allow users to redirect URI by `/r/<File ID>`

## [1.18.0] - 2018-09-05
### Added
- Allow users to specify File ID when sending

## [1.17.0] - 2018-07-31
### Added
- Add `--db-url` to specify database URL like 'jdbc:h2:tcp://localhost/~/h2db/trans'
- Allow users to send data via GET method

### Changed
- Allow users to send bigger data by HTTP GET method

## [1.16.2] - 2018-07-18
### Changed
- Improve `/help` to use simpler commands and add CLI installation written in Go

### Fixed
- Fix error HTTP statuses

## [1.16.0] - 2018-07-16
### Added
- Redirect HTTP to HTTPS when "X-Forwarded-Proto" is used (Heroku or IBM Cloud (Bluemix))
- Redirect HTTP to HTTPS in top page by specifying a command line option

### Changed
- Encrypt storing files by download-key from Basic Authentication
- Specify HTTP/HTTPS ports by command line options

## [1.15.1] - 2018-07-13
### Added
- Show file ID history on Web client

## [1.15.0] - 2018-07-12
### Added
- Allow users to set download key

## [1.14.0] - 2018-07-09
### Added
- Add `X-FILE-MD5` HTTP Header for checksum
- Add `X-FILE-SHA1` HTTP Header for checksum
- Add `X-FILE-SHA256` HTTP Header for checksum

## [1.13.0] - 2018-06-30
### Added
- Add `secure-char` GET parameter for more complex File ID

## [1.12.0] - 2018-06-28
### Added
- Adapt to deletion in Web client

### Changed
- Allow cross origin access

## [1.11.0] - 2018-06-24
### Added
- Adapt to sending a file by PUT method

### Fixed
- Change maximum file ID length to 128 for file system limitation

## [1.10.0] - 2018-06-24
### Changed
- Allow user to get file by any end-path

## [1.9.0] - 2018-05-22
### Changed
- Not to generate file ID including misunderstandable chararacters

## [1.8.0] - 2018-04-06
### Added
- Add text-sending form in web client

## [1.7.1] - 2018-04-01
### Fixed
- Fix not to fail to upload big file via multipart because of size limit

## [1.7.0] - 2018-01-22
### Added
- Add [manifest.yml](manifest.yml) for [IBM Cloud](https://www.ibm.com/cloud/)
### Changed
- Make `/help` page to be easy to copy

## [1.6.0] - 2017-12-03
### Added
- GET `/help` routing for help
- GET `/version` routing for version

### Fixed
- Return "Content-Length" in HTTP response to adapt [`pget`](https://github.com/Code-Hex/pget)

## [1.5.1] - 2017-12-01
### Changed
- Ignore cases, for example `true`, `True` or `TRUE` in `deletable` parameter
- Allow to users to specify keystore outside of Docker container

## [1.5.0] - 2017-11-27
### Changed
- Rename GET parameters
  - `key` => `delete-key`
  - `length` => `id-length`
  - `times` => `get-times` 

## [1.4.0] - 2017-11-27
### Changed
- Encrypt stored files in the server
- Compress stored files in the server

## [1.3.1] - 2017-11-26
### Added
- Specify options in the Web client

## [1.3.0] - 2017-11-25
## Added
- Allow users to change length of File ID
- Allow users to delete files with key or without key

## [1.2.0] - 2017-11-25
### Added
- Allow users to specify download-times limit


## [1.1.1] - 2017-11-25
### Fixed
- Docker automated problem with `sbt-assembly` 


## [1.1.0] - 2017-11-25
### Added
- Allow users to limit file by store duration


## 1.0.0 - 2017-11-23
### Added
- Basic sending file and getting file via HTTP
- HTTPS connection by using keystore
- Make jar by [`sbt-assembly`](https://github.com/sbt/sbt-assembly)
- Adapt [`sbt-native-packager`](https://github.com/sbt/sbt-native-packager) for [Heroku](https://heroku.com/)


[Unreleased]: https://github.com/nwtgck/trans-server-akka/compare/v1.19.1...HEAD
[1.19.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.19.0...v1.19.1
[1.19.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.18.0...v1.19.0
[1.18.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.17.0...v1.18.0
[1.17.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.16.2...v1.17.0
[1.16.2]: https://github.com/nwtgck/trans-server-akka/compare/v1.16.1...v1.16.2
[1.16.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.15.1...v1.16.0
[1.15.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.15.0...v1.15.1
[1.15.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.14.0...v1.15.0
[1.14.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.13.0...v1.14.0
[1.13.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.11.0...v1.12.0
[1.11.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.10.0...v1.11.0
[1.10.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.9.0...v1.10.0
[1.9.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.7.1...v1.8.0
[1.7.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.7.0...v1.7.1
[1.7.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.5.1...v1.6.0
[1.5.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.3.1...v1.4.0
[1.3.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.0.0...v1.1.0