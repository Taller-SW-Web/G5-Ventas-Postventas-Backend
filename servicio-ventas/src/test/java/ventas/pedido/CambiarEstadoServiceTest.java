package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.pedido.dto.CambiarEstadoRequest;
import ventas.pedido.dto.CambiarEstadoResponse;

@ExtendWith(MockitoExtension.class)
class CambiarEstadoServiceTest {

    private static final String CODIGO = "PED-2026-00981";
    private static final String MOTIVO = "Guía de remisión GR-00892 asignada a transporte";

    @Mock PedidoRepository pedidos;
    @Mock HistorialEstadoRepository historial;
    @Mock MaquinaEstados maquina;
    @InjectMocks CambiarEstadoService servicio;

    @Test
    void logisticaAvanzaElPedidoConLaMaquinaDeEstados() {
        Pedido p = pedido(EstadoPedido.EN_PREPARACION);
        when(pedidos.findById(981L)).thenReturn(Optional.of(p));

        CambiarEstadoResponse r = servicio.cambiar(CODIGO,
                new CambiarEstadoRequest(EstadoPedido.DESPACHADO, ActorHistorial.SISTEMA_LOGISTICA, " " + MOTIVO + " "));

        verify(maquina).cambiar(eq(p), eq(EstadoPedido.DESPACHADO), eq(ActorHistorial.SISTEMA_LOGISTICA), eq(MOTIVO),
                any(OffsetDateTime.class));
        assertThat(r.pedidoId()).isEqualTo(CODIGO);
        assertThat(r.estadoAnterior()).isEqualTo(EstadoPedido.EN_PREPARACION);
        assertThat(r.nuevoEstado()).isEqualTo(EstadoPedido.DESPACHADO);
        assertThat(r.fechaTransicion()).isNotNull();
    }

    @Test
    void motivoVacioSeGuardaComoNull() {
        Pedido p = pedido(EstadoPedido.PAGADO);
        when(pedidos.findById(981L)).thenReturn(Optional.of(p));
        servicio.cambiar(CODIGO, new CambiarEstadoRequest(EstadoPedido.EN_PREPARACION, ActorHistorial.SISTEMA_LOGISTICA, "  "));
        verify(maquina).cambiar(eq(p), eq(EstadoPedido.EN_PREPARACION), eq(ActorHistorial.SISTEMA_LOGISTICA),
                eq((String) null), any(OffsetDateTime.class));
    }

    @Test
    void reintentoAlMismoEstadoResponde200SinTocarElHistorial() {
        Pedido p = pedido(EstadoPedido.DESPACHADO);
        when(pedidos.findById(981L)).thenReturn(Optional.of(p));
        HistorialEstado h = new HistorialEstado();
        OffsetDateTime cuando = OffsetDateTime.of(2026, 10, 13, 10, 0, 0, 0, ZoneOffset.UTC);
        h.setFechaHora(cuando);
        when(historial.findByPedidoIdOrderByFechaHoraAsc(981L)).thenReturn(List.of(h));

        CambiarEstadoResponse r = servicio.cambiar(CODIGO,
                new CambiarEstadoRequest(EstadoPedido.DESPACHADO, ActorHistorial.SISTEMA_LOGISTICA, MOTIVO));

        verifyNoInteractions(maquina);
        assertThat(r.estadoAnterior()).isEqualTo(EstadoPedido.DESPACHADO);
        assertThat(r.nuevoEstado()).isEqualTo(EstadoPedido.DESPACHADO);
        assertThat(r.fechaTransicion()).isEqualTo(cuando);
    }

    @Test
    void anularPorEsteEndpointResponde409() {
        assertThatThrownBy(() -> servicio.cambiar(CODIGO,
                new CambiarEstadoRequest(EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, "ENTREGA_FALLIDA")))
                .isInstanceOf(ConflictoException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_NO_PERMITIDA");
        verifyNoInteractions(pedidos, maquina);
    }

    @Test
    void otroActorQueNoEsLogisticaResponde409() {
        // Evita marcar PAGADO sin pasar por 1.2 o entregar un pedido Retail a mano
        for (ActorHistorial actor : List.of(ActorHistorial.PASARELA_PAGOS, ActorHistorial.CLIENTE, ActorHistorial.GESTOR)) {
            assertThatThrownBy(() -> servicio.cambiar(CODIGO, new CambiarEstadoRequest(EstadoPedido.PAGADO, actor, null)))
                    .isInstanceOf(ConflictoException.class)
                    .hasFieldOrPropertyWithValue("codigo", "TRANSICION_NO_PERMITIDA");
        }
        verifyNoInteractions(pedidos, maquina);
    }

    @Test
    void transicionFueraDeLaMatrizResponde409() {
        Pedido p = pedido(EstadoPedido.CREADO);
        when(pedidos.findById(981L)).thenReturn(Optional.of(p));
        doThrow(new ConflictoException("TRANSICION_NO_PERMITIDA", "x")).when(maquina)
                .cambiar(eq(p), eq(EstadoPedido.DESPACHADO), eq(ActorHistorial.SISTEMA_LOGISTICA), any(), any(OffsetDateTime.class));

        assertThatThrownBy(() -> servicio.cambiar(CODIGO,
                new CambiarEstadoRequest(EstadoPedido.DESPACHADO, ActorHistorial.SISTEMA_LOGISTICA, null)))
                .isInstanceOf(ConflictoException.class);
    }

    @Test
    void pedidoInexistenteResponde404() {
        when(pedidos.findById(981L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.cambiar(CODIGO,
                new CambiarEstadoRequest(EstadoPedido.DESPACHADO, ActorHistorial.SISTEMA_LOGISTICA, null)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private static Pedido pedido(EstadoPedido estado) {
        Pedido p = new Pedido();
        p.setId(981L);
        p.setCodigo(CODIGO);
        p.setCanal(CanalVenta.MARKETPLACE);
        p.setEstado(estado);
        p.setCreadoEn(OffsetDateTime.of(2026, 10, 12, 0, 0, 0, 0, ZoneOffset.UTC));
        return p;
    }
}
