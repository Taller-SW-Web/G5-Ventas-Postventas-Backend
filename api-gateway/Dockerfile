# Imagen del API Gateway. Se construye desde la raíz del repo:
#   docker build -f api-gateway/Dockerfile -t api-gateway .
# En Render: Docker, Root Directory vacío, Dockerfile Path = api-gateway/Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/
COPY servicio-ventas/pom.xml servicio-ventas/
COPY servicio-postventa/pom.xml servicio-postventa/
COPY simulador-pasarela/pom.xml simulador-pasarela/
RUN mvn -q -pl api-gateway -am dependency:go-offline -DskipTests || true
COPY api-gateway/src api-gateway/src
RUN mvn -q -pl api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/api-gateway/target/api-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
