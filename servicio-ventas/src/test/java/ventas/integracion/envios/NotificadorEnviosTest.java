package ventas.integracion.envios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;
import ventas.pedido.Pedido;

@ExtendWith(MockitoExtension.class)
class NotificadorEnviosTest {

    @Mock NotificacionEnvioRepository notificaciones;
    NotificadorEnvios notificador;
    Pedido pedido;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void armar() {
        notificador = new NotificadorEnvios(notificaciones, json);
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo("PED-2026-00981");
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setEstado(EstadoPedido.DESPACHADO);
        pedido.setDireccion("Av. La Marina 1234");
        pedido.setFechaEstimadaEntrega(OffsetDateTime.parse("2026-10-15T00:00:00Z"));
    }

    @Test
    void guardaElEventoConElFormatoDeH05() throws Exception {
        notificador.registrar(pedido, "DESP-00412");

        ArgumentCaptor<NotificacionEnvio> captor = ArgumentCaptor.forClass(NotificacionEnvio.class);
        verify(notificaciones).save(captor.capture());
        NotificacionEnvio n = captor.getValue();
        assertThat(n.getIdPedido()).isEqualTo(981L);
        assertThat(n.getEstadoPublico()).isEqualTo("DESPACHADO");
        assertThat(n.getEnviadoEn()).isNull();

        JsonNode e = json.readTree(n.getPayload());
        assertThat(e.get("eventId").asText()).isEqualTo(n.getIdEvento().toString());
        assertThat(e.get("eventType").asText()).isEqualTo("ShipmentStatusUpdated");
        assertThat(e.get("version").asText()).isEqualTo("1.0.0");
        assertThat(e.get("emitter").asText()).isEqualTo("modulo-ventas");
        assertThat(e.hasNonNull("timestamp")).isTrue();
        JsonNode d = e.get("data");
        assertThat(d.get("orderId").asText()).isEqualTo("PED-2026-00981");
        assertThat(d.get("publicStatus").asText()).isEqualTo("DESPACHADO");
        assertThat(d.get("trackingNumber").asText()).isEqualTo("DESP-00412");
        assertThat(d.get("estimatedDeliveryDate").asText()).isEqualTo("2026-10-15");
        // Privacidad (H-05): sin dirección ni datos del cliente
        assertThat(n.getPayload()).doesNotContain("La Marina");
    }

    @Test
    void sinFechaEstimadaVaNull() throws Exception {
        pedido.setFechaEstimadaEntrega(null);
        notificador.registrar(pedido, null);
        ArgumentCaptor<NotificacionEnvio> captor = ArgumentCaptor.forClass(NotificacionEnvio.class);
        verify(notificaciones).save(captor.capture());
        JsonNode d = json.readTree(captor.getValue().getPayload()).get("data");
        assertThat(d.get("estimatedDeliveryDate").isNull()).isTrue();
        assertThat(d.get("trackingNumber").isNull()).isTrue();
    }

    @Test
    void soloParaPedidosDeMarketplace() {
        pedido.setCanal(CanalVenta.CHATBOT);   // Chatbot consulta bajo demanda (acuerdo G3-8)
        notificador.registrar(pedido, "DESP-00412");
        verifyNoInteractions(notificaciones);
    }
}
