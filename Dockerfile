# NOTE: Multi-stage Build

FROM nwtgck/trans-server-akka:v1.9.0
LABEL maintainer="Ryo Ota <nwtgck@gmail.com>"

# Copy all things in this repo except files in .dockerignore
COPY . /trans
# Move to /trans
WORKDIR /trans
# Generate jar
RUN sbt assembly


# Open JDK 8 - Alpine 
FROM java:openjdk-8-alpine
# Make directories
RUN mkdir -p /trans/target/scala-2.11/
# Copy scripts
COPY make-keystore.bash docker-entry.bash /trans/
# Copy artifacts
COPY --from=0 /trans/target/scala-2.11/trans-server-akka.jar /trans/target/scala-2.11/trans-server-akka.jar

# Run entry (Run the server)
ENTRYPOINT ["/trans/docker-entry.bash"]
