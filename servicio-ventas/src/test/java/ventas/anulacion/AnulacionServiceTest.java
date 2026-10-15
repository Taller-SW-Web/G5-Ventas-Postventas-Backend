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

import ventas.anulacion.dto.SolicitarAnulacionRequest;
import ventas.anulacion.dto.SolicitarAnulacionResponse;
import ventas.compartido.ConflictoException;
import ventas.compartido.SolicitudInvalidaException;
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
}
