FROM busybox:1.36.1-uclibc as busybox

FROM gcr.io/distroless/java21

COPY --from=busybox /bin/sh /bin/sh
COPY --from=busybox /bin/printenv /bin/printenv
COPY --from=busybox /bin/ping /bin/ping

ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/libs/*.jar ./
EXPOSE 8080
USER nonroot
CMD ["ao-oppfolgingskontor-all.jar"]
