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

## Pendiente

- Detener M2, enviar otro evento y comprobar su permanencia en la cola y consumo posterior.
- Enviar un mensaje inválido y comprobar su llegada a la DLQ.
- Capturas del resultado local y del panel.
- Prueba conjunta de compra real con Retail/G7 y revisión antes de cualquier PR o despliegue.

La proyección de ventas por producto y vendedor del PR #41 no forma parte de esta rama.
