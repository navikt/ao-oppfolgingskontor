FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21

ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/install/*/lib /lib
EXPOSE 8080
# Add your preferred Logback config explicitly
COPY src/main/resources/logback.xml /app/logback.xml

ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback.xml", "-cp", "/lib/*", "no.nav.ApplicationKt"]
