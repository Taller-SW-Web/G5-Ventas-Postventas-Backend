# Hito 5 — G5 Ventas y Postventa: lo avanzado y lo que falta

**Líder del equipo:** Michael Orosco
**Fecha de corte:** 17/11/2026
**Presentación:** https://claude.ai/artifact/NsnknwaFJo5GofTzWcQEBr

---

## 1. Resumen en una línea

De los **5 requisitos** del Hito 5 tenemos **3 terminados y probados en producción**. Faltan **2**: pruebas de **performance** y el **mecanismo asíncrono**.

| # | Requisito | Estado | Evidencia |
|---|---|---|---|
| 1 | CI/CD con revisión de seguridad | ✅ Listo | PR #69 (backend) y PR #31 (frontend) en verde |
| 2 | API Management | ✅ Listo | Gateway en producción, versión v1.3.0 |
| 3 | Pruebas de integración | ✅ Listo | PR #70: 0 tests omitidos |
| 4 | Pruebas de performance (k6) | 🔴 Falta | — |
| 5 | Mecanismo asíncrono (RabbitMQ) | 🔴 Falta | — |

---

## 2. Lo que ya está hecho (explicado despacio)

### 2.1 CI/CD con revisión de seguridad ✅

**Qué es CI/CD:** cada vez que alguien sube código a GitHub, un "robot" (GitHub Actions) lo prueba y, si todo sale bien, lo publica solo en Render (backend) o en Vercel (frontend). Antes del Hito 5 el robot solo corría tests. Ahora **también revisa la seguridad**.

**Qué revisa ahora el robot:**

| Revisión | Pregunta que responde | Herramienta |
|---|---|---|
| Secretos | ¿Alguien subió una contraseña o un token por error? | gitleaks |
| Librerías | ¿Alguna librería que usamos tiene una falla conocida? | Trivy (backend) y npm audit (frontend) |
| Código | ¿Nuestro código tiene errores de seguridad? | CodeQL |
| Imágenes Docker | ¿El "paquete" que se instala en Render tiene fallas? | Trivy |
| Cabeceras web | ¿La página del front se protege del navegador? | Chequeo en producción (HSTS, X-Frame-Options, etc.) |

**La regla clave:** si cualquiera de estas revisiones encuentra algo grave, el pipeline se pone en **rojo** y **no se publica nada**.

**Esto ya pasó de verdad:** la primera vez que corrió, Trivy encontró fallas críticas en librerías (Tomcat, Netty y Bouncy Castle) y bloqueó la publicación. Las actualizamos a versiones corregidas. Tres fallas de Spring no tienen arreglo sin una migración grande; comprobamos que no nos afectan (usan funciones que no usamos) y lo dejamos documentado en el archivo `.trivyignore`.

**Dónde verlo:**
- Backend: https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/pull/69
- Frontend: https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Frontend/pull/31

### 2.2 API Management ✅

**Qué es:** todas las llamadas a nuestra API entran por **una sola puerta**, el **API Gateway** (https://g5-api-gateway.onrender.com). El gateway decide quién entra, cuánto puede usar y deja un rastro de cada llamada. Después reparte la llamada a M1 (Ventas, `/api/v1`) o a M2 (Postventa, `/api/v2`).

**Qué hace el gateway:**

| Función | Explicación simple | Cómo se ve |
|---|---|---|
| Controla el acceso | Sin token de G7 no entra | Responde **401** |
| Limita el uso (rate limit) | Máximo de llamadas por minuto: 300 si es un servicio, 120 si es un usuario y 60 por IP si no hay token | Al pasarse responde **429** con la cabecera `Retry-After` |
| Rastrea cada llamada | Cada llamada lleva un código único para seguirla en los logs | Cabecera `X-Correlation-Id` |
| Se protege | Timeouts, reintento en las lecturas y límite de 10 MB por llamada | — |
| Publica el manual | El contrato de la API en formato OpenAPI | `/openapi/ventas.yaml` |

**Probado en producción el 17/11:** sin token responde 401 `TOKEN_INVALIDO` con las cabeceras de rate limit y correlation-id. El catálogo OpenAPI responde 200.

### 2.3 Pruebas de integración ✅

**Qué es:** un test unitario prueba una pieza sola. Un **test de integración** prueba varias piezas juntas, por ejemplo **nuestro código + una base de datos PostgreSQL real**.

**Qué pasaba antes:** M2 tenía 3 grupos de tests de integración (calificaciones CSAT, eventos recibidos e idempotencia) que **se saltaban en GitHub** porque ahí no había base de datos. Aparecían 13 tests "omitidos".

**Qué hicimos:** ahora el pipeline levanta un **PostgreSQL 16 temporal** en cada corrida. Esos tests corren de verdad y la base se borra al terminar. No toca Supabase.

**Resultado (PR #70):**

| Servicio | Tests | Fallidos | Omitidos |
|---|---|---|---|
| servicio-ventas (M1) | 350 | 0 | 0 |
| servicio-postventa (M2) | 329 | 0 | **0** (antes 13) |
| api-gateway | 11 | 0 | 0 |

El gateway además tiene sus propios tests de integración: JWT, rate limit y ruteo.

---

## 3. Lo que falta (explicado despacio)

### 3.1 Pruebas de performance con k6 🔴

**Qué es:** medir **qué tan rápido responde** la API y **cuánta carga aguanta**. k6 es un programa que simula muchos usuarios llamando a la API al mismo tiempo y mide los tiempos.

**Qué hay que entregar:**
1. Un script k6 (archivo `.js`) con al menos estos escenarios:
   - **Carga normal:** por ejemplo, 20 usuarios durante 2 minutos consultando pedidos.
   - **Pico:** subir a 100 usuarios para ver cuándo empieza a fallar.
   - **Prueba de 5000 pedidos:** crear o consultar 5000 pedidos y medir.
2. Umbrales de aprobación, por ejemplo: el 95 % de las respuestas en menos de 800 ms y menos del 1 % de errores.
3. Un reporte con los resultados (tabla y captura) y una explicación.

**Lo que hay que saber antes:**
- Para llamar a la API **se necesita un token de G7** (de servicio o de un usuario de prueba). Sin token solo se puede medir health, los 401 y el rate limit.
- Render gratis es lento y "se duerme". Hay que despertarlo antes de medir y explicarlo en el reporte.
- **Ojo con el rate limit:** con el mismo token, después de 120–300 llamadas por minuto el gateway responde 429. Hay que probar contra los servicios directamente o explicar los 429 como evidencia de que el rate limit funciona.
- **No correr la prueba de 5000 pedidos contra la base de producción sin limpiar después.**

**Tiempo estimado:** unas 2–3 horas.

### 3.2 Mecanismo asíncrono con RabbitMQ 🔴

**Qué pasa hoy:** cuando M1 (Ventas) registra un pedido pagado, anulado o entregado, guarda un "evento" en una tabla (outbox). Cada 5 segundos un proceso lee esa tabla y se lo **envía a M2 por HTTP** (`POST /api/v2/eventos`). Funciona, pero si M2 está caído hay que reintentar a mano y los dos servicios quedan muy acoplados.

**Qué se propone:** poner un **intermediario de mensajes (RabbitMQ)** entre M1 y M2:
- M1 **publica** el evento en una cola y sigue con lo suyo.
- M2 **consume** los eventos de la cola cuando puede.
- Si M2 está caído, los mensajes **esperan en la cola** y no se pierden.
- Si un mensaje falla varias veces, va a una **cola de errores (DLQ)** para revisarlo.

```
Hoy:       M1 ──(HTTP cada 5 s)──> M2
Propuesta: M1 ──publica──> [ RabbitMQ: cola eventos-ventas ] ──consume──> M2
                                         └─ si falla 3 veces → cola de errores (DLQ)
```

**Dónde se aloja:** CloudAMQP (RabbitMQ gratis en la nube). G6 ya usa RabbitMQ, así que podemos reutilizar la experiencia.

**Qué hay que entregar:**
1. Una cuenta de CloudAMQP con un exchange, una cola y una DLQ.
2. M1: el outbox publica en RabbitMQ en lugar de llamar por HTTP.
3. M2: un consumidor que recibe el evento y reutiliza la lógica actual (`EventoPedidoService`, que ya evita duplicados).
4. Tests del publicador y del consumidor.
5. Variables en Render (URL de CloudAMQP) y la demo funcionando.

**Tiempo estimado:** medio día a un día.

---

## 4. Reparto sugerido del trabajo

> Es una propuesta: ajústenla según lo que cada uno domine. Cada parte está pensada para hacerse **sin pisarse**, en su propia rama y con su propio PR.

| Parte | Responsable | Qué hace | Depende de | Entregable |
|---|---|---|---|---|
| **A. Coordinación, informe y demo** | **Michael Orosco** (líder) | Pide el token a G7, revisa los PR, arma el informe final y la presentación, y guía la demo | — | Informe del Hito 5 y presentación |
| **B. k6: scripts** | **Joseph Purisaca** | Escribe los escenarios (carga normal, pico y 5000 pedidos) con sus umbrales | Token de G7 (parte A) | `performance/*.js` en el repo |
| **C. k6: ejecución y reporte** | **Luis Castro** | Corre las pruebas, guarda resultados y capturas, y explica los números (y los 429) | Parte B | Reporte con tabla y capturas |
| **D. RabbitMQ: infraestructura y M1** | **Luis Arroyo Tapia** | Crea CloudAMQP (exchange, cola y DLQ) y hace que el outbox de M1 publique en la cola | — | PR de M1 y variables de Render |
| **E. RabbitMQ: consumidor en M2** | **Fabrizio Huaytalla** | Hace que M2 consuma la cola, maneje errores y DLQ, y escribe sus tests | Acordar el formato del mensaje con la parte D | PR de M2 |

**Orden recomendado:**
1. **Hoy:** Michael pide el token a G7. D y E acuerdan el formato del mensaje (el mismo JSON que hoy va a `POST /api/v2/eventos`).
2. **En paralelo:** B escribe k6 mientras D y E construyen RabbitMQ.
3. **Después:** C corre k6 cuando B termina. D y E prueban juntos de punta a punta.
4. **Al final:** Michael junta todo en el informe y ensaya la demo.

---

## 5. Otros pendientes (no son del Hito 5, pero conviene cerrarlos)

| Pendiente | Qué hay que hacer | Sugerido |
|---|---|---|
| Permisos de M2 sin fusionar | Integrar `G5-back-permisos-seguridad.zip` (lectura de reembolsos, evidencias de ADMIN_VENTAS, reintento con la misma idempotency key) | Fabrizio, junto con la parte E |
| Despacho (G2) | En Render: `DESPACHO_URL=https://taller-api-gateway.onrender.com` y `DESPACHO_SIMULADO=false`, y pedir el scope `despachos:crear` a G7 | Michael |
| Scopes con G7 | Pedir `ventas:pedidos:consultar`, `ventas:pedidos:reemplazar`, `inventario:disponibilidad:leer`, `inventario:operaciones:leer`, el cliente `modulo-chatbot` y cuentas CLIENTE | Michael |
| Datos para G1 y G3 | Cargar pedidos para sus usuarios de prueba (`sub`) cuando los envíen | Luis Castro |
| Frontend | Quitar la carpeta `.vercel` del repo | Joseph |

---

## 6. Enlaces para verificar

- API Gateway (entrada general): https://g5-api-gateway.onrender.com/actuator/health
- Catálogo OpenAPI: https://g5-api-gateway.onrender.com/openapi/ventas.yaml
- Pipelines del backend: https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/actions
- PR de seguridad y gateway: https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/pull/69
- PR de integración con Postgres: https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/pull/70
- PR de seguridad del frontend: https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Frontend/pull/31
- Frontend en producción: https://g5-ventas-postventas-frontend.vercel.app
