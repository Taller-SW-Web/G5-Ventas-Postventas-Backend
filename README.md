# Backend - Módulo D Ventas y Postventa

Java 21, Spring Boot 3.5, Maven multimódulo.

| Aplicación | Puerto | Paquete | Qué hace |
|---|---|---|---|
| `api-gateway` | 8080 | `gateway` | Entrada pública: valida el JWT y reparte a M1 o M2 |
| `servicio-ventas` (M1) | 8081 | `ventas` | F1 Pedidos y F2 Anulaciones |
| `servicio-postventa` (M2) | 8082 | `postventa` | F3 Devoluciones, F4 Reembolsos, F5 CSAT, F6 Reclamos y dashboard |

## Ejecutar

```bash
cp .env.example .env          # completar DB_URL, DB_USER y DB_PASSWORD
mvn clean package             # compila los tres módulos y corre las pruebas
```

Cada servicio en una terminal:

```bash
mvn -pl servicio-ventas spring-boot:run
mvn -pl servicio-postventa spring-boot:run
mvn -pl api-gateway spring-boot:run
```

Prueba rápida por el gateway (mientras no esté programado responde 501):

```bash
curl -i http://localhost:8080/api/v1/pedidos/PED-2026-00001
```
