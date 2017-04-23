# tras - Transmit a file anywhere

## What is this?

Sending a file and getting a file via HTTP
 
 
## How to run the server

### Way1 - sbt "run-main ..."

The following example is running on 80 port.
```sh
$ cd <this-project>
$ sbt "run-main Main 80"
```

### Way2 - Making a jar


#### 1. Making a jar

It takes a time
```sh
$ cd <this-project>
$ sbt assembly
```

#### 2. Run the jar

```sh
$ java -jar target/scala-2.11/trans-server-akka-assembly-1.0.jar 80
```


## How to send a file to the server

The following example is sending `../test.txt`

```
$ curl http://localhost:4343 --data-binary @../test.txt
ab2
```

 
The server response, `ab2` is a File ID to get `../test.txt`
 

 
## How to get a file from the server

### Way1 - wget

```sh
$ wget http://localhost:4343/ab2
```

### Way2 - curl


```sh
$ curl http://localhost:4343/ab2 > test.txt
```


### Way3 - Using a Browser

Access to `http://localhost:4343/ab2`