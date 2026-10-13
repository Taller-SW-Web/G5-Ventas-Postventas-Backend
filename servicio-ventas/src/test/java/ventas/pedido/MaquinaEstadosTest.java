package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.compartido.ConflictoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.eventos.EventoPedidoPayload;
import ventas.integracion.eventos.PublicadorEventos;
import ventas.integracion.eventos.TipoEvento;

@ExtendWith(MockitoExtension.class)
class MaquinaEstadosTest {

    @Mock PedidoRepository pedidos;
    @Mock HistorialEstadoRepository historial;
    @Mock PublicadorEventos eventos;
    @InjectMocks MaquinaEstados maquina;

    @ParameterizedTest(name = "{0} -> {1} por {2}")
    @CsvSource({
            "CREADO, PAGADO, PASARELA_PAGOS",
            "CREADO, ANULADO, PASARELA_PAGOS",
            "CREADO, ANULADO, CANAL_CHATBOT",
            "CREADO, ANULADO, CLIENTE",
            "CREADO, ANULADO, GESTOR",
            "CREADO, ANULADO, SISTEMA_PRODUCTOS",
            "PAGADO, EN_PREPARACION, SISTEMA_LOGISTICA",
            "PAGADO, ANULADO, CLIENTE",
            "PAGADO, ANULADO, GESTOR",
            "PAGADO, ANULADO, SISTEMA_PRODUCTOS",
            "EN_PREPARACION, DESPACHADO, SISTEMA_LOGISTICA",
            "EN_PREPARACION, ANULADO, GESTOR",
            "DESPACHADO, ENTREGADO, SISTEMA_LOGISTICA",
            "DESPACHADO, ANULADO, SISTEMA_LOGISTICA"
    })
    void transicionesDeLaMatrizSePermiten(EstadoPedido origen, EstadoPedido destino, ActorHistorial actor) {
        Pedido p = pedidoEn(origen);

        maquina.cambiar(p, destino, actor, "SOLICITUD_CLIENTE");

        assertThat(p.getEstado()).isEqualTo(destino);
        verify(pedidos).save(p);
        verify(historial).save(any(HistorialEstado.class));
    }

    @ParameterizedTest(name = "{0} -> {1} por {2}")
    @CsvSource({
            "CREADO, DESPACHADO, SISTEMA_LOGISTICA",
            "CREADO, ENTREGADO, SISTEMA_LOGISTICA",
            "PAGADO, ANULADO, PASARELA_PAGOS",
            "PAGADO, CREADO, GESTOR",
            "EN_PREPARACION, ANULADO, CLIENTE",
            "DESPACHADO, ANULADO, CLIENTE",
            "ENTREGADO, ANULADO, GESTOR",
            "ANULADO, PAGADO, PASARELA_PAGOS"
    })
    void transicionesFueraDeLaMatrizSeRechazan(EstadoPedido origen, EstadoPedido destino, ActorHistorial actor) {
        Pedido p = pedidoEn(origen);

        assertThatThrownBy(() -> maquina.cambiar(p, destino, actor, "SOLICITUD_CLIENTE"))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("TRANSICION_NO_PERMITIDA");

        assertThat(p.getEstado()).isEqualTo(origen);
        verifyNoInteractions(pedidos, historial, eventos);
    }

    @Test
    void retailPasaDePagadoAEntregadoPorEntregaEnTienda() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);
        p.setCanal(CanalVenta.RETAIL);

        maquina.cambiar(p, EstadoPedido.ENTREGADO, ActorHistorial.CLIENTE, "ENTREGA_INMEDIATA_EN_TIENDA");

        assertThat(p.getEstado()).isEqualTo(EstadoPedido.ENTREGADO);
        verify(eventos).publicar(eq(TipoEvento.PEDIDO_ENTREGADO), eq(p), any());
    }

    @Test
    void marketplaceNoPuedeSaltarDePagadoAEntregado() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);

        assertThatThrownBy(() -> maquina.cambiar(p, EstadoPedido.ENTREGADO, ActorHistorial.CLIENTE, "x"))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("TRANSICION_NO_PERMITIDA");
        verifyNoInteractions(pedidos, historial, eventos);
    }

    @Test
    void cambiarConFechaGuardaEsaFechaEnElHistorial() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);
        p.setCanal(CanalVenta.RETAIL);
        OffsetDateTime venta = OffsetDateTime.parse("2026-10-11T18:30:00Z");

        maquina.cambiar(p, EstadoPedido.ENTREGADO, ActorHistorial.CLIENTE, "ENTREGA_INMEDIATA_EN_TIENDA", venta);

        ArgumentCaptor<HistorialEstado> captor = ArgumentCaptor.forClass(HistorialEstado.class);
        verify(historial).save(captor.capture());
        assertThat(captor.getValue().getFechaHora()).isEqualTo(venta);
    }

    @Test
    void registroInicialGuardaHistorialSinEstadoAnterior() {
        Pedido p = pedidoEn(EstadoPedido.CREADO);
        OffsetDateTime ahora = OffsetDateTime.parse("2026-10-12T10:00:00Z");

        maquina.registrarInicial(p, ActorHistorial.CANAL_CHATBOT, "Registro inicial de compra", ahora);

        ArgumentCaptor<HistorialEstado> captor = ArgumentCaptor.forClass(HistorialEstado.class);
        verify(historial).save(captor.capture());
        assertThat(captor.getValue().getEstadoAnterior()).isNull();
        assertThat(captor.getValue().getEstadoNuevo()).isEqualTo(EstadoPedido.CREADO);
        assertThat(captor.getValue().getActor()).isEqualTo(ActorHistorial.CANAL_CHATBOT);
        verifyNoInteractions(eventos);
    }

    @Test
    void registroInicialPagadoPublicaPedidoPagado() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);
        p.setCanal(CanalVenta.RETAIL);

        maquina.registrarInicial(p, ActorHistorial.CLIENTE, "Registro inicial de compra", OffsetDateTime.now());

        verify(eventos).publicar(eq(TipoEvento.PEDIDO_PAGADO), eq(p), any());
    }

    @Test
    void anulacionSinMotivoSeRechaza() {
        Pedido p = pedidoEn(EstadoPedido.CREADO);

        assertThatThrownBy(() -> maquina.cambiar(p, EstadoPedido.ANULADO, ActorHistorial.CLIENTE, " "))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("MOTIVO_REQUERIDO");

        assertThat(p.getEstado()).isEqualTo(EstadoPedido.CREADO);
        verifyNoInteractions(pedidos, historial, eventos);
    }

    @Test
    void guardaElHistorialConEstadoAnteriorActorYMotivo() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);

        maquina.cambiar(p, EstadoPedido.ANULADO, ActorHistorial.GESTOR, "DUPLICADO");

        ArgumentCaptor<HistorialEstado> captor = ArgumentCaptor.forClass(HistorialEstado.class);
        verify(historial).save(captor.capture());
        HistorialEstado h = captor.getValue();
        assertThat(h.getEstadoAnterior()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(h.getEstadoNuevo()).isEqualTo(EstadoPedido.ANULADO);
        assertThat(h.getActor()).isEqualTo(ActorHistorial.GESTOR);
        assertThat(h.getMotivo()).isEqualTo("DUPLICADO");
        assertThat(h.getFechaHora()).isNotNull();
    }

    @Test
    void pagoPublicaPedidoPagadoConItems() {
        Pedido p = pedidoEn(EstadoPedido.CREADO);

        maquina.cambiar(p, EstadoPedido.PAGADO, ActorHistorial.PASARELA_PAGOS, "Confirmación de cobro exitoso");

        ArgumentCaptor<EventoPedidoPayload> captor = ArgumentCaptor.forClass(EventoPedidoPayload.class);
        verify(eventos).publicar(eq(TipoEvento.PEDIDO_PAGADO), eq(p), captor.capture());
        EventoPedidoPayload e = captor.getValue();
        assertThat(e.tipo()).isEqualTo("pedido.pagado");
        assertThat(e.pedidoId()).isEqualTo("PED-2026-00981");
        assertThat(e.datos().motivo()).isNull();
        assertThat(e.datos().items()).hasSize(1);
        assertThat(e.datos().items().get(0).productoId()).isEqualTo("PROD-101");
    }

    @Test
    void anulacionPublicaPedidoAnuladoConMotivo() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);

        maquina.cambiar(p, EstadoPedido.ANULADO, ActorHistorial.CLIENTE, "ARREPENTIMIENTO_COMPRA");

        ArgumentCaptor<EventoPedidoPayload> captor = ArgumentCaptor.forClass(EventoPedidoPayload.class);
        verify(eventos).publicar(eq(TipoEvento.PEDIDO_ANULADO), eq(p), captor.capture());
        assertThat(captor.getValue().datos().motivo()).isEqualTo("ARREPENTIMIENTO_COMPRA");
        assertThat(captor.getValue().datos().items()).isNull();
    }

    @Test
    void pasarAPreparacionNoPublicaEvento() {
        Pedido p = pedidoEn(EstadoPedido.PAGADO);

        maquina.cambiar(p, EstadoPedido.EN_PREPARACION, ActorHistorial.SISTEMA_LOGISTICA, null);

        verifyNoInteractions(eventos);
    }

    private static Pedido pedidoEn(EstadoPedido estado) {
        Pedido p = new Pedido();
        p.setId(981L);
        p.setCodigo("PED-2026-00981");
        p.setCanal(CanalVenta.MARKETPLACE);
        p.setEstado(estado);
        p.setFecha(LocalDate.of(2026, 9, 23));
        p.setIdCliente(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        p.setMoneda("PEN");
        p.setSubtotal(new BigDecimal("129.90"));
        p.setTotal(new BigDecimal("129.90"));

        DetallePedido d = new DetallePedido();
        d.setIdProducto("PROD-101");
        d.setCantidad(1);
        d.setPrecioUnitario(new BigDecimal("129.90"));
        d.setImporte(new BigDecimal("129.90"));
        p.agregarDetalle(d);
        return p;
    }
}
