# TODO NOT to use latest
FROM nwtgck/sbt:ubuntu16

LABEL maintainer="Ryo Ota <nwtgck@gmail.com>"

# Copy all things in this repo except files in .dockerignore
COPY . /trans

# Move to /trans
WORKDIR /trans

# Make keystore
RUN ./make-keystore.bash

# Generate jar
RUN sbt assembly

# Run the server
ENTRYPOINT ["java", "-jar", "target/scala-2.11/trans-server-akka-assembly-*.jar", "80", "443"]
