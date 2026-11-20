FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home mediaflow
COPY --from=build /workspace/target/mediaflow-ai-*.jar /app/mediaflow-ai.jar
USER mediaflow
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/mediaflow-ai.jar"]
