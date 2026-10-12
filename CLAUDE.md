# CLAUDE.md — Backend Módulo D Ventas y Postventa (G5)

Contexto para Claude Code en este repo. Léelo completo antes de tocar código.

## 1. Qué es

Backend del Módulo D (Ventas y Postventa) del marketplace multicanal de artículos deportivos, curso Taller de Construcción de Software Web, UNMSM. Java 21, Spring Boot 3.5.6, Spring Cloud 2025.0.0, Maven multimódulo.

| Módulo | Puerto | Paquete raíz | Qué hace |
|---|---|---|---|
| `api-gateway` | 8080 | `gateway` | Entrada pública. Rutea por recurso: `/api/*/pedidos/**` → M1; devoluciones, reembolsos, csat, reclamos, dashboard → M2 |
| `servicio-ventas` (M1) | 8081 | `ventas` | F1 Ciclo de vida del pedido (Michael), F2 Anulación (Luis Arroyo). Esquema `ventas` |
| `servicio-postventa` (M2) | 8082 | `postventa` | F3 Devoluciones (Joseph), F4 Reembolsos (Luis Alejandro), F5 CSAT (Johan), F6 Reclamos y dashboard (Fabrizio). Esquema `postventa` |
| `simulador-pasarela` | 8083 | `pasarela` | Simula cobros (avisa a M1 por 1.2) y extornos (los pide M2) |

Base de datos: Supabase PostgreSQL 17, proyecto `nsfrwxrjidyckgfriyhv`. Database-per-Service: M1 nunca lee `postventa` y M2 nunca lee `ventas`; se comunican por HTTP o eventos.

## 2. Fuentes de verdad

Repo de documentación: `Taller-SW-Web/G5-Ventas-Postventas` (clonarlo al lado de este). Si el código y la documentación no coinciden, manda la documentación; avisa en vez de "arreglar" la spec.

| Tema | Archivo en el repo de documentación |
|---|---|
| Decisiones cerradas (léelo primero) | `AGENTS.md`, sección 2 |
| Contratos | `specs/api/api-ventas.md` (M1), `specs/api/api-postventas.md` (M2) |
| Reglas y criterios de aceptación | `specs/especificaciones/funcionales/ESP-01` a `ESP-16` |
| Máquina de estados del pedido | `ESP-03`, sección 7.2 |
| Tablas reales | `diseno/diagramas/bd/diagrama-fisico.md` y `infra/bd/ventas_postventa_schema.sql` |
| Acuerdos con otros módulos | `integraciones/ACUERDO_G1_MARKETPLACE.md`, `ACUERDO_G4_RETAIL.md`, `ACUERDO_G6_PRODUCTOS.md`, `ACUERDO_G7_SEGURIDAD.md` |
| Validación de tokens | Repo `Taller-SW-Web/Modulo-de-Seguridad`, `specs/kit-integracion.md` (manda sobre cualquier otra fuente) |

## 3. Comandos

```bash
cp .env.example .env                       # completar DB_URL, DB_USER, DB_PASSWORD
mvn clean package                          # compila los 4 módulos y corre las pruebas
mvn -pl servicio-ventas -am test           # solo M1
mvn -pl servicio-ventas spring-boot:run    # levantar M1
docker build -f servicio-ventas/Dockerfile -t servicio-ventas .
```

Para probar sin tokens en local: `SEGURIDAD_HABILITADA=false`. Con eso, `/pedidos/me` usa `CLIENTE_PRUEBA`.

## 4. Reglas de código

- Capas: Controller → Service → Repository / Client (`integracion/`). El controller no toca repositorios; el service no conoce HTTP.
- DTOs como `record` en `dto/`, con los nombres de campo **exactos** del contrato. Nunca devolver una entidad.
- Errores: siempre `ErrorResponse` (`codigo`, `mensaje`, `timestamp`, `detalles`) vía las excepciones de `compartido/` (`SolicitudInvalidaException` 400, `RecursoNoEncontradoException` 404, `ConflictoException` 409).
- Estados, canal y actores: siempre su enum, nunca texto suelto. Valores iguales al CHECK de la base.
- Fechas: `OffsetDateTime` en UTC. JSON en ISO-8601.
- `spring.jpa.hibernate.ddl-auto=validate`. **Nunca** `update`. Un cambio de tabla es una migración SQL en Supabase + entidad + `diagrama-fisico.md` + `ventas_postventa_schema.sql`, todo junto.
- El estado del pedido solo lo cambia `MaquinaEstados.cambiar(...)` (valida la matriz de ESP-03, guarda historial y evento outbox en la misma transacción).
- M1 no importa nada de `postventa` y viceversa (`ArquitecturaTest` lo verifica).
- Commits: `feat(f1): ...`, `fix(seguridad): ...`, `test(f1): ...`. Rama propia, PR hacia `main`.

## 5. Identidad y seguridad (acuerdo A10 con Seguridad, AGENTS.md puntos 9, 10 y 11)

- Identidad de cualquier persona = UUID del claim `sub`. `id_cliente`, `id_vendedor`, `id_solicitante`, `id_autorizador` son `uuid` en la base (migración `ids_usuario_a_uuid` **ya aplicada**) y `UUID` en Java.
- **Token de usuario**: `tipo: "acceso"`, RS256, `iss` exacto, `exp`, `iat`, `jti`, `sub` UUID, `roles` (lista). **No trae `aud`; no inventarlo.** Se autoriza por rol: `ROLE_CLIENTE`, `ROLE_ADMIN_VENTAS` ("Gestor" = `ADMIN_VENTAS`).
- **Token de servicio**: `tipo: "servicio"`, `aud` incluye `api-ventas` (M1) o `api-postventa` (M2), `scope` separado por espacios. Se autoriza por `SCOPE_<scope>`.
- `ventas:pedidos:leer` y `ventas:admin` **no son scopes**; son roles del token de usuario.
- Crear (1.1) y anular (1.7) usan el token de servicio del canal; el cliente viaja en `contacto.clienteId`. Única excepción: `GET /pedidos/me`, que usa el token del cliente y filtra por `sub`.
- Scopes por cliente: `modulo-marketplace`/`modulo-retail` → `ventas:pedidos:crear`, `ventas:pedidos:anular` (+ escrituras de postventa); `modulo-chatbot` → lo mismo + `ventas:pagos:webhook`; `modulo-despacho` → `ventas:pedidos:estado`; `simulador-pasarela` → `ventas:pagos:webhook`; `modulo-ventas` → `postventa:reembolsos:procesar`.
- El mock de Seguridad (Prism) **no firma tokens**. Para probar, las pruebas firman sus propios RS256 (ver `MisPedidosSeguridadTest`).
- Datos de prueba de Seguridad (kit §6): cliente activo `11111111-1111-1111-1111-111111111111`, vendedor `22222222-...`, desactivado `33333333-...`; `client_secret` de prueba `secreto-de-prueba`.

Implementación en M1: `ventas/compartido/SecurityConfig.java` (reglas por ruta, decoder con JWKS + `JwtIssuerValidator` + `ValidadorToken`, CORS, 401/403 con `ErrorResponse`) y `ValidadorToken.java`.

## 6. Estado actual (rama `demo-pedidos-me`)

Hecho y con pruebas (falta correr `mvn clean package`: se escribió sin acceso a Maven Central):

- `Pedido.idCliente` / `idVendedor` → `UUID`; `moneda` mapeada como `char(3)`; `creadoEn` mapeado (lo llena la base).
- Seguridad de M1 reescrita según A10 y el kit de Seguridad.
- `GET /api/v1/pedidos/me` (1.4b, acuerdo H-03): rol `CLIENTE`, filtro por `sub`, `estado` opcional, `page` desde 1, `pageSize` 1–50, respuesta `{items, total, page, pageSize}`.
- Pruebas: `ValidadorTokenTest`, `PedidoServiceTest`, `MisPedidosSeguridadTest` (tokens RS256 reales: 401 sin token, 200 cliente, 403 sin rol, 401 otro emisor, 403 token de servicio en `/me`).
- `servicio-ventas/Dockerfile` para Render y `servicio-ventas/datos-demo/pedidos-demo.sql` (probado en Supabase).

Todo lo demás de M1 y todo M2 responde `501` (`NoImplementadoException`).

## 7. Tareas, en orden

### P0 — Demo de semana 8 con Marketplace (login real + `/pedidos/me`)

1. `mvn clean package` y corregir lo que no compile.
2. Pedir a Seguridad las URLs reales de `SECURITY_ISSUER` y `SECURITY_JWKS_URL` (HTTPS en nube). El `iss` debe coincidir exacto.
3. Pedir a Marketplace el `sub` de su usuario de prueba con rol `CLIENTE` y su dominio (para `CORS_ORIGINS`).
4. Correr `datos-demo/pedidos-demo.sql` en Supabase con ese `sub`.
5. Desplegar M1 en Render: Docker, Dockerfile Path `servicio-ventas/Dockerfile`, variables `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SECURITY_ISSUER`, `SECURITY_JWKS_URL`, `CORS_ORIGINS`, `SEGURIDAD_HABILITADA=true`. Health: `/actuator/health`.
6. Prueba conjunta en nube al menos 48 h antes de la presentación.

Para la demo, Marketplace llama directo a M1. El gateway queda para después (validar luego que también acepte tokens de servicio y no duplique CORS).

### P1 — Seguridad de M2 (mismo patrón que M1)

- Copiar `ValidadorToken` y el decoder de M1, con `audiencia: api-postventa`.
- Reglas: `/devoluciones/me` y `/reclamos/me` → `ROLE_CLIENTE`; listados por `clienteId`/`documento`, resolución de devoluciones, respuesta de reclamos y dashboard → `ROLE_ADMIN_VENTAS`; `POST /reembolsos` → `SCOPE_postventa:reembolsos:procesar` o `ROLE_ADMIN_VENTAS`; escrituras de canales con sus scopes; `POST /csat` con token de cliente (se retiró `postventa:csat:escribir`). Revisar `api-postventas.md` antes.
- Quitar `spring.security.oauth2.*` de `servicio-postventa/src/main/resources/application.yml`.

### P1 — F1 crear pedido (1.1), por canal

- Cabecera `Idempotency-Key` obligatoria (acuerdo H-02). **Falta la columna `ventas.pedido.idempotency_key`** (única) en la base: migración pendiente.
- Marketplace: crea `CREADO`, reserva stock con Productos, y M1 invoca al simulador de pasarela (AGENTS.md punto 8).
- Chatbot: crea `CREADO`; el pago llega por 1.2 con su token de servicio.
- Retail (`ACUERDO_G4_RETAIL.md`): nace `PAGADO` y en la misma operación pasa a `ENTREGADO` (actor `CLIENTE`, motivo `ENTREGA_INMEDIATA_EN_TIENDA`); `envio` opcional, `tiendaId` obligatorio; datos del POS en `pago`; `fechaVenta` opcional (solo offline, UTC, máx. 72 h); no se reserva stock ni se llama a 1.2.
- Código del pedido: `PED-AAAA-NNNNN`. `codigo` es `varchar(30)` y `NOT NULL`: el temporal debe caber (ej. `"TMP-" + 24 caracteres`), no `"TMP-" + UUID` completo.
- `CodigoPublico.id("CLI-...")` ya no aplica a clientes: el cliente llega como UUID.

### P2 — Integraciones

- Productos (G6): reserva/confirmación/liberación por HTTP y resultados por RabbitMQ (`po.sales.q`); cupón solo por eventos (`po.commands.x` / `po.results.x`). Topología aún candidata (`0.6.0-rc.1`).
- Seguridad: `GET /usuarios/{clienteId}` con `usuarios:leer` antes de anular y reembolsar.
- Despacho: `despachos:crear` / `despachos:cancelar`.
- Webhook H-05 `pedidos.envio.estado_actualizado` hacia Marketplace (diseño de reintentos pendiente).

## 8. Errores conocidos a evitar

- Agregar `aud` a la validación de tokens de usuario: rompe todos los logins.
- Usar `spring.security.oauth2.resourceserver.jwt.issuer-uri` en M1: llama al descubrimiento al arrancar; el decoder se arma a mano en `SecurityConfig`.
- Paginación de `/pedidos/me` con `pagina`/`tamano`: este endpoint usa `page`/`pageSize` desde 1 (excepción acordada).
- Crear tablas o columnas desde JPA.
