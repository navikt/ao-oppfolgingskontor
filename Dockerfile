FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21

ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/install/*/lib /lib
EXPOSE 8080

ENTRYPOINT ["java", "-cp", "/lib/*", "no.nav.ApplicationKt"]
