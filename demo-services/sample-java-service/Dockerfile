FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.source="https://github.com/samueltexas/StormOps" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system stormops \
    && useradd --system --gid stormops stormops

COPY target/stormops-demo.jar /app/app.jar

USER stormops

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
