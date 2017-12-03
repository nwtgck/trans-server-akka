# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)

## [1.5.1] - 2017-12-01
### Change
- Ignore cases, for example `true`, `True` or `TRUE` in `deletable` parameter
- Allow to users to specify keystore outside of Docker container

## [1.5.0] - 2017-11-27
### Change
- Rename GET parameters
  - `key` => `delete-key`
  - `length` => `id-length`
  - `times` => `get-times` 

## [1.4.0] - 2017-11-27
### Change
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


[1.5.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.3.1...v1.4.0
[1.3.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/nwtgck/trans-server-akka/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/nwtgck/trans-server-akka/compare/v1.0.0...v1.1.0