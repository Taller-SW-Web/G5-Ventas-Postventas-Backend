# Validación de eventos de ventas — PR #41

Fecha: 2026-11-03. Rama de trabajo: Johan, con origin/main incorporado mediante
la fusión que estaba pendiente de confirmar al ejecutar las pruebas.

## Alcance

EventoPedidoService registra el UUID y actualiza las proyecciones en una sola
transacción. Solo pedido.pagado sin datos.devolucionId llama a
ProyeccionVentasService.pedidoPagado con fecha, canal, vendedor y líneas.
Un UUID ya registrado no vuelve a modificar los agregados.

## Resultados obtenidos

- Backend completo: mvn clean verify con F5_POSTGRES_TEST=false, BUILD SUCCESS
  en api-gateway, servicio-ventas, servicio-postventa y simulador-pasarela.
- Postventa con PostgreSQL: mvn -pl servicio-postventa -am verify con
  F5_POSTGRES_TEST=true, BUILD SUCCESS: 307 pruebas, 0 fallos, 0 errores, 0 omitidas.
- EventoPedidoPostgresTest: 10 casos ejecutados, 0 fallos, 0 errores, 0 omitidos.
- Entorno de integración: PostgreSQL 16 en el contenedor local f5-eventos-test,
  base f5_eventos_test, puerto publicado exclusivamente en 127.0.0.1:55432.

Comprobaciones con SQL real y transacciones Spring:

1. Evento nuevo: registra el UUID y suma una vez; repetirlo devuelve false y conserva los totales.
2. Doce recepciones concurrentes: una sola se considera nueva y la métrica diaria suma una vez.
3. Pago Retail: guarda vendedor, producto, dos unidades y monto 25.00 una sola vez.
4. Fallo provocado después del INSERT de ventas: revierten evento_recibido,
   agregado_metricas y agregado_ventas. Reintentar el mismo evento funciona;
   repetirlo después del éxito no duplica unidades ni monto.
5. Pago con referencia de reemplazo: registra el evento para deduplicación,
   pero no llama a las proyecciones ni modifica ventas o métricas.
6. La fecha se agrupa en UTC; los eventos inválidos no se marcan como recibidos.

Las entregas y anulaciones de reemplazos que llaman a DevolucionService también
están cubiertas por las pruebas unitarias del receptor. La prueba PostgreSQL de
reemplazos usa un pago con devolucionId para verificar la exclusión del agregado;
no ejecuta el flujo completo de devolución con servicios externos.

## Evento de ejemplo

Ejemplo equivalente al construido por pagoRetail en EventoPedidoPostgresTest.
Los UUID y la fecha de este JSON son ilustrativos: las pruebas generan UUID nuevos
y fechas aleatorias para poder repetirse sobre la base local sin colisiones.
El importe es el total de la línea, no su precio unitario.

```json
{
  "idEvento": "bc8bb780-bff8-4427-a2d5-1dcb66b27842",
  "tipo": "pedido.pagado",
  "pedidoId": "PED-2026-00981",
  "fecha": "2026-11-03T18:00:00-05:00",
  "datos": {
    "canal": "RETAIL",
    "clienteId": "11111111-1111-1111-1111-111111111111",
    "vendedorId": "22222222-2222-4222-8222-222222222222",
    "total": 25.00,
    "moneda": "PEN",
    "motivo": null,
    "items": [
      { "productoId": "PROD-PRUEBA-42", "cantidad": 2, "importe": 25.00 }
    ],
    "devolucionId": null
  }
}
```

Para este ejemplo, la fila de ventas corresponde a periodo 2026-11, canal RETAIL,
vendedor y producto indicados; incrementa unidades en 2 y monto en 25.00. En la
prueba de reemplazo, el mismo helper añade devolucionId = DEV-2026-0042 y no suma.

La ruta HTTP del receptor es POST /api/v2/eventos, pero la validación PostgreSQL
ejecutó el servicio dentro de Spring; no equivale a una prueba desplegada M1 → M2.

## Scripts SQL locales

Se compararon las definiciones de las doce tablas del script de creación y las
dos tablas del script de actualización con el esquema compartido en main:

https://github.com/Taller-SW-Web/G5-Ventas-Postventas/blob/main/infra/bd/ventas_postventa_schema.sql

Referencia revisada: blob ba164f413c278bd2d05d9eceaea83480f725e8c8.
Las definiciones de esas tablas coinciden; esto no es una inspección de Supabase.

Ejecución confirmada en una segunda base local f4_integracion_johan, dentro del
mismo contenedor de pruebas:

- crear-f4-integracion-johan.sql: COMMIT y doce tablas creadas.
- actualizar-f4-integracion-johan.sql: COMMIT.
- Segunda ejecución del actualizador: COMMIT, sin errores; los objetos existentes se omiten.
- Listado final de postventa: doce tablas.

Esta comprobación prueba creación y repetición sobre un esquema recién creado.
No prueba una migración de una base antigua con datos ni el arranque de Hibernate
contra esa segunda base. Los scripts siguen siendo exclusivos de PostgreSQL local.

## Pendientes y límites

- No se ejecutó SQL en Supabase ni en una base compartida.
- Falta la revisión conjunta del dashboard y CSAT en el entorno integrado real.
- Los eventos pagados que se procesaron antes de añadir la llamada no se recalculan
  por reenviar su UUID: la deduplicación los omite. Cualquier recuperación histórica
  necesita un procedimiento aparte, sin borrar registros de idempotencia a ciegas.
- Los scripts locales permanecen en el PR; el equipo debe decidir si los conserva
  o si se separan antes de fusionarlo a main.
