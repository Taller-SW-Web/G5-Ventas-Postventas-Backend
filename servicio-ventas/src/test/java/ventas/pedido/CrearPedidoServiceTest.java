package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.ConflictoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.MensajeInventario;
import ventas.integracion.ProductosClient;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoRequest.Item;

/** 1.1 sin base de datos: repositorios, máquina de estados y clientes simulados con Mockito. */
@ExtendWith(MockitoExtension.class)
class CrearPedidoServiceTest {

    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Mock PedidoRepository pedidos;
    @Mock PagoRepository pagos;
    @Mock MaquinaEstados maquina;
    @Mock ProductosClient productos;
    @Mock MensajesProductosService mensajesProductos;

    CrearPedidoService servicio;

    @BeforeEach
    void armar() {
        servicio = new CrearPedidoService(new ValidadorPedido(), pedidos, pagos, maquina, productos, mensajesProductos,
                TransactionOperations.withoutTransaction());
    }

    /** saveAndFlush simula el id que daría la base. */
    private void conIdGenerado(long id) {
        when(pedidos.saveAndFlush(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.setId(id);
            return p;
        });
    }

    // ---------- CA-01: registro por canal ----------

    @Test
    void chatbotNaceCreadoConPagoPendienteYReservaStock() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(981);

        CrearPedidoService.Resultado r = servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto()));

        assertThat(r.creado()).isTrue();
        assertThat(r.pedido().pedidoId()).matches("PED-\\d{4}-00981");
        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(r.pedido().total()).isEqualByComparingTo("129.90");
        assertThat(r.pedido().moneda()).isEqualTo("PEN");
        assertThat(r.pedido().fechaCreacion()).isNotNull();

        // Se reserva con el código del pedido ya guardado y el canal
        verify(productos).reservar(eq(r.pedido().pedidoId()), eq("CHATBOT"), anyList());
        verify(maquina).registrarInicial(any(Pedido.class), eq(ActorHistorial.CANAL_CHATBOT),
                eq(CrearPedidoService.MOTIVO_INICIAL), any(OffsetDateTime.class));
        verify(maquina, never()).cambiar(any(), any(), any(), any(), any());
        verifyNoInteractions(mensajesProductos);   // en modo real el resultado llega por po.sales.q

        ArgumentCaptor<Pago> pago = ArgumentCaptor.forClass(Pago.class);
        verify(pagos).save(pago.capture());
        assertThat(pago.getValue().getEstado()).isEqualTo(EstadoPago.PENDIENTE);
        assertThat(pago.getValue().getMonto()).isEqualByComparingTo("129.90");
    }

    @Test
    void guardaContactoEnvioIdempotencyKeyYDetalles() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(5);

        servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto()));

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidos).saveAndFlush(captor.capture());
        Pedido p = captor.getValue();
        assertThat(p.getIdempotencyKey()).isEqualTo(CLAVE);
        assertThat(p.getIdCliente()).isEqualTo(contacto().clienteId());
        assertThat(p.getTipoDocumento()).isEqualTo("DNI");
        assertThat(p.getDireccion()).isEqualTo("Av. La Marina 1234");
        assertThat(p.getDescuento()).isEqualByComparingTo("10.00");
        assertThat(p.getDetalles()).singleElement().satisfies(d -> {
            assertThat(d.getSku()).isEqualTo("SKU-AUR-01");
            assertThat(d.getIdProducto()).isEqualTo(101L);
            assertThat(d.getImporte()).isEqualByComparingTo("129.90");
        });
    }

    @Test
    void conReservaInmediataSeAplicaDespuesDeGuardar() {
        // Modo simulado: ProductosClient devuelve el resultado al instante y se aplica tras el commit
        MensajeInventario reserva = new MensajeInventario("SIM-1", MensajeInventario.RESERVA_CREADA, "op", Map.of());
        when(productos.reservar(anyString(), eq("MARKETPLACE"), anyList())).thenReturn(Optional.of(reserva));
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(7);
        CrearPedidoRequest base = ValidadorPedidoTest.chatbot(contacto());
        CrearPedidoRequest req = new CrearPedidoRequest(CanalVenta.MARKETPLACE, base.contacto(), base.items(),
                null, base.envio(), base.pago());

        CrearPedidoService.Resultado r = servicio.crear(CLAVE, req);

        assertThat(r.pedido().estado()).isEqualTo(EstadoPedido.CREADO);
        verify(maquina).registrarInicial(any(), eq(ActorHistorial.CLIENTE), anyString(), any());
        verify(mensajesProductos).procesar(reserva);
    }

    @Test
    void retailNacePagadoSeEntregaEnTiendaYNoReservaStock() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(12);

        servicio.crear(CLAVE, ValidadorPedidoTest.retail("TARJETA_POS", "VOU-778", null, null, null));

        verifyNoInteractions(productos, mensajesProductos);
        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(maquina).registrarInicial(captor.capture(), eq(ActorHistorial.CLIENTE), anyString(), any());
        assertThat(captor.getValue().getEstado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(captor.getValue().getTiendaId()).isEqualTo("TIENDA-MIRAFLORES");
        assertThat(captor.getValue().getDireccion()).isNull();
        verify(maquina).cambiar(any(Pedido.class), eq(EstadoPedido.ENTREGADO), eq(ActorHistorial.CLIENTE),
                eq(CrearPedidoService.MOTIVO_ENTREGA_TIENDA), any(OffsetDateTime.class));

        ArgumentCaptor<Pago> pago = ArgumentCaptor.forClass(Pago.class);
        verify(pagos).save(pago.capture());
        assertThat(pago.getValue().getEstado()).isEqualTo(EstadoPago.CONFIRMADO);
        assertThat(pago.getValue().getReferencia()).isEqualTo("VOU-778");
    }

    @Test
    void retailMixtoGuardaLosMontosYLaFechaDeVenta() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(14);
        OffsetDateTime hace5h = OffsetDateTime.now().minusHours(5);

        servicio.crear(CLAVE, ValidadorPedidoTest.retail("MIXTO", "VOU-1", "29.90", "100.00", hace5h));

        ArgumentCaptor<Pago> pago = ArgumentCaptor.forClass(Pago.class);
        verify(pagos).save(pago.capture());
        assertThat(pago.getValue().getMontoEfectivo()).isEqualByComparingTo("29.90");
        assertThat(pago.getValue().getMontoTarjeta()).isEqualByComparingTo("100.00");
        assertThat(pago.getValue().getFechaVenta()).isEqualTo(hace5h);
        assertThat(pago.getValue().getReferencia()).isEqualTo("VOU-1");
    }

    @Test
    void retailConFechaVentaLaUsaSoloEnLaEntrega() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(13);
        OffsetDateTime ayer = OffsetDateTime.now().minusDays(1);

        servicio.crear(CLAVE, ValidadorPedidoTest.retail("EFECTIVO", null, null, null, ayer));

        // null -> PAGADO con la hora real de la llamada; PAGADO -> ENTREGADO con fechaVenta (acuerdo G4, R8)
        ArgumentCaptor<OffsetDateTime> inicial = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(maquina).registrarInicial(any(), any(), anyString(), inicial.capture());
        assertThat(inicial.getValue()).isAfter(ayer.plusHours(23));
        verify(maquina).cambiar(any(), eq(EstadoPedido.ENTREGADO), any(), anyString(), eq(ayer));
    }

    // ---------- Idempotencia (acuerdo H-02) ----------

    @Test
    void mismaClaveYMismoCuerpoDevuelveElPedidoExistenteSinGuardar() {
        Pedido existente = guardado(ValidadorPedidoTest.chatbot(contacto()));
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.of(existente));

        CrearPedidoService.Resultado r = servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto()));

        assertThat(r.creado()).isFalse();
        assertThat(r.pedido().pedidoId()).isEqualTo("PED-2026-00981");
        verify(pedidos, never()).saveAndFlush(any());
        verifyNoInteractions(productos, pagos, maquina, mensajesProductos);
    }

    @Test
    void mismaClaveConOtroCuerpoDa409() {
        Pedido existente = guardado(ValidadorPedidoTest.chatbot(contacto()));
        existente.getDetalles().get(0).setCantidad(2);
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto())))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        verify(pedidos, never()).saveAndFlush(any());
    }

    @Test
    void sinClaveDa400YNoTocaLaBase() {
        assertThatThrownBy(() -> servicio.crear(null, ValidadorPedidoTest.chatbot(contacto())))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CABECERA_FALTANTE");
        verifyNoInteractions(pedidos, productos, maquina);
    }

    @Test
    void errorDeValidacionNoGuardaLaClave() {
        CrearPedidoRequest malo = ValidadorPedidoTest.chatbot(ValidadorPedidoTest.contacto(TipoDocumento.DNI, "123"));

        assertThatThrownBy(() -> servicio.crear(CLAVE, malo)).isInstanceOf(SolicitudInvalidaException.class);
        verifyNoInteractions(pedidos, productos, maquina);
    }

    @Test
    void siProductosNoAdmiteLaReservaFallaDentroDeLaTransaccion() {
        // Con transacción real el pedido se deshace; aquí basta con ver que el error sale y no se aplica nada más
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        conIdGenerado(9);
        doThrow(new ConflictoException("PRODUCTOS_NO_DISPONIBLE", "Productos no responde"))
                .when(productos).reservar(anyString(), anyString(), anyList());

        assertThatThrownBy(() -> servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto())))
                .extracting("codigo").isEqualTo("PRODUCTOS_NO_DISPONIBLE");
        verifyNoInteractions(mensajesProductos);
    }

    @Test
    void siFallaElGuardadoNoSeReserva() {
        // Primero se guarda y luego se reserva: si el guardado falla, Productos no se entera
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        when(pedidos.saveAndFlush(any(Pedido.class))).thenThrow(new IllegalStateException("base caída"));

        assertThatThrownBy(() -> servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(productos);
    }

    @Test
    void retailQueFallaNoLiberaPorqueNoReservo() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());
        when(pedidos.saveAndFlush(any(Pedido.class))).thenThrow(new IllegalStateException("base caída"));

        assertThatThrownBy(() -> servicio.crear(CLAVE, ValidadorPedidoTest.retail("EFECTIVO", null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(productos);
    }

    @Test
    void dosPeticionesSimultaneasConLaMismaClaveDevuelvenElMismoPedido() {
        Pedido ganador = guardado(ValidadorPedidoTest.chatbot(contacto()));
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty(), Optional.of(ganador));
        when(pedidos.saveAndFlush(any(Pedido.class))).thenThrow(new DataIntegrityViolationException("pedido_idempotency_key_key"));

        CrearPedidoService.Resultado r = servicio.crear(CLAVE, ValidadorPedidoTest.chatbot(contacto()));

        assertThat(r.creado()).isFalse();
        assertThat(r.pedido().pedidoId()).isEqualTo("PED-2026-00981");
        // Falló al guardar: no se llegó a reservar
        verifyNoInteractions(productos);
    }

    @Test
    void idProductoSaleDelNumeroFinal() {
        assertThat(CrearPedidoService.idProducto("PROD-101")).isEqualTo(101L);
        assertThat(CrearPedidoService.idProducto("55")).isEqualTo(55L);
        assertThat(CrearPedidoService.idProducto("AUDIFONOS")).isNull();
    }

    // ---------- datos ----------

    private static CrearPedidoRequest.Contacto contacto() {
        return ValidadorPedidoTest.contacto(TipoDocumento.DNI, "72458912");
    }

    /** Pedido como quedaría en la base después de crearse con req (moneda char(3) incluida). */
    private static Pedido guardado(CrearPedidoRequest req) {
        Pedido p = new Pedido();
        p.setId(981L);
        p.setCodigo("PED-2026-00981");
        p.setIdempotencyKey(CLAVE);
        p.setCanal(req.canal());
        p.setEstado(EstadoPedido.CREADO);
        p.setIdCliente(req.contacto().clienteId());
        p.setMoneda(req.pago().moneda());
        p.setTotal(new BigDecimal("129.90"));
        p.setCreadoEn(OffsetDateTime.now());
        for (Item i : req.items()) {
            DetallePedido d = new DetallePedido();
            d.setSku(i.sku());
            d.setCantidad(i.cantidad());
            d.setPrecioUnitario(new BigDecimal("129.9000"));
            p.agregarDetalle(d);
        }
        return p;
    }

    @Test
    void comparaPrecioSinImportarLaEscala() {
        CrearPedidoRequest req = ValidadorPedidoTest.chatbot(contacto());
        assertThat(CrearPedidoService.mismoPedido(guardado(req), req)).isTrue();
        Pedido otroCliente = guardado(req);
        otroCliente.setIdCliente(UUID.randomUUID());
        assertThat(CrearPedidoService.mismoPedido(otroCliente, req)).isFalse();
    }
}
