package ventas.integracion.eventos;

import ventas.integracion.PostventaClient.Envio;

/** Transporte elegido por entorno para entregar el JSON original del outbox. */
public interface TransporteEventosPedido {
    Envio enviar(OutboxEvento evento);
}
