package ventas.integracion.envios;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ventas.pedido.CanalVenta;
import ventas.pedido.Pedido;

/**
 * 1.10 Webhook de estado de envío (acuerdo H-05): arma el evento pedidos.envio.estado_actualizado v1.0.0 y lo deja
 * en notificacion_envio, en la transacción del cambio. Solo para MARKETPLACE (Chatbot consulta bajo demanda, G3-8).
 * Sin datos sensibles ni dirección: solo el pedido, el estado público, el tracking y la fecha estimada.
 */
@Component
@RequiredArgsConstructor
public class NotificadorEnvios {

    static final String TIPO = "ShipmentStatusUpdated";
    static final String VERSION = "1.0.0";
    static final String EMISOR = "modulo-ventas";

    private final NotificacionEnvioRepository notificaciones;
    private final ObjectMapper json;

    /** trackingNumber: el idDespacho de G2 (puede ser null si Despacho no lo mandó). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Pedido pedido, String trackingNumber) {
        if (pedido.getCanal() != CanalVenta.MARKETPLACE) {
            return;
        }
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        UUID idEvento = UUID.randomUUID();

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("orderId", pedido.getCodigo());
        datos.put("publicStatus", pedido.getEstado().name());
        datos.put("trackingNumber", trackingNumber);
        datos.put("estimatedDeliveryDate", pedido.getFechaEstimadaEntrega() == null ? null
                : pedido.getFechaEstimadaEntrega().format(DateTimeFormatter.ISO_LOCAL_DATE));
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("eventId", idEvento.toString());
        evento.put("eventType", TIPO);
        evento.put("version", VERSION);
        evento.put("timestamp", ahora.toString());
        evento.put("emitter", EMISOR);
        evento.put("data", datos);

        NotificacionEnvio n = new NotificacionEnvio();
        n.setIdEvento(idEvento);
        n.setIdPedido(pedido.getId());
        n.setEstadoPublico(pedido.getEstado().name());
        n.setPayload(aJson(evento));
        n.setCreadoEn(ahora);
        n.setIntentos(0);
        notificaciones.save(n);
    }

    private String aJson(Map<String, Object> evento) {
        try {
            return json.writeValueAsString(evento);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el aviso de envío", e);
        }
    }
}
