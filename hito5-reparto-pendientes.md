# Hito 5 · Módulo D: Ventas y Postventa (G5)
## Estado de avance y reparto de tareas pendientes

| Campo | Detalle |
|---|---|
| Líder del equipo | Michael Orosco |
| Fecha de corte | 17 de noviembre de 2026 |
| Responsables de las tareas pendientes | Fabrizio Huaytalla, Johan |
| Integraciones con otros módulos | Michael Orosco |

---

## 1. Requisitos del Hito 5

El Hito 5 comprende cinco requisitos: CI/CD con revisión de seguridad, pruebas de integración, pruebas de performance, API Management y un mecanismo asíncrono de comunicación.

| N.º | Requisito | Estado |
|---|---|---|
| 1 | CI/CD con revisión de seguridad | Completado |
| 2 | API Management | Completado |
| 3 | Pruebas de integración | Completado |
| 4 | Pruebas de performance | Completado en parte |
| 5 | Mecanismo asíncrono | Pendiente |

---

## 2. Trabajo completado

### 2.1 CI/CD con revisión de seguridad

Los pipelines de backend (`ci.yml`) y frontend (`ci-cd.yml`) incorporan una revisión de seguridad automatizada. Si alguna revisión encuentra un hallazgo grave, el pipeline se detiene y no se realiza el despliegue ni se publica una versión.

| Control | Herramienta | Alcance |
|---|---|---|
| Secretos expuestos | gitleaks | Todo el historial de git (backend y frontend) |
| Dependencias vulnerables | Trivy (backend), npm audit (frontend) | Librerías Maven y npm |
| Imágenes Docker | Trivy | Sistema operativo y librerías de cada imagen |
| Análisis estático de código | CodeQL | Java y TypeScript |
| Cabeceras de seguridad | Verificación en producción | HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy y Permissions-Policy |

En su primera ejecución, la revisión detectó vulnerabilidades críticas en librerías de terceros. Se corrigieron mediante actualización de versiones:

| Librería | Versión anterior | Versión corregida |
|---|---|---|
| Apache Tomcat | 10.1.x | 10.1.62 |
| Netty | 4.1.135 | 4.1.141 |
| Bouncy Castle | 1.80.2 | 1.87 |

Tres vulnerabilidades de Spring Framework solo tienen corrección en la versión 7 (Spring Boot 4), lo que exige una migración mayor. Se verificó que no afectan al sistema, porque corresponden a funcionalidades que no se utilizan (XsltView, Server-Sent Events y predicados de cabecera en Functional Endpoints). Quedaron documentadas como riesgo aceptado en el archivo `.trivyignore`.

**Evidencia:** PR #69 (backend) y PR #31 (frontend), con todos los controles en verde.

### 2.2 API Management

Todas las solicitudes al backend ingresan por un único punto de entrada: el API Gateway (`https://g5-api-gateway.onrender.com`).

| Función | Implementación |
|---|---|
| Control de acceso | Token JWT de G7 obligatorio. Sin token, la respuesta es 401 |
| Limitación de uso (rate limit) | Token bucket: 300 solicitudes por minuto con token de servicio, 120 con token de usuario y 60 por IP sin token. Al superar el límite, la respuesta es 429 con la cabecera `Retry-After` |
| Trazabilidad | Cabecera `X-Correlation-Id` en cada solicitud y registro de acceso |
| Resiliencia | Tiempos máximos de espera, reintento en lecturas y límite de 10 MB por solicitud |
| Catálogo | Contrato OpenAPI publicado en `/openapi/ventas.yaml` |

**Evidencia:** verificación en producción del 17/11/2026 y versión v1.3.0.

### 2.3 Pruebas de integración

El pipeline levanta una base de datos PostgreSQL 16 temporal en cada ejecución. Las pruebas de integración de Postventa (calificaciones CSAT, eventos recibidos e idempotencia), que antes se omitían, ahora se ejecutan contra una base de datos real. La base de producción (Supabase) no se utiliza.

| Servicio | Pruebas | Fallidas | Omitidas |
|---|---|---|---|
| servicio-ventas (M1) | 350 | 0 | 0 |
| servicio-postventa (M2) | 329 | 0 | 0 |
| api-gateway | 11 | 0 | 0 |

**Evidencia:** PR #70.

### 2.4 Pruebas de performance (parcial)

Se implementaron pruebas de carga con k6 (carpeta `performance/`), que se ejecutan desde GitHub Actions (workflow "Performance (k6)").

| Prueba | Resultado |
|---|---|
| Carga normal (20 usuarios durante 1 minuto) y pico (100 usuarios) | 3601 solicitudes, 0 % de errores, tiempo promedio de 603 ms y p95 de 1,4 s. Se cumplió la meta del pico (p95 menor a 1,5 s). No se alcanzó la meta general de 800 ms debido a las limitaciones del plan gratuito de Render |
| Rate limit | De 150 solicitudes en ráfaga, 66 fueron rechazadas con 429. El límite funciona correctamente |

**Evidencia:** corridas #4 y #5 del workflow "Performance (k6)".

---

## 3. Tareas pendientes y responsables

### 3.1 Fabrizio Huaytalla

#### Tarea F1. Mecanismo asíncrono: consumidor en Postventa (M2)

**Contexto:** actualmente, Ventas (M1) registra los eventos de pedido (pagado, anulado y entregado) en una tabla outbox, y cada 5 segundos un proceso los envía a Postventa (M2) por HTTP (`POST /api/v2/eventos`). El objetivo es reemplazar esa llamada directa por una cola de mensajes RabbitMQ.

**Actividades:**
1. Agregar a `servicio-postventa` la dependencia de Spring AMQP y la configuración de conexión, tomando la URL de una variable de entorno.
2. Implementar un consumidor que lea la cola de eventos y reutilice la lógica existente de `EventoPedidoService`, que ya evita procesar un mismo evento dos veces.
3. Configurar la cola de errores (DLQ): un mensaje que falle tres veces debe pasar a esa cola en lugar de perderse.
4. Escribir las pruebas del consumidor: un evento válido, un evento duplicado y un evento con error que termina en la DLQ.
5. Acordar con Johan el formato del mensaje antes de empezar. Se recomienda mantener el mismo JSON que hoy recibe `POST /api/v2/eventos`.

**Entregable:** pull request en `servicio-postventa` con el pipeline en verde.

#### Tarea F2. Verificación de reintentos idempotentes en Postventa

**Contexto:** los permisos de M2 previstos (lectura del detalle de reembolsos y carga de evidencias para ADMIN_VENTAS) ya están integrados en `main` (`SecurityConfig.java`). Solo queda confirmar el comportamiento de los reintentos.

**Actividades:**
1. Verificar que, cuando el gestor reintenta una operación (por ejemplo, registrar un reembolso), se envíe la misma `Idempotency-Key` y no se genere un registro duplicado.
2. Si no ocurre así, corregirlo y agregar una prueba que lo cubra.

**Entregable:** confirmación por escrito o pull request con la corrección y el pipeline en verde.

### 3.2 Johan

#### Tarea J1. Mecanismo asíncrono: infraestructura y publicación desde Ventas (M1)

**Actividades:**
1. Crear la instancia gratuita de RabbitMQ en CloudAMQP, con un exchange, la cola de eventos de pedido y su cola de errores (DLQ).
2. Agregar a `servicio-ventas` la dependencia de Spring AMQP y la configuración de conexión, tomando la URL de una variable de entorno.
3. Modificar el proceso del outbox para que publique cada evento en RabbitMQ en lugar de llamar por HTTP a M2. El registro en la tabla outbox se mantiene.
4. Escribir las pruebas del publicador.
5. Configurar la variable de conexión en Render para M1 y M2. La URL de CloudAMQP no debe registrarse en el repositorio.
6. Una vez integrados F1 y J1, realizar la prueba de punta a punta:
   - Registrar un pedido pagado y verificar que el evento llega a M2.
   - Detener M2, generar un evento y verificar que el mensaje permanece en la cola hasta que M2 vuelve a estar disponible.
7. Obtener las capturas de evidencia: la cola en CloudAMQP con mensajes procesados y el resultado en M2.

**Entregable:** pull request en `servicio-ventas` con el pipeline en verde, más las capturas de la prueba.

#### Tarea J2. Prueba de performance: 5000 consultas de pedidos

**Dependencia:** requiere un token de usuario con rol ADMIN_VENTAS, que Michael Orosco gestionará con G7.

**Actividades:**
1. Registrar el token como secret del repositorio con el nombre `K6_TOKEN_GESTOR` (Settings → Secrets and variables → Actions). El token no debe escribirse en el código ni compartirse en el grupo.
2. Ejecutar en GitHub Actions el workflow "Performance (k6)" con la opción `pedidos-consulta`.
3. Registrar los resultados: número de solicitudes, porcentaje de errores, tiempo promedio y p95.
4. Obtener la captura del resumen del workflow.

**Entregable:** captura del resumen y tabla de resultados para el informe.

#### Tarea J3. Limpieza del repositorio de frontend

**Actividades:** eliminar del repositorio la carpeta `.vercel` y agregarla a `.gitignore`.

**Entregable:** pull request en el repositorio de frontend con el pipeline en verde.

### 3.3 Michael Orosco (integraciones)

Las integraciones con otros módulos y las gestiones asociadas quedan a cargo del líder del equipo:

- Gestión con G7 de permisos, cuentas de prueba y el token para la tarea J2.
- Integración con Despacho (G2): configuración en Render y prueba conjunta.
- Prueba conjunta de compra por canal con G7.
- Carga de datos de prueba para G1 y G3.
- Actualización final de la presentación y del informe del Hito 5.

---

## 4. Orden de ejecución recomendado

1. Fabrizio y Johan acuerdan el formato del mensaje de eventos.
2. En paralelo:
   - Fabrizio: F1 y, en paralelo, la verificación F2.
   - Johan: J1 y J3.
3. Johan ejecuta la prueba de punta a punta de J1 cuando F1 y J1 estén integrados.
4. Johan ejecuta J2 cuando reciba el token.
5. Cada responsable entrega a Michael Orosco las capturas de evidencia para la presentación.

## 5. Criterios de aceptación

- Todo cambio de código se integra mediante pull request con el pipeline de CI en verde, incluida la revisión de seguridad.
- No se registran credenciales, tokens ni URLs de conexión en el repositorio: se usan variables de entorno o secrets.
- Cada tarea se entrega con evidencia verificable: captura del pipeline, de la cola de mensajes o del resumen de k6, según corresponda.

---

## 6. Referencias

| Recurso | Enlace |
|---|---|
| API Gateway | https://g5-api-gateway.onrender.com/actuator/health |
| Pipelines del backend | https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/actions |
| PR de seguridad y API Management | https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/pull/69 |
| PR de pruebas de integración | https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Backend/pull/70 |
| PR de seguridad del frontend | https://github.com/Taller-SW-Web/G5-Ventas-Postventas-Frontend/pull/31 |
| Frontend en producción | https://g5-ventas-postventas-frontend.vercel.app |
| Presentación del Hito 5 | https://www.canva.com/design/DAHbLvwSKwY/919OSHh7iYfv-u792Vwjjg/edit |
