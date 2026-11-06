package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.EntregaPickupRequest;
import ventas.pedido.dto.EntregaPickupResponse;
import ventas.pedido.dto.PedidoPickupResponse;

@ExtendWith(MockitoExtension.class)
class PickupPedidoServiceTest {

    @Mock
    private PedidoRepository pedidos;

    @Mock
    private MaquinaEstados maquina;

    private PickupPedidoService service;

    @BeforeEach
    void setUp() {
        service = new PickupPedidoService(pedidos, maquina);
    }

    @Test
    void listarPendientes_conTiendaValida_devuelvePedidosMapeados() {
        Pedido p = new Pedido();
        p.setId(10L);
        p.setCodigo("PED-2026-08910");
        p.setEstado(EstadoPedido.PAGADO);
        p.setNombreContacto("María González");
        p.setNumeroDocumento("45678912");
        p.setTiendaId("TIENDA-MIRAFLORES");
        p.setModalidadEnvio("RECOJO_TIENDA");
        p.setReferenciaEnvio("Anaquel B-04");
        p.setCreadoEn(OffsetDateTime.of(2026, 11, 5, 10, 0, 0, 0, ZoneOffset.UTC));

        DetallePedido d = new DetallePedido();
        d.setSku("ZAP-RUN-41-BLANC");
        d.setDescripcion("Zapatilla Running Pegasus 40");
        d.setCantidad(1);
        d.setPrecioUnitario(new BigDecimal("350.00"));
        p.agregarDetalle(d);

        when(pedidos.buscarParaPickup(eq("TIENDA-MIRAFLORES"), any())).thenReturn(List.of(p));

        List<PedidoPickupResponse> respuesta = service.listarPendientes("TIENDA-MIRAFLORES");

        assertThat(respuesta).hasSize(1);
        PedidoPickupResponse item = respuesta.get(0);
        assertThat(item.pedidoId()).isEqualTo("PED-2026-08910");
        assertThat(item.clienteNombre()).isEqualTo("María González");
        assertThat(item.clienteDni()).isEqualTo("45678912");
        assertThat(item.anaquelUbicacion()).isEqualTo("Anaquel B-04");
        assertThat(item.estado()).isEqualTo("LISTO_PARA_RECOJO");
        assertThat(item.articulos()).hasSize(1);
        assertThat(item.articulos().get(0).sku()).isEqualTo("ZAP-RUN-41-BLANC");
    }

    @Test
    void listarPendientes_sinTienda_lanzaExcepcion() {
        assertThatThrownBy(() -> service.listarPendientes("  "))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("PARAMETRO_REQUERIDO");
    }

    @Test
    void entregar_pedidoPagado_transicionaAEntregadoYEmiteConstancia() {
        Pedido p = new Pedido();
        p.setId(10L);
        p.setCodigo("PED-2026-08910");
        p.setEstado(EstadoPedido.PAGADO);
        p.setCanal(CanalVenta.MARKETPLACE);
        p.setModalidadEnvio("RECOJO_TIENDA");

        when(pedidos.bloquearPorCodigo("PED-2026-08910")).thenReturn(Optional.of(p));

        EntregaPickupRequest req = new EntregaPickupRequest("45678912", "María González", false, "A7K2Q9");
        EntregaPickupResponse resp = service.entregar("PED-2026-08910", req);

        assertThat(resp.constanciaId()).isEqualTo("CONST-PED-2026-08910");
        assertThat(resp.estado()).isEqualTo("ENTREGADO");
        verify(maquina).cambiar(eq(p), eq(EstadoPedido.ENTREGADO), eq(ActorHistorial.CLIENTE),
                eq("RETIRO_EN_TIENDA_CONFIRMADO"), any(OffsetDateTime.class));
    }

    @Test
    void entregar_pedidoNoExiste_lanza404() {
        when(pedidos.bloquearPorCodigo("PED-INEXISTENTE")).thenReturn(Optional.empty());

        EntregaPickupRequest req = new EntregaPickupRequest("45678912", "María González", false, "A7K2Q9");

        assertThatThrownBy(() -> service.entregar("PED-INEXISTENTE", req))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_ENCONTRADO");

        verify(maquina, never()).cambiar(any(), any(), any(), any(), any());
    }

    @Test
    void entregar_pedidoEnEstadoNoApto_lanza409() {
        Pedido p = new Pedido();
        p.setId(10L);
        p.setCodigo("PED-2026-08910");
        p.setEstado(EstadoPedido.ANULADO);

        when(pedidos.bloquearPorCodigo("PED-2026-08910")).thenReturn(Optional.of(p));

        EntregaPickupRequest req = new EntregaPickupRequest("45678912", "María González", false, "A7K2Q9");

        assertThatThrownBy(() -> service.entregar("PED-2026-08910", req))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_LISTO_PARA_RECOJO");

        verify(maquina, never()).cambiar(any(), any(), any(), any(), any());
    }
}
