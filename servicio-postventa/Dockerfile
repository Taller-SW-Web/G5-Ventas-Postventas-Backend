# Imagen de M2 (servicio-postventa). Se construye desde la raíz del repo:
#   docker build -f servicio-postventa/Dockerfile -t servicio-postventa .
# En Render: Docker, Root Directory vacío, Dockerfile Path = servicio-postventa/Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/
COPY servicio-ventas/pom.xml servicio-ventas/
COPY servicio-postventa/pom.xml servicio-postventa/
COPY simulador-pasarela/pom.xml simulador-pasarela/
RUN mvn -q -pl servicio-postventa -am dependency:go-offline -DskipTests || true
COPY servicio-postventa/src servicio-postventa/src
RUN mvn -q -pl servicio-postventa -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/servicio-postventa/target/servicio-postventa-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
