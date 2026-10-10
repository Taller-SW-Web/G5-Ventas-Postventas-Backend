# Backend - Módulo D Ventas y Postventa

Java 21, Spring Boot 3.5, Maven multimódulo.

| Aplicación | Puerto | Qué hace |
|---|---|---|
| `servicio-ventas` (M1) | 8081 | F1 Pedidos y F2 Anulaciones |
| `servicio-postventa` (M2) | 8082 | F3 a F6 (pendiente) |
| `api-gateway` | 8080 | Entrada pública (pendiente) |

## Ejecutar

```bash
cp .env.example .env
mvn clean package
mvn -pl servicio-ventas spring-boot:run
```
