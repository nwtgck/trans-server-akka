# trans - Transmit a file by only standard commands

Transmit files by using **only common Uninx/Linux commands, `curl` or `wget`**

| branch | Travis status|
| --- | --- |
| [`master`](https://github.com/nwtgck/trans-server-akka/tree/master) | [![Build Status](https://travis-ci.org/nwtgck/trans-server-akka.svg?branch=master)](https://travis-ci.org/nwtgck/trans-server-akka) |
| [`develop`](https://github.com/nwtgck/trans-server-akka/tree/develop) | [![Build Status](https://travis-ci.org/nwtgck/trans-server-akka.svg?branch=develop)](https://travis-ci.org/nwtgck/trans-server-akka) |


## Why `trans`?

File transmitting between different devices annoying problem. There are already many file-transfer services on Web. However, most of these requires to **make us to sign up** or **install additional applications** to use them.

`trans` server is created to solve these problems. You can send/get by **only common Unix/Linux commands** or **on your browser**.


## Main features

* Send/Get by **only common commands**, `curl` or `wget`
* Send/Get **on your browser**
* Send/Get with some limitations
  - store duration
  - how many times you can download
* Delete files you sent
  - without delete key
  - with delete key
* Change File ID length (for security)

## Public Server on Heroku 

https://trans-akka.herokuapp.com/

## How to run the server

You can choose any ways you want bellow.

### Way 1 - Run on Docker

[![Docker Automated build](https://img.shields.io/docker/automated/nwtgck/trans-server-akka.svg)](https://hub.docker.com/r/nwtgck/trans-server-akka/) [![Docker Pulls](https://img.shields.io/docker/pulls/nwtgck/trans-server-akka.svg)](https://hub.docker.com/r/nwtgck/trans-server-akka/)

```bash
docker run -p 8080:80 nwtgck/trans-server-akka
```

Then you can go http://localhost:8080/

#### Docker run for daemonize and data persistence

You can also run the following command for **daemonize** and **data persistence**.

```bash
docker run -d -p 8080:80 -v $PWD/trans-db:/trans/db --restart=always nwtgck/trans-server-akka:v1.3.0
```

Data will be stored in `$PWD/trans-db` on your host machine. (Currently file-base H2 database is used, and files sent are stored as raw files)

### Way 2 - sbt "run-main ..."

The following example is running on 80 port.
```sh
$ cd <this-project>
$ ./make-keystore.bash
$ sbt "run-main Main 80"
```

### Way 3 - Making a jar

#### 1. Make a keystore

```sh
$ cd <this-project>
$ ./make-keystore.bash
```


#### 2. Make a jar

It takes a time
```sh
$ cd <this-project>
$ sbt assembly
```

#### 3. Run the jar

```sh
$ sudo java -jar target/scala-2.11/trans-server-akka-assembly-1.0.jar 80 443
```

## How to send a file to the server

### Way 1 - curl

The following example is sending `../test.txt`

```
$ curl https://trans-akka.herokuapp.com/ --data-binary @../test.txt
```

#### output
```
ab2
```

The server response, `ab2` is a File ID to get `../test.txt`


### Way 2 - wget

The following example is sending `../test.txt`

```sh
$ wget -q -O - https://trans-akka.herokuapp.com/ --post-file=../test.txt
```

* `-q` is for non-progress bar
* `-O -` is to output STDOUT  

#### output
```
9vi
```

The server response, `9vi` is a File ID to get `../test.txt`


### Way 3 - wc & cat & nc

**This way is for a user which can't use `curl` command.**

The following example is sending `sounds.zip`.

#### 1. Get a file byte

```sh
$ wc -c  < sounds.zip
```


##### output
```
1161257298 # Use it later
```

#### 2. Create `header.txt`

```
POST / HTTP/1.1
Host: localhost:4343
Content-Length: 1161257298

```

Don't forget the end of `'\n'`

 #### 3. Send a file by HTTP request


```
$ cat header.txt sounds.zip | nc localhost 4343
```


 The response is bellow.
```
HTTP/1.1 200 OK
Server: akka-http/10.0.5
Date: Sun, 23 Apr 2017 04:22:32 GMT
Connection: close
Content-Type: text/plain; charset=UTF-8
Content-Length: 4

6oz
```

`6oz` is FILE ID.


## How to get a file from the server

### Way 1 - wget

```sh
$ wget https://trans-akka.herokuapp.com/ab2
```

`ab2` is a File ID.

### Way 2 - curl


```sh
$ curl https://trans-akka.herokuapp.com/ab2 > test.txt
```

`ab2` is a File ID.


### Way 3 - Using a Browser

Access to `https://trans-akka.herokuapp.com/ab2`

`ab2` is a File ID.


## Sending options

|GET parameter | default value | decription |
|---|---:|---|
| `duration`   | 1 hour        | Store duration/life             |
| `times`      | any times     | How many times you can download |
| `length`     | `3`           | Length of File ID               |
| `deletable`  | `false`       | Whether a file can be deleted   |
| `key`        | nothing       | Key for deletion                | 

### An example with options

```bash
wget -q -O - 'https://trans-akka.herokuapp.com/?duration=30s&times=1&length=16&deletable&key=mykey1234' --post-file=./hello.txt
```

The command means
* duration is 30 seconds
* download once
* File ID length is 16
* The file is deletable and key is `'mykey1234'`

### Available duration examples
* `10s` - 10 seconds
* `2m`  - 2 minutes
* `12h` - 12 hours
* `25d` - 25 days

### Usage of `deletable`

All bellow are valid usage.

```bash
'https://trans-akka.herokuapp.com/?deletable'
# (same meaning as `deletable=true`)
```

```bash
'https://trans-akka.herokuapp.com/?deletable=true'
```

```bash
'https://trans-akka.herokuapp.com/?deletable=false'
```


## Delete file

Here is an example

```bash
# wget version
wget -q -O - --method=DELETE 'https://trans-akka.herokuapp.com/vua'
```

```bash
# curl version
curl -X DELETE 'https://trans-akka.herokuapp.com/vua'
```
(`vua` is a File ID)



## How to deploy on Heroku

```sh
$ git clone <this repo uri>
$ cd <this repo>
$ heroku create
$ git push heroku master
# Done !
# You can go to https://<app name>.herokuapp.com/
```

