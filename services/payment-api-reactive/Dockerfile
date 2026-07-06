# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- run ----
FROM eclipse-temurin:25-jre AS run
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
# OpenTelemetry auto-instrumentation agent. Activated only when JAVA_TOOL_OPTIONS references it
# (set in docker-compose), so plain `java -jar` and test runs are unaffected.
ADD https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.20.0/opentelemetry-javaagent-2.20.0.jar /otel/opentelemetry-javaagent.jar
RUN chmod 644 /otel/opentelemetry-javaagent.jar
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
