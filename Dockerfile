# NOTE: Multi-stage Build

FROM nwtgck/pre-installed-sbt:1.2.8 as build

# Copy all things in this repo except files in .dockerignore
COPY . /trans
# Move to /trans
WORKDIR /trans
# Generate jar
RUN sbt assembly


# Open JDK 8 - Alpine
FROM openjdk:8-alpine
LABEL maintainer="Ryo Ota <nwtgck@gmail.com>"

# Make directories
RUN mkdir -p /trans/target/scala-2.11/
# Copy scripts
COPY make-keystore.sh docker-entry.sh /trans/
# Copy artifacts
COPY --from=build /trans/target/scala-2.11/trans-server-akka.jar /trans/target/scala-2.11/trans-server-akka.jar

# Run entry (Run the server)
ENTRYPOINT ["/trans/docker-entry.sh"]
