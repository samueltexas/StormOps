# Package the Java 21-compatible JAR produced by the tested Maven build.
FROM registry.k8s.io/kubectl:v1.36.1 AS kubectl
FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.source="https://github.com/samueltexas/StormOps"       org.opencontainers.image.licenses="MIT"
RUN groupadd --system stormops && useradd --system --gid stormops --create-home stormops
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl
WORKDIR /app
COPY target/stormops-api.jar /app/app.jar
USER stormops
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
