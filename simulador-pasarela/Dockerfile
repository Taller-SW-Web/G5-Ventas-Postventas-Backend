# Imagen del simulador de pasarela de pagos. Se construye desde la raíz del repo:
#   docker build -f simulador-pasarela/Dockerfile -t simulador-pasarela .
# En Render: Docker, Root Directory vacío, Dockerfile Path = simulador-pasarela/Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/
COPY servicio-ventas/pom.xml servicio-ventas/
COPY servicio-postventa/pom.xml servicio-postventa/
COPY simulador-pasarela/pom.xml simulador-pasarela/
RUN mvn -q -pl simulador-pasarela -am dependency:go-offline -DskipTests || true
COPY simulador-pasarela/src simulador-pasarela/src
RUN mvn -q -pl simulador-pasarela -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/simulador-pasarela/target/simulador-pasarela-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
