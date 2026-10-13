package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import ventas.compartido.RecursoNoEncontradoException;
import ventas.pedido.dto.PedidoDetalle;

@ExtendWith(MockitoExtension.class)
class ConsultarPedidoServiceTest {

    static final String CODIGO = "PED-2026-00981";
    static final UUID CLIENTE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final OffsetDateTime CREADO = OffsetDateTime.parse("2026-09-23T00:05:00Z");

    @Mock PedidoRepository pedidos;
    @Mock PagoRepository pagos;
    @Mock HistorialEstadoRepository historial;
    @InjectMocks ConsultarPedidoService servicio;

    Pedido pedido;

    @BeforeEach
    void armar() {
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo(CODIGO);
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedido.setIdCliente(CLIENTE);
        pedido.setNombreContacto("Juan Pérez");
        pedido.setTipoDocumento("DNI");
        pedido.setNumeroDocumento("72458912");
        pedido.setEmail("juan@example.com");
        pedido.setModalidadEnvio("DELIVERY");
        pedido.setDireccion("Av. La Marina 1234");
        pedido.setDistrito("San Miguel");
        pedido.setMoneda("PEN");
        pedido.setTotal(new BigDecimal("129.90"));
        pedido.setCreadoEn(CREADO);
        DetallePedido d = new DetallePedido();
        d.setIdProducto("PROD-101");
        d.setSku("SKU-AUR-01");
        d.setDescripcion("Audífonos");
        d.setCantidad(1);
        d.setPrecioUnitario(new BigDecimal("129.90"));
        pedido.agregarDetalle(d);
    }

    private static HistorialEstado h(EstadoPedido estado, ActorHistorial actor, String motivo, String fecha) {
        HistorialEstado h = new HistorialEstado();
        h.setEstadoNuevo(estado);
        h.setActor(actor);
        h.setMotivo(motivo);
        h.setFechaHora(OffsetDateTime.parse(fecha));
        return h;
    }

    @Test
    void devuelveElPedidoCompletoConHistorialEnOrden() {
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.of(pedido));
        Pago pago = new Pago();
        pago.setMetodo("TARJETA_CREDITO");
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));
        when(historial.findByPedidoIdOrderByFechaHoraAsc(981L)).thenReturn(List.of(
                h(EstadoPedido.CREADO, ActorHistorial.CANAL_CHATBOT, "Registro inicial de compra", "2026-09-23T00:05:00Z"),
                h(EstadoPedido.PAGADO, ActorHistorial.PASARELA_PAGOS, "Confirmación de cobro exitoso", "2026-09-23T00:06:00Z")));

        PedidoDetalle r = servicio.consultar(CODIGO, Optional.empty());

        assertThat(r.pedidoId()).isEqualTo(CODIGO);
        assertThat(r.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(r.contacto().clienteId()).isEqualTo(CLIENTE);
        assertThat(r.pago().metodoPago()).isEqualTo("TARJETA_CREDITO");
        assertThat(r.pago().moneda()).isEqualTo("PEN");
        assertThat(r.envio().direccion()).isEqualTo("Av. La Marina 1234");
        assertThat(r.items()).singleElement().satisfies(i -> {
            assertThat(i.productoId()).isEqualTo("PROD-101");
            assertThat(i.sku()).isEqualTo("SKU-AUR-01");
        });
        assertThat(r.historialEstados()).extracting(PedidoDetalle.Historial::estado)
                .containsExactly(EstadoPedido.CREADO, EstadoPedido.PAGADO);
        assertThat(r.fechaCreacion()).isEqualTo(CREADO);
        assertThat(r.ultimaActualizacion()).isEqualTo(OffsetDateTime.parse("2026-09-23T00:06:00Z"));
    }

    @Test
    void elClienteVeSuPropioPedido() {
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.of(pedido));

        assertThat(servicio.consultar(CODIGO, Optional.of(CLIENTE)).pedidoId()).isEqualTo(CODIGO);
    }

    @Test
    void pedidoDeOtroClienteDa403() {
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.of(pedido));

        assertThatThrownBy(() -> servicio.consultar(CODIGO, Optional.of(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void pedidoInexistenteDa404() {
        when(pedidos.findByCodigo("PED-2026-99999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.consultar("PED-2026-99999", Optional.empty()))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_ENCONTRADO");
    }

    @Test
    void sinHistorialLaUltimaActualizacionEsLaCreacion() {
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.of(pedido));

        assertThat(servicio.consultar(CODIGO, Optional.empty()).ultimaActualizacion()).isEqualTo(CREADO);
    }
}
