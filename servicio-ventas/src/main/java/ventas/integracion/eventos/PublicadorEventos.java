package ventas.integracion.eventos;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ventas.pedido.Pedido;

/** Guarda el evento en outbox_evento dentro de la transacción del cambio de estado. */
@Component
@RequiredArgsConstructor
public class PublicadorEventos {

    private final OutboxEventoRepository repositorio;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publicar(TipoEvento tipo, Pedido pedido, EventoPedidoPayload payload) {
        OutboxEvento evento = new OutboxEvento();
        evento.setIdEvento(payload.idEvento());
        evento.setTipo(tipo);
        evento.setIdPedido(pedido.getId());
        evento.setPayload(aJson(payload));
        evento.setCreadoEn(OffsetDateTime.now(ZoneOffset.UTC));
        evento.setIntentos(0);
        repositorio.save(evento);
    }

    private String aJson(EventoPedidoPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento " + payload.idEvento(), e);
        }
    }
}
