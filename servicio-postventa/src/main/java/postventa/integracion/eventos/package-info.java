/**
 * Receptor de eventos de M1 - Johan. Dos entradas que comparten EventoPedidoService:
 * POST /api/v2/eventos (HTTP) y la cola postventa.eventos-pedido.q de RabbitMQ (Hito 5, ConsumidorEventosPedido),
 * esta última solo con eventos.asincronos=true.
 */
package postventa.integracion.eventos;
