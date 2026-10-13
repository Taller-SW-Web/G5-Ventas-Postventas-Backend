package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import ventas.anulacion.AnulacionSistemaService;
import ventas.anulacion.ReembolsoAnulacionService;
import ventas.integracion.MensajeInventario;
import ventas.integracion.PasarelaClient;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.MotivoLiberacion;
import ventas.integracion.ProductosClient.Operacion;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.MensajesProductosService.Desenlace;

@ExtendWith(MockitoExtension.class)
class MensajesProductosServiceTest {

    static final String CODIGO = "PED-2026-00981";

    @Mock PedidoRepository pedidos;
    @Mock PagoRepository pagos;
    @Mock EventoRecibidoRepository recibidos;
    @Mock MaquinaEstados maquina;
    @Mock AnulacionSistemaService anulaciones;
    @Mock ProductosClient productos;
    @Mock PasarelaClient pasarela;
    @Mock ReembolsoAnulacionService reembolsos;
    @Mock ventas.integracion.CuponesClient cupones;

    MensajesProductosService servicio;
    Pedido pedido;

    @BeforeEach
    void armar() {
        servicio = new MensajesProductosService(pedidos, pagos, recibidos, maquina, anulaciones, productos, pasarela,
                reembolsos, cupones, TransactionOperations.withoutTransaction());
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo(CODIGO);
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setEstado(EstadoPedido.CREADO);
        pedido.setMoneda("PEN");
        pedido.setTotal(new BigDecimal("129.90"));
    }

    private void pedidoExiste() {
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.of(pedido));
    }

    private static MensajeInventario mensaje(String nombre, String operacionId, Map<String, Object> extra) {
        Map<String, Object> data = new java.util.HashMap<>(extra);
        data.put("order_id", CODIGO);
        return new MensajeInventario("msg-1", nombre, operacionId, data);
    }

    private static MensajeInventario creada() {
        return mensaje(MensajeInventario.RESERVA_CREADA, ProductosClient.operacionId(Operacion.RESERVAR, CODIGO),
                Map.of("reservation_id", "RES-778"));
    }

    private static MensajeInventario rechazo(Operacion operacion) {
        return mensaje(MensajeInventario.CONSUMO_RECHAZADO, ProductosClient.operacionId(operacion, CODIGO),
                Map.of("code", "STOCK_INSUFICIENTE"));
    }

    // ---------- inventory.reservation.created ----------

    @Test
    void marketplaceReservaCreadaGuardaReservaYPideElCobro() {
        pedidoExiste();
        Pago pago = new Pago();
        pago.setMetodo("TARJETA_CREDITO");
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        assertThat(servicio.procesar(creada())).isEqualTo(Desenlace.APLICADO);

        assertThat(pedido.getReservaId()).isEqualTo("RES-778");
        verify(pasarela).iniciarCobro(CODIGO, new BigDecimal("129.90"), "PEN", "TARJETA_CREDITO");
    }

    @Test
    void chatbotReservaCreadaNoPideCobro() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedidoExiste();

        servicio.procesar(creada());

        assertThat(pedido.getReservaId()).isEqualTo("RES-778");
        verifyNoInteractions(pasarela);
    }

    @Test
    void siYaSePagoSeConfirmaElConsumo() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();
        when(productos.confirmar(CODIGO, "RES-778")).thenReturn(ResultadoConfirmacion.ACEPTADA);

        servicio.procesar(creada());

        verify(productos).confirmar(CODIGO, "RES-778");
        verifyNoInteractions(pasarela);
    }

    @Test
    void siYaSeAnuloSeLibera() {
        pedido.setEstado(EstadoPedido.ANULADO);
        pedidoExiste();

        servicio.procesar(creada());

        verify(productos).liberar(CODIGO, "RES-778", MotivoLiberacion.ANULACION);
    }

    // ---------- inventory.consumption.rejected ----------

    @Test
    void rechazoDeLaReservaAnulaElPedidoCreado() {
        pedidoExiste();

        servicio.procesar(rechazo(Operacion.RESERVAR));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                MensajesProductosService.MOTIVO_SIN_STOCK);
        verifyNoInteractions(reembolsos, pasarela);
    }

    @Test
    void rechazoDeLaReservaConPedidoPagadoAnulaYReembolsa() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();
        when(anulaciones.registrarEjecutada(981L, MensajesProductosService.MOTIVO_SIN_STOCK)).thenReturn("ANUL-0007");

        servicio.procesar(rechazo(Operacion.RESERVAR));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                MensajesProductosService.MOTIVO_SIN_STOCK);
        verify(reembolsos).registrar(eq("ANUL-0007"), eq(981L), eq(new BigDecimal("129.90")), eq("PEN"), anyString());
        verify(reembolsos).enviar("ANUL-0007");
    }

    @Test
    void rechazoDeLaConfirmacionAnulaPorReservaExpiradaYReembolsa() {
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();
        when(anulaciones.registrarEjecutada(981L, MensajesProductosService.MOTIVO_RESERVA_EXPIRADA)).thenReturn("ANUL-0008");

        servicio.procesar(rechazo(Operacion.CONFIRMAR));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                MensajesProductosService.MOTIVO_RESERVA_EXPIRADA);
        verify(reembolsos).registrar(eq("ANUL-0008"), eq(981L), any(), eq("PEN"), anyString());
        verify(reembolsos).enviar("ANUL-0008");
    }

    @Test
    void rechazoConOperacionDesconocidaNoCambiaNada() {
        pedidoExiste();

        servicio.procesar(mensaje(MensajeInventario.CONSUMO_RECHAZADO, "otra-operacion", Map.of()));

        verifyNoInteractions(maquina, reembolsos, anulaciones);
    }

    // ---------- inventory.reservation.expired y los que solo se registran ----------

    @Test
    void reservaExpiradaAnulaElPedidoCreado() {
        pedidoExiste();

        servicio.procesar(mensaje(MensajeInventario.RESERVA_EXPIRADA, null, Map.of()));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                MensajesProductosService.MOTIVO_RESERVA_EXPIRADA);
    }

    @Test
    void reservaExpiradaConPedidoPagadoSoloSeRegistra() {
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();

        assertThat(servicio.procesar(mensaje(MensajeInventario.RESERVA_EXPIRADA, null, Map.of())))
                .isEqualTo(Desenlace.APLICADO);
        verifyNoInteractions(maquina);
        verify(recibidos).saveAndFlush(any());
    }

    @Test
    void consumidaYLiberadaSoloSeRegistran() {
        pedidoExiste();

        servicio.procesar(mensaje(MensajeInventario.RESERVA_CONSUMIDA, null, Map.of()));

        verifyNoInteractions(maquina, pasarela, productos, reembolsos);
        verify(recibidos).saveAndFlush(any());
    }

    // ---------- idempotencia y casos raros ----------

    @Test
    void mensajeRepetidoNoHaceNada() {
        when(recibidos.existsByIdEventoAndOrigen("msg-1", OrigenEvento.SISTEMA_PRODUCTOS)).thenReturn(true);

        assertThat(servicio.procesar(creada())).isEqualTo(Desenlace.REPETIDO);
        verifyNoInteractions(pedidos, maquina, pasarela, productos);
    }

    @Test
    void dosMensajesIgualesALaVezSeTratanComoRepetido() {
        pedidoExiste();
        when(recibidos.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("evento_recibido_pkey"));

        assertThat(servicio.procesar(creada())).isEqualTo(Desenlace.REPETIDO);
        verifyNoInteractions(pasarela, productos);
    }

    @Test
    void sinPedidoTodaviaPideReintentar() {
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.empty());

        assertThat(servicio.procesar(creada())).isEqualTo(Desenlace.PEDIDO_NO_ENCONTRADO);
        verify(recibidos, never()).saveAndFlush(any());
    }

    @Test
    void mensajeSinOrderIdSeIgnora() {
        assertThat(servicio.procesar(new MensajeInventario("msg-2", MensajeInventario.RESERVA_CREADA, null, Map.of())))
                .isEqualTo(Desenlace.IGNORADO);
        verifyNoInteractions(pedidos);
    }

    @Test
    void confirmarRechazadoAlInstantePorReservaExpiradaAnulaYReembolsa() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        when(pedidos.findByCodigo(CODIGO)).thenReturn(Optional.of(pedido));
        when(productos.confirmar(CODIGO, "RES-778")).thenReturn(ResultadoConfirmacion.RESERVA_EXPIRADA);
        when(anulaciones.registrarEjecutada(981L, MensajesProductosService.MOTIVO_RESERVA_EXPIRADA)).thenReturn("ANUL-0009");

        servicio.procesar(creada());

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                MensajesProductosService.MOTIVO_RESERVA_EXPIRADA);
        verify(reembolsos).registrar(eq("ANUL-0009"), eq(981L), any(), eq("PEN"), anyString());
        verify(reembolsos).enviar("ANUL-0009");
    }

    // ---------- cupón (kit G6 §18: reserva -> consumo de cupón -> pago) ----------

    private static MensajeInventario cupon(String nombre) {
        return new MensajeInventario("msg-cupon", nombre, ventas.integracion.CuponesClient.operacionConsumo(CODIGO),
                Map.of("order_id", CODIGO, "code", "CUPON_AGOTADO"));
    }

    @Test
    void conCuponLaReservaCreadaPideConsumirloAntesDelCobro() {
        pedido.setCodigoCupon("DESC10");
        pedido.setIdCliente(java.util.UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a"));
        pedido.setCreadoEn(java.time.OffsetDateTime.parse("2026-10-13T10:00:00Z"));
        pedidoExiste();
        when(cupones.consumir(any(), any(), any(), any(), any())).thenReturn(Optional.empty());   // modo real: llega después

        servicio.procesar(creada());

        verify(cupones).consumir(CODIGO, "DESC10", pedido.getIdCliente(), "MARKETPLACE", pedido.getCreadoEn());
        verifyNoInteractions(pasarela);   // todavía no se cobra
    }

    @Test
    void cuponConsumidoHabilitaElCobroEnMarketplace() {
        pedido.setCodigoCupon("DESC10");
        pedidoExiste();
        Pago pago = new Pago();
        pago.setMetodo("TARJETA_CREDITO");
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        servicio.procesar(cupon(MensajeInventario.CUPON_CONSUMIDO));

        verify(pasarela).iniciarCobro(CODIGO, new BigDecimal("129.90"), "PEN", "TARJETA_CREDITO");
    }

    @Test
    void enModoSimuladoElCuponSeConsumeYSeCobraEnSeguida() {
        pedido.setCodigoCupon("DESC10");
        pedido.setCreadoEn(java.time.OffsetDateTime.parse("2026-10-13T10:00:00Z"));
        pedidoExiste();
        Pago pago = new Pago();
        pago.setMetodo("TARJETA_CREDITO");
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));
        when(cupones.consumir(any(), any(), any(), any(), any())).thenReturn(Optional.of(new MensajeInventario(
                "SIM-cupon", MensajeInventario.CUPON_CONSUMIDO, "op", Map.of("order_id", CODIGO))));

        servicio.procesar(creada());

        verify(pasarela).iniciarCobro(CODIGO, new BigDecimal("129.90"), "PEN", "TARJETA_CREDITO");
    }

    @Test
    void cuponRechazadoAnulaElPedidoYLiberaLaReserva() {
        pedido.setCodigoCupon("DESC10");
        pedido.setReservaId("RES-778");
        pedidoExiste();

        servicio.procesar(cupon(MensajeInventario.CUPON_RECHAZADO));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                MensajesProductosService.MOTIVO_CUPON_RECHAZADO);
        verify(productos).liberar(CODIGO, "RES-778", MotivoLiberacion.ANULACION);
        verifyNoInteractions(pasarela, reembolsos);
    }

    @Test
    void cuponRechazadoConElPedidoYaPagadoAnulaYReembolsa() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedido.setCodigoCupon("DESC10");
        pedidoExiste();
        when(anulaciones.registrarEjecutada(981L, MensajesProductosService.MOTIVO_CUPON_RECHAZADO)).thenReturn("ANUL-0010");

        servicio.procesar(cupon(MensajeInventario.CUPON_RECHAZADO));

        verify(reembolsos).registrar(eq("ANUL-0010"), eq(981L), any(), eq("PEN"), anyString());
        verify(reembolsos).enviar("ANUL-0010");
    }

    @Test
    void elChatbotQuePagoAntesConsumeElCuponAlConfirmarLaReserva() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedido.setCodigoCupon("DESC10");
        pedido.setCreadoEn(java.time.OffsetDateTime.parse("2026-10-13T10:00:00Z"));
        pedidoExiste();
        when(productos.confirmar(CODIGO, "RES-778")).thenReturn(ResultadoConfirmacion.ACEPTADA);
        when(cupones.consumir(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        servicio.procesar(creada());

        verify(cupones).consumir(eq(CODIGO), eq("DESC10"), any(), eq("CHATBOT"), any());
    }

    @Test
    void retailNoConsumeCuponPorM1() {
        pedido.setCanal(CanalVenta.RETAIL);
        pedido.setCodigoCupon("DESC10");
        assertThat(MensajesProductosService.tieneCupon(pedido)).isFalse();
        pedido.setCanal(CanalVenta.CHATBOT);
        assertThat(MensajesProductosService.tieneCupon(pedido)).isTrue();
        pedido.setCodigoCupon(" ");
        assertThat(MensajesProductosService.tieneCupon(pedido)).isFalse();
    }
}
