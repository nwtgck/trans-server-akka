#!/bin/bash

# Make keystore if trans.keystore doesn't exist
! test -e /trans/trans.keystore && /trans/make-keystore.bash

# Runt the server
java -jar /trans/target/scala-2.11/trans-server-akka.jar 80 443