package ventas.anulacion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.anulacion.dto.ResolverAnulacionRequest;
import ventas.anulacion.dto.SolicitarAnulacionRequest;
import ventas.anulacion.dto.SolicitarAnulacionResponse;
import ventas.compartido.ConflictoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.DespachoClient;
import ventas.integracion.DespachoClient.Cancelacion;
import ventas.integracion.ProductosClient;
import ventas.pedido.*;

@ExtendWith(MockitoExtension.class)
class AnulacionServiceTest {

    @Mock
    private PedidoRepository pedidos;

    @Mock
    private AnulacionRepository anulaciones;

    @Mock
    private MaquinaEstados maquinaEstados;

    @Mock
    private ReembolsoAnulacionService reembolsos;

    @Mock
    private ProductosClient productos;

    @Mock
    private DespachoClient despacho;

    @InjectMocks
    private AnulacionService anulacionService;

    private Pedido pedidoCreado;
    private Pedido pedidoPagado;
    private Pedido pedidoEnPrep;

    @BeforeEach
    void setUp() {
        pedidoCreado = new Pedido();
        pedidoCreado.setId(101L);
        pedidoCreado.setCodigo("PED-2026-00101");
        pedidoCreado.setEstado(EstadoPedido.CREADO);
        pedidoCreado.setCanal(CanalVenta.MARKETPLACE);
        pedidoCreado.setTotal(new BigDecimal("150.00"));

        pedidoPagado = new Pedido();
        pedidoPagado.setId(102L);
        pedidoPagado.setCodigo("PED-2026-00102");
        pedidoPagado.setEstado(EstadoPedido.PAGADO);
        pedidoPagado.setCanal(CanalVenta.MARKETPLACE);
        pedidoPagado.setTotal(new BigDecimal("220.00"));
        pedidoPagado.setMoneda("PEN");

        pedidoEnPrep = new Pedido();
        pedidoEnPrep.setId(103L);
        pedidoEnPrep.setCodigo("PED-2026-00103");
        pedidoEnPrep.setEstado(EstadoPedido.EN_PREPARACION);
        pedidoEnPrep.setCanal(CanalVenta.MARKETPLACE);
        pedidoEnPrep.setTotal(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("Anulación directa en CREADO responde 200 sin autorizacion")
    void testAnulacionDirectaCreado() {
        when(pedidos.findByCodigo("PED-2026-00101")).thenReturn(Optional.of(pedidoCreado));
        when(anulaciones.findByIdPedido(101L)).thenReturn(Optional.empty());
        when(anulaciones.saveAndFlush(any(Anulacion.class))).thenAnswer(i -> {
            Anulacion a = i.getArgument(0);
            a.setId(1L);
            return a;
        });

        SolicitarAnulacionRequest req = new SolicitarAnulacionRequest("PAGO_NO_COMPLETADO", "Timeout");
        SolicitarAnulacionResponse resp = anulacionService.solicitarAnulacion("PED-2026-00101", req, ActorHistorial.CANAL_CHATBOT);

        assertNotNull(resp);
        assertEquals("PED-2026-00101", resp.pedidoId());
        assertEquals("ANULADO", resp.estadoPedido());
        assertFalse(resp.autorizacionRequerida());
        verify(maquinaEstados, times(1)).cambiar(pedidoCreado, EstadoPedido.ANULADO, ActorHistorial.CANAL_CHATBOT, "PAGO_NO_COMPLETADO");
    }

    @Test
    @DisplayName("Anulación en EN_PREPARACION responde 202 con codigo de solicitud ANUL-NNNN")
    void testAnulacionEnPreparacionPendiente() {
        when(pedidos.findByCodigo("PED-2026-00103")).thenReturn(Optional.of(pedidoEnPrep));
        when(anulaciones.findByIdPedido(103L)).thenReturn(Optional.empty());
        when(anulaciones.saveAndFlush(any(Anulacion.class))).thenAnswer(i -> {
            Anulacion a = i.getArgument(0);
            a.setId(12L);
            return a;
        });

        SolicitarAnulacionRequest req = new SolicitarAnulacionRequest("SOLICITUD_CLIENTE", "Cliente se mudó");
        SolicitarAnulacionResponse resp = anulacionService.solicitarAnulacion("PED-2026-00103", req, ActorHistorial.CLIENTE);

        assertNotNull(resp);
        assertTrue(resp.autorizacionRequerida());
        assertEquals("ANUL-0012", resp.solicitudAnulacionId());
        assertEquals("EN_PREPARACION", resp.estadoPedido());
        verify(maquinaEstados, never()).cambiar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Rechaza motivo fuera del catalogo con SolicitudInvalidaException 400")
    void testMotivoInvalido() {
        SolicitarAnulacionRequest req = new SolicitarAnulacionRequest("OTRO_MOTIVO", "Comentario");
        assertThrows(SolicitudInvalidaException.class, () ->
                anulacionService.solicitarAnulacion("PED-2026-00101", req, ActorHistorial.CLIENTE));
    }

    @Test
    @DisplayName("Rechaza pedido DESPACHADO con ConflictoException 409")
    void testPedidoDespachadoNoAnulable() {
        Pedido despachado = new Pedido();
        despachado.setCodigo("PED-2026-00104");
        despachado.setEstado(EstadoPedido.DESPACHADO);

        when(pedidos.findByCodigo("PED-2026-00104")).thenReturn(Optional.of(despachado));
        SolicitarAnulacionRequest req = new SolicitarAnulacionRequest("SOLICITUD_CLIENTE", "Comentario");

        assertThrows(ConflictoException.class, () ->
                anulacionService.solicitarAnulacion("PED-2026-00104", req, ActorHistorial.CLIENTE));
    }

    @Test
    @DisplayName("Anulación directa en PAGADO registra reembolso hacia M2 (F4)")
    void testAnulacionDirectaPagadoRegistraReembolso() {
        when(pedidos.findByCodigo("PED-2026-00102")).thenReturn(Optional.of(pedidoPagado));
        when(anulaciones.findByIdPedido(102L)).thenReturn(Optional.empty());
        when(anulaciones.saveAndFlush(any(Anulacion.class))).thenAnswer(i -> {
            Anulacion a = i.getArgument(0);
            a.setId(2L);
            return a;
        });

        SolicitarAnulacionRequest req = new SolicitarAnulacionRequest("SOLICITUD_CLIENTE", "Arrepentimiento");
        SolicitarAnulacionResponse resp = anulacionService.solicitarAnulacion("PED-2026-00102", req, ActorHistorial.CLIENTE);

        assertNotNull(resp);
        assertEquals("PED-2026-00102", resp.pedidoId());
        assertEquals("ANULADO", resp.estadoPedido());
        assertTrue(resp.solicitudReembolsoGenerada());
        verify(reembolsos, times(1)).registrar(eq("ANUL-0002"), eq(102L), eq(new BigDecimal("220.00")), eq("PEN"), eq("SOLICITUD_CLIENTE"));
        verify(reembolsos, times(1)).enviar("ANUL-0002");
    }

    @Test
    @DisplayName("Aprobación tardía cuando pedido ya no está en EN_PREPARACION marca anulación como RECHAZADA y lanza 409")
    void testResolverAnulacionAprobadaTardiaRechazaYPersiste() {
        Anulacion pendiente = new Anulacion();
        pendiente.setId(12L);
        pendiente.setIdPedido(103L);
        pendiente.setEstado(EstadoAnulacion.PENDIENTE_AUTORIZACION);
        pendiente.setMotivo("SOLICITUD_CLIENTE");

        pedidoEnPrep.setEstado(EstadoPedido.DESPACHADO);

        when(anulaciones.findById(12L)).thenReturn(Optional.of(pendiente));
        when(pedidos.findById(103L)).thenReturn(Optional.of(pedidoEnPrep));

        ResolverAnulacionRequest req = new ResolverAnulacionRequest("APROBADA", "ADMIN_VENTAS", "Aprobando tarde");

        ConflictoException ex = assertThrows(ConflictoException.class, () ->
                anulacionService.resolverAnulacion("PED-2026-00103", "ANUL-0012", req));

        assertEquals("PEDIDO_YA_NO_EN_PREPARACION", ex.getCodigo());
        assertEquals(EstadoAnulacion.RECHAZADA, pendiente.getEstado());
        verify(anulaciones, times(1)).saveAndFlush(pendiente);
        verify(maquinaEstados, never()).cambiar(any(), any(), any(), any());
    }

    private Anulacion pendienteDe(Pedido pedido) {
        Anulacion a = new Anulacion();
        a.setId(13L);
        a.setIdPedido(pedido.getId());
        a.setEstado(EstadoAnulacion.PENDIENTE_AUTORIZACION);
        a.setMotivo("SOLICITUD_CLIENTE");
        when(anulaciones.findById(13L)).thenReturn(Optional.of(a));
        when(pedidos.findById(pedido.getId())).thenReturn(Optional.of(pedido));
        return a;
    }

    @Test
    @DisplayName("Aprobar en EN_PREPARACION cancela el despacho, anula, libera stock y pide el reembolso (ESP-06)")
    void aprobarCancelaElDespachoYAnula() {
        pedidoEnPrep.setReservaId("RES-1");
        pedidoEnPrep.setMoneda("PEN");
        Anulacion a = pendienteDe(pedidoEnPrep);
        when(despacho.cancelar("PED-2026-00103", "SOLICITUD_CLIENTE")).thenReturn(Cancelacion.CANCELADO);

        var resp = anulacionService.resolverAnulacion("PED-2026-00103", "ANUL-0013",
                new ResolverAnulacionRequest("APROBADA", null, "ok"));

        assertEquals("ANULADO", resp.estadoPedido());
        assertEquals(EstadoAnulacion.EJECUTADA, a.getEstado());
        verify(despacho).cancelar("PED-2026-00103", "SOLICITUD_CLIENTE");
        verify(maquinaEstados).cambiar(pedidoEnPrep, EstadoPedido.ANULADO, ActorHistorial.GESTOR, "SOLICITUD_CLIENTE");
        verify(productos).liberar("PED-2026-00103", "RES-1", ProductosClient.MotivoLiberacion.ANULACION);
        verify(reembolsos).enviar("ANUL-0013");
    }

    @Test
    @DisplayName("Si Despacho responde 409 (ya en camino), la anulación queda RECHAZADA y el pedido no cambia (ESP-06 CA-06)")
    void despachoEnCaminoRechazaLaAnulacion() {
        Anulacion a = pendienteDe(pedidoEnPrep);
        when(despacho.cancelar(any(), any())).thenReturn(Cancelacion.EN_CAMINO);

        ConflictoException ex = assertThrows(ConflictoException.class, () ->
                anulacionService.resolverAnulacion("PED-2026-00103", "ANUL-0013",
                        new ResolverAnulacionRequest("APROBADA", null, "ok")));

        assertEquals("PEDIDO_EN_CAMINO", ex.getCodigo());
        assertEquals(EstadoAnulacion.RECHAZADA, a.getEstado());
        verify(anulaciones).saveAndFlush(a);
        verify(maquinaEstados, never()).cambiar(any(), any(), any(), any());
        verifyNoInteractions(reembolsos, productos);
    }

    @Test
    @DisplayName("Si Despacho no responde, la anulación sigue y la falla queda en el log (ESP-06 CA-05)")
    void despachoCaidoNoFrenaLaAnulacion() {
        Anulacion a = pendienteDe(pedidoEnPrep);
        when(despacho.cancelar(any(), any())).thenReturn(Cancelacion.FALLO);

        var resp = anulacionService.resolverAnulacion("PED-2026-00103", "ANUL-0013",
                new ResolverAnulacionRequest("APROBADA", null, "ok"));

        assertEquals("ANULADO", resp.estadoPedido());
        assertEquals(EstadoAnulacion.EJECUTADA, a.getEstado());
    }

    @Test
    @DisplayName("Un pedido RETAIL no llama a Despacho")
    void retailNoCancelaDespacho() {
        pedidoEnPrep.setCanal(CanalVenta.RETAIL);
        pendienteDe(pedidoEnPrep);

        anulacionService.resolverAnulacion("PED-2026-00103", "ANUL-0013",
                new ResolverAnulacionRequest("APROBADA", null, "ok"));

        verifyNoInteractions(despacho);
    }
}
