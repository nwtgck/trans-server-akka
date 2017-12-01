FROM nwtgck/pre-installed-sbt:0.13.15

LABEL maintainer="Ryo Ota <nwtgck@gmail.com>"

# Copy all things in this repo except files in .dockerignore
COPY . /trans

# Move to /trans
WORKDIR /trans

# Generate jar
RUN sbt assembly

# Run entry (Run the server)
ENTRYPOINT ["/trans/docker-entry.bash"]
