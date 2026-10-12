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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import ventas.integracion.PasarelaClient;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.ResultadoReserva;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.ResultadoReservaService.Desenlace;

@ExtendWith(MockitoExtension.class)
class ResultadoReservaServiceTest {

    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    static final ResultadoReserva CONFIRMADA = new ResultadoReserva(CLAVE, "RES-778", true);
    static final ResultadoReserva RECHAZADA = new ResultadoReserva(CLAVE, null, false);

    @Mock PedidoRepository pedidos;
    @Mock PagoRepository pagos;
    @Mock EventoRecibidoRepository recibidos;
    @Mock MaquinaEstados maquina;
    @Mock ProductosClient productos;
    @Mock PasarelaClient pasarela;

    ResultadoReservaService servicio;
    Pedido pedido;

    @BeforeEach
    void armar() {
        servicio = new ResultadoReservaService(pedidos, pagos, recibidos, maquina, productos, pasarela,
                TransactionOperations.withoutTransaction());
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo("PED-2026-00981");
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setEstado(EstadoPedido.CREADO);
        pedido.setMoneda("PEN");
        pedido.setTotal(new BigDecimal("129.90"));
    }

    private void pedidoExiste() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.of(pedido));
    }

    @Test
    void marketplaceConfirmadaGuardaReservaYPideElCobro() {
        pedidoExiste();
        Pago pago = new Pago();
        pago.setMetodo("TARJETA_CREDITO");
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        assertThat(servicio.procesar(CONFIRMADA)).isEqualTo(Desenlace.APLICADO);

        assertThat(pedido.getReservaId()).isEqualTo("RES-778");
        verify(pedidos).save(pedido);
        verify(pasarela).iniciarCobro("PED-2026-00981", new BigDecimal("129.90"), "PEN", "TARJETA_CREDITO");
        verify(productos, never()).confirmar(anyString());
    }

    @Test
    void chatbotConfirmadaNoPideCobro() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedidoExiste();

        servicio.procesar(CONFIRMADA);

        assertThat(pedido.getReservaId()).isEqualTo("RES-778");
        verifyNoInteractions(pasarela);
    }

    @Test
    void siYaSePagoSeConfirmaElConsumoEnseguida() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();

        servicio.procesar(CONFIRMADA);

        verify(productos).confirmar("RES-778");
        verifyNoInteractions(pasarela);
    }

    @Test
    void siYaSeAnuloSeLiberaElStock() {
        pedido.setEstado(EstadoPedido.ANULADO);
        pedidoExiste();

        servicio.procesar(CONFIRMADA);

        verify(productos).liberar("RES-778");
    }

    @Test
    void rechazadaAnulaElPedidoCreado() {
        pedidoExiste();

        servicio.procesar(RECHAZADA);

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_PRODUCTOS,
                ResultadoReservaService.MOTIVO_SIN_STOCK);
        verifyNoInteractions(pasarela, productos);
    }

    @Test
    void rechazadaSobrePedidoPagadoQuedaParaRevision() {
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();

        servicio.procesar(RECHAZADA);

        verifyNoInteractions(maquina, pasarela, productos);
    }

    @Test
    void resultadoRepetidoNoHaceNada() {
        when(recibidos.existsByIdEventoAndOrigen("reserva:" + CLAVE, OrigenEvento.SISTEMA_PRODUCTOS)).thenReturn(true);

        assertThat(servicio.procesar(CONFIRMADA)).isEqualTo(Desenlace.REPETIDO);
        verifyNoInteractions(pedidos, maquina, pasarela, productos);
    }

    @Test
    void dosResultadosIgualesALaVezSeTratanComoRepetido() {
        pedidoExiste();
        when(recibidos.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("evento_recibido_pkey"));

        assertThat(servicio.procesar(CONFIRMADA)).isEqualTo(Desenlace.REPETIDO);
        verifyNoInteractions(pasarela, productos);
    }

    @Test
    void sinPedidoAvisaParaReintentarYLuegoSeLibera() {
        when(pedidos.findByIdempotencyKey(CLAVE)).thenReturn(Optional.empty());

        assertThat(servicio.procesar(CONFIRMADA)).isEqualTo(Desenlace.PEDIDO_NO_ENCONTRADO);
        verify(recibidos, never()).saveAndFlush(any());

        servicio.huerfana(CONFIRMADA);
        verify(productos).liberar("RES-778");
    }

    @Test
    void huerfanaRechazadaNoLiberaNada() {
        servicio.huerfana(RECHAZADA);
        verify(productos, never()).liberar(any());
    }

    @Test
    void elIdEventoUsaLaReferencia() {
        pedido.setCanal(CanalVenta.CHATBOT);
        pedidoExiste();
        servicio.procesar(CONFIRMADA);
        verify(recibidos).existsByIdEventoAndOrigen(eq("reserva:" + CLAVE), eq(OrigenEvento.SISTEMA_PRODUCTOS));
    }
}
