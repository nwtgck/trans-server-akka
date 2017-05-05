# trans - Transmit a file anywhere

## What is this?

Sending a file and getting a file via HTTP


## How to run the server

### Way1 - sbt "run-main ..."

The following example is running on 80 port.
```sh
$ cd <this-project>
$ ./make-keystore.sh
$ sbt "run-main Main 80"
```

### Way2 - Making a jar

#### 1. Make a keystore

```sh
$ cd <this-project>
$ ./make-keystore.sh
```


#### 2. Make a jar

It takes a time
```sh
$ cd <this-project>
$ sbt assembly
```

#### 3. Run the jar

```sh
$ java -jar target/scala-2.11/trans-server-akka-assembly-1.0.jar 80
```

## How to run the server on Docker

```sh
# In host machine
$ git clone <this-project-url>
$ cd <this-project>
$ ./make-keystore.bash
$ docker run -itd -p 80:80 -p 443:443 -v $PWD:/trans nwtgck/java:8 sh -c 'cd /trans && java -jar target/scala-2.11/trans-server-akka-assembly-1.0.jar 80 443'
```

## How to send a file to the server

### Way1 - curl

The following example is sending `../test.txt`

```
$ curl http://localhost:8181 --data-binary @../test.txt
```

#### output
```
ab2
```

The server response, `ab2` is a File ID to get `../test.txt`


### Way2 - wget

The following example is sending `../test.txt`

```sh
$ wget -q -O - http://localhost:8181 --post-file=../test.txt
```

* `-q` is for non-progress bar
* `-O -` is to output STDOUT  

#### output
```
9vi
```

The server response, `9vi` is a File ID to get `../test.txt`


### Way3 - wc & cat & nc

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

### Way1 - wget

```sh
$ wget http://localhost:8181/ab2
```

`ab2` is a File ID.

### Way2 - curl


```sh
$ curl http://localhost:8181/ab2 > test.txt
```

`ab2` is a File ID.


### Way3 - Using a Browser

Access to `http://localhost:8181/ab2`

`ab2` is a File ID.
