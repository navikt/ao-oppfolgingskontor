FROM busybox:1.36.1-uclibc as busybox

FROM gcr.io/distroless/java23

COPY --from=busybox /bin/sh /bin/sh
COPY --from=busybox /bin/printenv /bin/printenv

ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/libs/*.jar ./
COPY docs/index.html ./docs/index.html
EXPOSE 8080
USER nonroot
CMD ["ao-oppfolgingskontor-all.jar"]
