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
import ventas.pedido.DetallePedido;
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
    MutableClock reloj;

    /** Primera pasada: solo arma el plan (primera consulta a los 2 s, D-02). La segunda, 3 s después, ya consulta. */
    void seguir(Pedido p) {
        seguimiento.seguir(p, false);
        reloj.avanzar(Duration.ofSeconds(3));
        seguimiento.seguir(p);
    }

    @BeforeEach
    void armar() {
        pedidos = mock(PedidoRepository.class);
        recibidos = mock(EventoRecibidoRepository.class);
        productos = mock(ProductosClient.class);
        cupones = mock(CuponesClient.class);
        mensajes = mock(MensajesProductosService.class);
        reloj = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
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

        seguir(pedido(EstadoPedido.CREADO, null, null));

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

        seguir(pedido(EstadoPedido.CREADO, null, null));

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
        seguir(pedido(EstadoPedido.CREADO, null, null));

        when(productos.consultarOperacion(op)).thenReturn(Optional.empty());   // sin respuesta
        seguir(pedido(EstadoPedido.CREADO, null, null));

        verify(mensajes, never()).procesar(any());
    }

    @Test
    void unResultadoYaAplicadoNoSeVuelveAConsultar() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(recibidos.existsByIdEventoAndOrigen("H5:" + op + ":COMPLETED", OrigenEvento.SISTEMA_PRODUCTOS)).thenReturn(true);

        seguir(pedido(EstadoPedido.CREADO, null, null));

        verify(productos, never()).consultarOperacion(anyString());
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void cuponCompletadoHabilitaElPago() {
        String op = CuponesClient.operacionConsumo(CODIGO);
        when(cupones.consultar(op, CODIGO)).thenReturn(Optional.of(new ResultadoCupon("COMPLETED", null)));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));

        seguir(pedido(EstadoPedido.CREADO, RESERVA, "DESC10"));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.CUPON_CONSUMIDO);
        assertThat(m.pedidoId()).isEqualTo(CODIGO);
    }

    @Test
    void cuponRechazadoSeAplicaConSuCodigo() {
        String op = CuponesClient.operacionConsumo(CODIGO);
        when(cupones.consultar(op, CODIGO)).thenReturn(Optional.of(new ResultadoCupon("REJECTED", "CUPON_AGOTADO")));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));

        seguir(pedido(EstadoPedido.CREADO, RESERVA, "DESC10"));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.CUPON_RECHAZADO);
        assertThat(m.codigo()).isEqualTo("CUPON_AGOTADO");
    }

    @Test
    void siProductosNoConoceElConsumoDelCuponSeReenviaConLaMismaIdentidad() {
        when(cupones.consultar(any(), any())).thenReturn(Optional.of(ResultadoCupon.NO_ENCONTRADA));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.empty());
        Pedido p = pedido(EstadoPedido.CREADO, RESERVA, "DESC10");

        seguir(p);

        verify(cupones).consumir(CODIGO, "DESC10", p.getIdCliente(), "MARKETPLACE", p.getCreadoEn(), RESERVA);
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void siProductosNoRespondeNoSeReenviaElCupon() {
        when(cupones.consultar(any(), any())).thenReturn(Optional.empty());   // error de red, no 404
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.empty());

        seguir(pedido(EstadoPedido.CREADO, RESERVA, "DESC10"));

        verify(cupones, never()).consumir(any(), any(), any(), any(), any(), any());
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void reservaActivaPeroVencidaAnulaElPedidoPendiente() {
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VENCIDA")));

        seguir(pedido(EstadoPedido.CREADO, RESERVA, null));

        MensajeInventario m = aplicado();
        assertThat(m.name()).isEqualTo(MensajeInventario.RESERVA_EXPIRADA);
        assertThat(m.messageId()).isEqualTo("H5:EXP:" + RESERVA);
        assertThat(m.pedidoId()).isEqualTo(CODIGO);
    }

    @Test
    void reservaExpiradaPersistidaTambienSeAplica() {
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("EXPIRADA", "NO_APLICA")));
        seguir(pedido(EstadoPedido.CREADO, RESERVA, null));
        assertThat(aplicado().name()).isEqualTo(MensajeInventario.RESERVA_EXPIRADA);
    }

    @Test
    void reservaVigenteNoHaceNada() {
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.of(new EstadoReserva("ACTIVA", "VIGENTE")));
        seguir(pedido(EstadoPedido.CREADO, RESERVA, null));
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void consumoRechazadoDeUnPedidoPagadoSeAplicaConLaOperacionConfirmar() {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "CONFIRMAR_CONSUMO", "REJECTED", RESERVA, "RESERVA_EXPIRADA")));

        seguir(pedido(EstadoPedido.PAGADO, RESERVA, null));

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

        seguir(pedido(EstadoPedido.PAGADO, null, null));   // el Chatbot cobró antes de la reserva

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
        reloj.avanzar(Duration.ofSeconds(3));
        seguimiento.barrer();

        verify(productos).consultarOperacion(ProductosClient.operacionId(Operacion.RESERVAR, "PED-2026-00982"));
    }

    // ---------- D-07: reenvío con la misma identidad ----------

    @Test
    void siProductosNoConoceLaReservaSeReenviaConLasMismasLineas() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op)).thenReturn(Optional.of(ResultadoOperacion.NO_ENCONTRADA));
        Pedido guardado = new Pedido();
        guardado.setCodigo(CODIGO);
        guardado.agregarDetalle(detalle(2L, "SKU-B", 1));
        guardado.agregarDetalle(detalle(1L, "SKU-A", 2));
        when(pedidos.findConDetallesByCodigo(CODIGO)).thenReturn(Optional.of(guardado));

        seguir(pedido(EstadoPedido.CREADO, null, null));

        verify(productos).reservar(CODIGO, "MARKETPLACE",
                List.of(new ProductosClient.LineaReserva("SKU-A", 2), new ProductosClient.LineaReserva("SKU-B", 1)));
        verify(mensajes, never()).procesar(any());
    }

    static DetallePedido detalle(Long id, String sku, int cantidad) {
        DetallePedido d = new DetallePedido();
        d.setId(id);
        d.setSku(sku);
        d.setCantidad(cantidad);
        return d;
    }

    @Test
    void siProductosNoConoceLaConfirmacionDeUnaVentaPagadaSeReenvia() {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, CODIGO);
        when(productos.consultarOperacion(op)).thenReturn(Optional.of(ResultadoOperacion.NO_ENCONTRADA));
        when(productos.confirmar(CODIGO, RESERVA)).thenReturn(ProductosClient.ResultadoConfirmacion.ACEPTADA);

        seguir(pedido(EstadoPedido.PAGADO, RESERVA, null));

        verify(productos).confirmar(CODIGO, RESERVA);
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void siAlReenviarLaConfirmacionLaReservaYaVencioSeAnulaYReembolsa() {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, CODIGO);
        when(productos.consultarOperacion(op)).thenReturn(Optional.of(ResultadoOperacion.NO_ENCONTRADA));
        when(productos.confirmar(CODIGO, RESERVA)).thenReturn(ProductosClient.ResultadoConfirmacion.RESERVA_EXPIRADA);

        seguir(pedido(EstadoPedido.PAGADO, RESERVA, null));

        verify(mensajes).reservaExpiradaTrasPago(CODIGO);
    }

    @Test
    void unReemplazoEnPreparacionNoReenviaLaConfirmacionPorqueSeConfirmaAlSellar() {
        String op = ProductosClient.operacionId(Operacion.CONFIRMAR, CODIGO);
        when(productos.consultarOperacion(op)).thenReturn(Optional.of(ResultadoOperacion.NO_ENCONTRADA));
        when(productos.consultarReserva(RESERVA, CODIGO)).thenReturn(Optional.empty());
        Pedido p = pedido(EstadoPedido.EN_PREPARACION, RESERVA, null);
        when(p.esReemplazo()).thenReturn(true);

        seguir(p);

        verify(productos, never()).confirmar(any(), any());
    }

    // ---------- D-02: cadencia por operación, REQUIRES_REVIEW y 429 ----------

    @Test
    void primeraConsultaALos2sLuegoBackoffYCada60sTrasElDeadline() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "RESERVAR", "PROCESSING", null, null)));
        Pedido p = pedido(EstadoPedido.CREADO, null, null);

        seguimiento.seguir(p, false);                 // t=0: solo plan
        reloj.avanzar(Duration.ofSeconds(1));
        seguimiento.seguir(p, false);                 // t=1: todavía no
        verify(productos, never()).consultarOperacion(op);
        reloj.avanzar(Duration.ofSeconds(1));
        seguimiento.seguir(p, false);                 // t=2: primera consulta
        verify(productos, times(1)).consultarOperacion(op);

        var plan = seguimiento.plan(op);
        long espera = Duration.between(OffsetDateTime.now(reloj), plan.proxima()).toMillis();
        assertThat(espera).isBetween(2000L, 2999L);   // 2 s + jitter

        assertThat(SeguimientoProductos.espera(plan.inicio(), 3, plan.inicio().plusSeconds(20)).toSeconds()).isEqualTo(8);
        assertThat(SeguimientoProductos.espera(plan.inicio(), 9, plan.inicio().plusSeconds(100)).toSeconds()).isEqualTo(30);
        assertThat(SeguimientoProductos.espera(plan.inicio(), 9, plan.inicio().plusSeconds(121))).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void requiresReviewNoEsExitoYNoSeVuelveAConsultar() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        when(productos.consultarOperacion(op))
                .thenReturn(Optional.of(new ResultadoOperacion(op, "RESERVAR", "REQUIRES_REVIEW", null, null)));
        Pedido p = pedido(EstadoPedido.CREADO, null, null);

        seguir(p);
        reloj.avanzar(Duration.ofMinutes(5));
        seguimiento.seguir(p);

        verify(productos, times(1)).consultarOperacion(op);
        verify(mensajes, never()).procesar(any());
    }

    @Test
    void un429PausaElSeguimientoLoQueDigaRetryAfter() {
        String op = ProductosClient.operacionId(Operacion.RESERVAR, CODIGO);
        Pedido p = pedido(EstadoPedido.CREADO, null, null);
        when(pedidos.enSeguimientoProductos(any(), any(), any(), any(), any(), any())).thenReturn(List.of(p));
        when(productos.consultarOperacion(op)).thenThrow(new LimiteProductosException(Duration.ofSeconds(40)));

        seguimiento.barrer();                          // plan
        reloj.avanzar(Duration.ofSeconds(3));
        seguimiento.barrer();                          // 429
        reloj.avanzar(Duration.ofSeconds(30));
        seguimiento.barrer();                          // en pausa: no consulta la base ni Productos

        verify(productos, times(1)).consultarOperacion(op);
        verify(pedidos, times(2)).enSeguimientoProductos(any(), any(), any(), any(), any(), any());
    }
}
