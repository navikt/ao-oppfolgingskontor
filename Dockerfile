FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-24

ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/libs/*.jar ./
EXPOSE 8080
CMD ["-jar","ao-oppfolgingskontor-all.jar"]
