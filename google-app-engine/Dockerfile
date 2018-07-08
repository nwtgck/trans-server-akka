# (from: https://qiita.com/mtoyoshi/items/7d6ccfa8b2cbf04e73df)

FROM gcr.io/google_appengine/openjdk8
VOLUME /tmp
ADD trans-server-akka.jar app.jar
CMD [ "java","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar", "8080", "4343"]
