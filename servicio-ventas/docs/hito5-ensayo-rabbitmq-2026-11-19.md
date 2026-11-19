# J1 — Ensayo de transporte con RabbitMQ real (19/11/2026)

## Entorno y alcance

- Rama: `hito5/johan-publicador-rabbitmq`; publicador probado desde `9afd721`.
- Ventas local: `127.0.0.1:18081`. Postventa local: `127.0.0.1:18082`.
- PostgreSQL 16 local: contenedor `f5-eventos-test`, base `f4_integracion_johan`.
- RabbitMQ real en CloudAMQP: instancia `g5-ventas-postventa-hito5`.
- Ambos servicios con eventos asíncronos activados; sin cambios en Render o Supabase.

Se insertó un fixture identificado como `ENSAYO LOCAL J1` en el esquema local de Ventas,
con pedido, detalle, pago, historial y evento en una misma transacción SQL.
El scheduler real leyó el outbox, el publicador real lo envió a CloudAMQP y el consumidor
real de M2 lo registró y actualizó el agregado local.
Esto prueba el transporte y su efecto en M2. No prueba la creación por la API autenticada,
el token de G7 ni una compra desde la caja de Retail.

## Resultado observado

| Dato | Valor |
|---|---|
| Evento | `6e46b451-cd8a-4606-b5d5-f08ca12107aa` |
| Tipo | `pedido.pagado` |
| Pedido local | `PED-2026-00001` |
| Canal | `MARKETPLACE` |
| Fecha del evento | `2026-11-19T07:18:55.611520Z` |
| Outbox enviado_en | `2026-11-19T07:19:00.191846Z` |
| Intentos fallidos | `0` |
| M2 recibido_en | `2026-11-19T07:19:00.306784Z` |
| Contador pedidos_pagados antes/después | `0 → 1` |

El UUID es el mismo en `ventas.outbox_evento` y `postventa.evento_recibido`.
El agregado corresponde a `2026-11-19 / MARKETPLACE`.
El fixture incluye dos unidades de `PROD-J1-ENSAYO`, importe total de línea 150 PEN.
El registro del evento y del agregado se verificó consultando PostgreSQL local tras el envío.

El arranque previo confirmó ambas colas durables, vacías, con DLX/DLK en la cola principal.
La captura fue proporcionada por Johan desde el panel de RabbitMQ.

## Reenvio del mismo evento — comprobado

Johan volvio a poner pendiente exclusivamente el fixture local del primer ensayo,
conservando su UUID y payload. No se borro el registro de idempotencia de Postventa.
El scheduler y el publicador lo entregaron nuevamente por RabbitMQ.

| Comprobacion | Resultado observado en la consulta y captura de Johan |
|---|---|
| UUID | `6e46b451-cd8a-4606-b5d5-f08ca12107aa` |
| Nuevo enviado_en | `2026-11-19T07:35:07.853857Z` |
| Intentos fallidos | `0` |
| Registros del UUID en Postventa | `1` |
| pedidos_pagados / MARKETPLACE / 2026-11-19 | `1` |

El reenvio no incremento el agregado ni duplico el registro del receptor.
Evidencia visual: captura 3, evento duplicado sin doble conteo.

## Postventa detenido y recuperacion — comprobado

Se detuvo M2 local, manteniendo M1 activo. Se inserto un segundo fixture local con
pedido `PED-2026-00002` y UUID `c2b6b85a-407e-49e0-9caa-627095f7db17`.

| Momento | Resultado |
|---|---|
| Publicacion con M2 detenido | Outbox enviado_en `2026-11-19T07:47:34.857411Z`, 0 intentos fallidos |
| Antes de reiniciar M2 | 0 registros del segundo evento en M2; contador MARKETPLACE en 1 |
| Captura 4A de RabbitMQ | Ready 1, Total 1, Consumers 0, Persistent 1 |
| Recepcion tras reiniciar M2 | `2026-11-19T07:54:50.216706Z` |
| Contador tras procesar | 2 pedidos pagados, fecha 2026-11-19 y canal MARKETPLACE |

El mensaje se mantuvo pendiente mientras M2 estaba detenido y el consumidor real lo
proceso al volver a iniciarse. No se volvio a publicar manualmente para recuperarlo.
La captura 4A fue proporcionada por Johan. Captura 4B de la cola vacia y captura de
la consulta del resultado local pendientes de incorporar a la presentacion.

## Mensaje invalido hacia DLQ — comprobado

Con M2 funcionando, Johan publico desde el administrador de RabbitMQ en
`ventas.eventos`, routing key `pedido.pagado`, content_type `application/json`,
el cuerpo `{"prueba":"J1-MENSAJE-INVALIDO"}`. Es un JSON valido pero no un evento
valido: le faltan los campos obligatorios del contrato.

La captura 5 proporcionada por Johan muestra `postventa.eventos-pedido.dlq`
con Ready 1, Total 1 y Consumers 0. El mensaje queda separado para revision manual.
No se comprobo en este caso el agotamiento de tres intentos ante un error temporal;
ese comportamiento tiene pruebas unitarias en M2 y es distinto del rechazo de un mensaje invalido.

La consulta posterior en PostgreSQL local confirmo 2 registros validos en
`postventa.evento_recibido` y `pedidos_pagados = 2` para 2026-11-19 / MARKETPLACE.
Por tanto, el mensaje invalido no agrego un evento valido ni incremento la metrica.
Johan confirmo que incorporo a Canva las capturas 4B y 5.

## Validacion final del backend — aprobada

Johan ejecuto `mvn -B verify` el 19/11/2026, con `F5_POSTGRES_TEST=true`
y `EVENTOS_ASINCRONOS=false` en la terminal de pruebas. Los servicios locales se
detuvieron antes de empaquetar los JAR para evitar el bloqueo de archivos de Windows.
El proceso termino a las 03:14:27 -05:00 con `BUILD SUCCESS`, todos los modulos en
SUCCESS y 41.272 segundos de duracion.

| Modulo | Pruebas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| api-gateway | 11 | 0 | 0 | 0 |
| servicio-ventas | 401 | 0 | 0 | 0 |
| servicio-postventa | 342 | 0 | 0 | 0 |
| simulador-pasarela | 51 | 0 | 0 | 0 |
| Total | 805 | 0 | 0 | 0 |

Los totales se comprobaron contra los reportes XML de Surefire generados localmente.
Las pruebas PostgreSQL utilizaron la base local de pruebas, no Supabase.
La prueba real de transporte con CloudAMQP esta descrita por separado arriba.
Captura 6: Reactor Summary y BUILD SUCCESS de esta ejecucion, pendiente de incorporar a Canva.

## Pendiente

- Capturas del resultado local y del panel.
- Prueba conjunta de compra real con Retail/G7 y revisión antes de cualquier PR o despliegue.

La proyección de ventas por producto y vendedor del PR #41 no forma parte de esta rama.

La configuracion de variables en Render, la revision del cambio, el push y la apertura del PR siguen pendientes de coordinacion y aprobacion. No se ha desplegado esta rama.
