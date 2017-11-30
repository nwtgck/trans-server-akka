#!/bin/bash

# Make keystore if trans.keystore doesn't exist
! test -e trans.keystore && ./make-keystore.bash

# Runt the server
java -jar target/scala-2.11/trans-server-akka.jar 80 443