# J1 — Eventos de Ventas hacia Postventa

Implementación basada en `acuerdos-mecanismo-asincrono.md` (17/11/2026).

## Selección del transporte

| Variable | Valor | Efecto en M1 |
|---|---|---|
| `EVENTOS_ASINCRONOS` | `false` (por defecto) | Mantiene el envío HTTP a `POST /api/v2/eventos` |
| `EVENTOS_ASINCRONOS` | `true` | Publica en RabbitMQ con una conexión propia |
| `EVENTOS_RABBITMQ_URL` | URL privada AMQP/AMQPS | Conexión de eventos compartida con M2 |
| `POSTVENTA_SIMULADO` | `false` | Habilita el scheduler; en modo simulado no se envían eventos |

La URL solo se configura en el entorno local o en Render. No se registra en Git.
`spring.rabbitmq.*` y las variables `RABBITMQ_*` siguen perteneciendo a Productos.
La conexión y la plantilla de eventos son objetos internos, no beans de esos tipos;
así Spring Boot conserva la conexión, plantilla y listener de Productos.

## Publicación y confirmación

M1 declara solamente `ventas.eventos` (topic, durable, sin auto-delete).
M2 declara `postventa.eventos-pedido.q`, su binding `pedido.*`,
`ventas.eventos.dlx` y `postventa.eventos-pedido.dlq`.

- El cuerpo son los bytes UTF-8 del `payload` original del outbox, sin serializarlo otra vez.
- `message_id` conserva `idEvento`; `type` y routing key conservan el tipo del evento.
- El mensaje es persistente y su content type es `application/json`.
- Solo se devuelve `ACEPTADO` después de un confirm positivo y sin mensaje retornado.
- `mandatory` evita tratar como entregado un mensaje que no tiene cola de destino.
- Un error de conexión, nack, retorno o timeout deja `enviado_en` vacío, suma un intento
  y corta la vuelta. La siguiente vuelta conserva el UUID y el payload originales.
- Se conserva el límite existente de 10 intentos. Al agotarlo hace falta revisión manual;
  no se promete un reintento ilimitado ni se resetean eventos históricos automáticamente.
- Si M2 ya procesó el mensaje pero M1 no pudo guardar la confirmación, puede reenviarse.
  La idempotencia de M2 por `idEvento` evita duplicar sus efectos.

La espera del confirm es de 5 segundos y el timeout de conexión de 10 segundos.
Las excepciones de conexión no se imprimen con la URL privada.
El health de RabbitMQ permanece desactivado según el acuerdo.

## Comprobaciones locales — 19/11/2026

`mvn -B -pl servicio-ventas test`: **401 pruebas, 0 fallos, 0 errores, 0 omitidas** (incluye tres casos TLS). El verify anterior del publicador aprobo 398 pruebas.

Se comprobaron los tres tipos de evento, bytes y metadatos, ack, nack,
mensaje sin ruta, timeout, broker no disponible, guardado del outbox después del confirm,
reintento del mismo evento, selección HTTP/RabbitMQ y scheduler deshabilitado en modo simulado.
Un contexto de prueba con los componentes reales de Cupones y ConsumidorProductos
confirma que conservan la conexión de Productos al activar la de eventos.

Estas comprobaciones utilizan mocks del broker y un contexto reducido de Spring.
No constituyen una prueba real de CloudAMQP ni una prueba conjunta M1/M2.

## Pendiente de prueba conjunta y aprobación

1. Crear una única instancia de CloudAMQP del equipo y compartir el acceso por privado.
2. Configurar la URL para ambos servicios; mantener el interruptor desactivado durante la revisión.
3. Tras la aprobación de integración/despliegue, activar primero M2 y comprobar cola, binding y DLQ.
4. Activar M1 con `POSTVENTA_SIMULADO=false` y `EVENTOS_ASINCRONOS=true`.
5. Probar pago válido, duplicado, M2 detenido/reanudado y mensaje inválido hacia la DLQ.
6. Registrar versiones y capturas sin credenciales. Un confirm de publicación no demuestra
   por sí solo que M2 haya procesado el evento.

Para el ejemplo válido utilizar un canal admitido por el código (`MARKETPLACE`, `CHATBOT`
o `RETAIL`); el `WEB` del ejemplo del acuerdo no es un canal admitido actualmente.
Conservar también el significado actual de `items.importe` como importe total de la línea.

La proyección de ventas por producto/vendedor del PR #41 es un trabajo aparte y no está
incluida en esta rama. La prueba conjunta debe comprobar los efectos disponibles en M2
sin dar por integrada esa corrección.


## Validacion TLS

El primer arranque de M1 mostro que el cliente AMQP aceptaba cualquier certificado al
configurar amqps directamente con setUri. Ahora utiliza SSLContext.getDefault()
(confianza del JDK) y verifica el nombre del servidor. Se conservan el puerto TLS
5671 por defecto, puertos explicitos y vhosts codificados. Las tres pruebas nuevas
no abren conexiones y comprueban TLS, hostname, puerto y vhost.
La confirmacion del primer envio real con esta configuracion queda pendiente del ensayo.
