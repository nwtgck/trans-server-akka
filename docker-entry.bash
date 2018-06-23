#! /bin/sh

# Make keystore if trans.keystore doesn't exist
if [ ! -e /trans/trans.keystore ]; then
  /trans/make-keystore.bash
fi

# Runt the server
java -jar /trans/target/scala-2.11/trans-server-akka.jar 80 443