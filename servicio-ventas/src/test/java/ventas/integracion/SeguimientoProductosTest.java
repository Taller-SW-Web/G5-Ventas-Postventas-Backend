package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ventas.integracion.CuponesClient.ResultadoCupon;
import ventas.integracion.ProductosClient.EstadoReserva;
import ventas.integracion.ProductosClient.Operacion;
import ventas.integracion.ProductosClient.ResultadoOperacion;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.CanalVenta;
import ventas.pedido.EstadoPedido;
import ventas.pedido.MensajesProductosService;
import ventas.pedido.Pedido;
import ventas.pedido.PedidoRepository;
import ventas.pedido.TipoPedido;

/** Contrato H5: los resultados consultados por HTTP se convierten en el mismo MensajeInventario que llegaba por RabbitMQ. */
class SeguimientoProductosTest {

    static final String CODIGO = "PED-2026-00981";
    static final String RESERVA = "22222222-2222-4222-8222-222222222222";

    PedidoRepository pedidos;
    EventoRecibidoRepository recibidos;
    ProductosClient productos;
    CuponesClient cupones;
    MensajesProductosService mensajes;
    SeguimientoProductos seguimiento;

    @BeforeEach
    void armar() {
        pedidos = mock(PedidoRepository.class);
        recibidos = mock(EventoRecibidoRepository.class);
        productos = mock(ProductosClient.class);
        cupones = mock(CuponesClient.class);
        mensajes = mock(MensajesProductosService.class);
        Clock reloj = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);
        seguimiento = new SeguimientoProductos(pedidos, recibidos, productos, cupones, mensajes, reloj, Duration.ofHours(72), 100,
                Duration.ofSeconds(60));
    }

    Pedido pedido(EstadoPedido estado, String reservaId, String cupon) {
        Pedido p = mock(Pedido.class);
        when(p.getCodigo()).thenReturn(CODIGO);
        when(p.getEstado()).thenReturn(estado);
        when(p.getReservaId()).thenReturn(reservaId);
        when(p.getCodigoCupon()).thenReturn(cupon);
        when(p.getCanal()).thenReturn(CanalVenta.MARKETPLACE);
        when(p.getIdCliente()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(p.getCreadoEn()).thenReturn(OffsetDateTime.parse("2026-11-18T11:00:00Z"));
        return p;
    }

    MensajeInventario aplicado() {
        ArgumentCaptor<MensajeInventario> m = ArgumentCaptor.forClass(MensajeInventario.class);
        verify(mensajes).procesar(m.capture());
        return m.getValue();
    }

    @Test
    void elBarridoExcluyeRetailYUsaLaVentana() {
        when(pedidos.enSeguimientoProductos(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        seguimiento.barrer();
        verify(pedidos).enSeguimientoProductos(eq(SeguimientoProductos.ESTADOS), eq(EstadoPedido.EN_PREPARACION),
                eq(TipoPedido.REEMPLAZO), eq(CanalVenta.RETAIL), eq(OffsetDateTime.parse("2026-11-15T12:00Z")), any());
    }

    @Test
    void laVigenciaDeLasReservasSeRevisaCada60sYLosResultadosEnCadaBarrido() {
        MutableClock reloj = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
        seguimiento = new SeguimientoProductos(pedidos, recibidos, productos, cupones, mensajes, reloj, Duration.ofHours(72),
                100, Duration.ofSeconds(60));
        Pedido p = pedido(EstadoPedido.CREADO, RESERVA, null);
        when(pedidos.enSeguimientoProductos(any(), any(), any(), any(), any(), any())).thenReturn(List.of(p));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));

        seguimiento.barrer();                       // 12:00:00 revisa vigencia
        reloj.avanzar(Duration.ofSeconds(5));
        seguimiento.barrer();                       // 12:00:05 todavía no
        reloj.avanzar(Duration.ofSeconds(55));
        seguimiento.barrer();                       // 12:01:00 otra vez

        verify(productos, times(2)).consultarReserva(RESERVA, CODIGO);
    }

    static final class MutableClock extends Clock {
        private Instant ahora;

        MutableClock(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @Test
    void reservaCompletadaSeAplicaComoReservationCreated() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "RESERVAR", "COMPLETED", RESERVA, null)));

        seguimiento.seguir(pedido(EstadoPedido.CREADO, null, null));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.RESERVA_CREADA);
        assertThat(m.pedidoId()).isEqualTo(CODIGO);
        assertThat(m.reservaId()).isEqualTo(RESERVA);
        assertThat(m.operationId()).isEqualTo(op);
        assertThat(m.messageId()).isEqualTo("H5:" + op + ":COMPLETED");
    }

    @Test
    void reservaRechazadaSeAplicaComoConsumptionRejectedConSuCodigo() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "RESERVAR", "REJECTED", null, "STOCK_INSUFICIENTE")));

        seguimiento.seguir(pedido(EstadoPedido.CREADO, null, null));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.CONSUMO_RECHAZADO);
        assertThat(m.operationId()).isEqualTo(op);   // el servicio distingue reserva de confirmación por el operation_id
        assertThat(m.codigo()).isEqualTo("STOCK_INSUFICIENTE");
    }

    @Test
    void operacionPendienteOAusenteNoAplicaNada() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "RESERVAR", "PROCESSING", null, null)));
        seguimiento.seguir(pedido(EstadoPedido.CREADO, null, null));

        when(productos.consultarOperacion(op)).thenReturn(Optional.empty());   // 404 o sin respuesta
        seguimiento.seguir(pedido(EstadoPedido.CREADO, null, null));

        verify(mensajes, never()).procesar(any());
    }

    @Test
    void unResultadoYaAplicadoNoSeVuelveAConsultar() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(recibidos.existsByIdEventoAndOrigen("H5:" + op + ":COMPLETED", OrigenEvento.SISTEMA_PRODUCTOS)).thenReturn(true);

        seguimiento.seguir(pedido(EstadoPedido.CREADO, null, null));

        verify(productos, never()).consultarOperacion(anyString());
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void cuponCompletadoHabilitaElPago() {
        String op = CuponesClient.operacionConsumo(CODIGO);
        when(cupones.consultar(op, CODIGO)).thenReturn(Optional.of(new ResultadoCupon("COMPLETED", null)));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));

        seguimiento.seguir(pedido(EstadoPedido.CREADO, RESERVA, "DESC10"));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.CUPON_CONSUMIDO);
        assertThat(m.pedidoId()).isEqualTo(CODIGO);
    }

    @Test
    void cuponRechazadoSeAplicaConSuCodigo() {
        String op = CuponesClient.operacionConsumo(CODIGO);
        when(cupones.consultar(op, CODIGO)).thenReturn(Optional.of(new ResultadoCupon("REJECTED", "CUPON_AGOTADO")));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));

        seguimiento.seguir(pedido(EstadoPedido.CREADO, RESERVA, "DESC10"));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.CUPON_RECHAZADO);
        assertThat(m.codigo()).isEqualTo("CUPON_AGOTADO");
    }

    @Test
    void siProductosNoConoceElConsumoDelCuponSeReenviaConLaMismaIdentidad() {
        when(cupones.consultar(any(), any())).thenReturn(Optional.of(ResultadoCupon.NO_ENCONTRADA));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.empty());
        Pedido p = pedido(EstadoPedido.CREADO, RESERVA, "DESC10");

        seguimiento.seguir(p);

        verify(cupones).consumir(CODIGO, "DESC10", p.getIdCliente(), "MARKETPLACE", p.getCreadoEn(), RESERVA);
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void siProductosNoRespondeNoSeReenviaElCupon() {
        when(cupones.consultar(any(), any())).thenReturn(Optional.empty());   // error de red, no 404
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.empty());

        seguimiento.seguir(pedido(EstadoPedido.CREADO, RESERVA, "DESC10"));

        verify(cupones, never()).consumir(any(), any(), any(), any(), any(), any());
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void reservaActivaPeroVencidaAnulaElPedidoPendiente() {
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VENCIDA")));

        seguimiento.seguir(pedido(EstadoPedido.CREADO, RESERVA, null));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.RESERVA_EXPIRADA);
        assertThat(m.messageId()).isEqualTo("H5:EXP:" + RESERVA);
        assertThat(m.pedidoId()).isEqualTo(CODIGO);
    }

    @Test
    void reservaExpiradaPersistidaTambienSeAplica() {
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("EXPIRADA", "NO_APLICA")));
        seguimiento.seguir(pedido(EstadoPedido.CREADO, RESERVA, null));
        assertThat(aplicado().name()).isEqualTo(MensajeInventario.RESERVA_EXPIRADA);
    }

    @Test
    void reservaVigenteNoHaceNada() {
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));
        seguimiento.seguir(pedido(EstadoPedido.CREADO, RESERVA, null));
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void consumoRechazadoDeUnPedidoPagadoSeAplicaConLaOperacionConfirmar() {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "CONFIRMAR_CONSUMO", "REJECTED", RESERVA, "RESERVA_EXPIRADA")));

        seguimiento.seguir(pedido(EstadoPedido.PAGADO, RESERVA, null));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.CONSUMO_RECHAZADO);
        assertThat(m.operationId()).isEqualTo(op);
        verify(productos, never()).consultarReserva(any(), any());   // ya pagado: el vencimiento no aplica
    }

    @Test
    void unPedidoPagadoSinReservaEsperaElResultadoDeLaReserva() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "RESERVAR", "COMPLETED", RESERVA, null)));

        seguimiento.seguir(pedido(EstadoPedido.PAGADO, null, null));   // el Chatbot cobró antes de la reserva

        assertThat(aplicado().name()).isEqualTo(MensajeInventario.RESERVA_CREADA);
    }

    @Test
    void unErrorEnUnPedidoNoDetieneElBarrido() {
        Pedido malo = pedido(EstadoPedido.CREADO, null, null);
        Pedido bueno = mock(Pedido.class);
        when(bueno.getCodigo()).thenReturn("PED-2026-00982");
        when(bueno.getEstado()).thenReturn(EstadoPedido.CREADO);
        when(pedidos.enSeguimientoProductos(any(), any(), any(), any(), any(), any())).thenReturn(List.of(malo, bueno));
        when(productos.consultarOperacion(ProductosClient.operacionId(Operacion.RESERVAR, CODIGO)))
                .thenThrow(new IllegalStateException("caída"));

        seguimiento.barrer();

        verify(productos).consultarOperacion(ProductosClient.operacionId(Operacion.RESERVAR, "PED-2026-00982"));
    }
}
