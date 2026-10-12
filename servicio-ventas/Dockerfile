# Imagen de M1 (servicio-ventas). Se construye desde la raíz del repo:
#   docker build -f servicio-ventas/Dockerfile -t servicio-ventas .
# En Render: Docker, Root Directory vacío, Dockerfile Path = servicio-ventas/Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/
COPY servicio-ventas/pom.xml servicio-ventas/
COPY servicio-postventa/pom.xml servicio-postventa/
COPY simulador-pasarela/pom.xml simulador-pasarela/
RUN mvn -q -pl servicio-ventas -am dependency:go-offline -DskipTests || true
COPY servicio-ventas/src servicio-ventas/src
RUN mvn -q -pl servicio-ventas -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/servicio-ventas/target/servicio-ventas-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
