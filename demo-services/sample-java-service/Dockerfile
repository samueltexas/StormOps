# A aplicação é compilada previamente pelo Maven Wrapper.
# A imagem contém apenas o JAR e o runtime Java.
# Não precisamos de instalar Maven dentro do container.

FROM eclipse-temurin:21-jre

WORKDIR /app

# Executar como utilizador não privilegiado.
RUN groupadd --system stormops \
    && useradd --system --gid stormops stormops

COPY target/*.jar /app/app.jar

USER stormops

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
