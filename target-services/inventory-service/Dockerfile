FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/inventory-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
