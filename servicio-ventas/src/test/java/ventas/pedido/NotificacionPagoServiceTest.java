package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.NotificacionPagoRequest;
import ventas.pedido.dto.NotificacionPagoResponse;

@ExtendWith(MockitoExtension.class)
class NotificacionPagoServiceTest {

    static final String CODIGO = "PED-2026-00981";
    static final String TX = "TX-PASARELA-778120";
    static final OffsetDateTime FECHA_PAGO = OffsetDateTime.parse("2026-09-23T00:06:00Z");

    @Mock PedidoRepository pedidos;
    @Mock PagoRepository pagos;
    @Mock EventoRecibidoRepository recibidos;
    @Mock MaquinaEstados maquina;

    NotificacionPagoService servicio;
    Pedido pedido;
    Pago pago;

    @BeforeEach
    void armar() {
        servicio = new NotificacionPagoService(pedidos, pagos, recibidos, maquina, TransactionOperations.withoutTransaction());
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo(CODIGO);
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setEstado(EstadoPedido.CREADO);
        pedido.setMoneda("PEN");
        pedido.setTotal(new BigDecimal("129.90"));
        pago = new Pago();
        pago.setPedido(pedido);
        pago.setMetodo("TARJETA_CREDITO");
        pago.setMonto(new BigDecimal("129.90"));
        pago.setEstado(EstadoPago.PENDIENTE);
    }

    private void pedidoExiste() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
    }

    // ---------- CA-01 / CA-02 de ESP-03 ----------

    @Test
    void pagoAprobadoPasaAPagadoConActorPasarela() {
        pedidoExiste();
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));
        // la máquina de estados real cambia el estado; aquí lo simulamos
        doAnswer(inv -> { pedido.setEstado(EstadoPedido.PAGADO); return null; })
                .when(maquina).cambiar(eq(pedido), eq(EstadoPedido.PAGADO), eq(ActorHistorial.PASARELA_PAGOS), any(), any());

        NotificacionPagoResponse r = servicio.procesar(CODIGO, notificacion(ResultadoPasarela.APROBADO, "129.90"));

        assertThat(r.pedidoId()).isEqualTo(CODIGO);
        assertThat(r.nuevoEstado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(r.transaccionId()).isEqualTo(TX);
        assertThat(r.fechaTransicion()).isEqualTo(FECHA_PAGO);
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.CONFIRMADO);
        assertThat(pago.getReferencia()).isEqualTo(TX);
        assertThat(pago.getFechaProceso()).isEqualTo(FECHA_PAGO);
        verify(maquina).cambiar(pedido, EstadoPedido.PAGADO, ActorHistorial.PASARELA_PAGOS,
                NotificacionPagoService.MOTIVO_PAGO_OK, FECHA_PAGO);

        ArgumentCaptor<EventoRecibido> clave = ArgumentCaptor.forClass(EventoRecibido.class);
        verify(recibidos).saveAndFlush(clave.capture());
        assertThat(clave.getValue().getIdEvento()).isEqualTo(TX);
        assertThat(clave.getValue().getOrigen()).isEqualTo(OrigenEvento.PASARELA_PAGOS);
    }

    @Test
    void pagoRechazadoAnulaPorPagoNoCompletadoSinGestor() {
        pedidoExiste();
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        servicio.procesar(CODIGO, notificacion(ResultadoPasarela.RECHAZADO, "129.90"));

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.RECHAZADO);
        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.PASARELA_PAGOS,
                NotificacionPagoService.MOTIVO_PAGO_RECHAZADO, FECHA_PAGO);
    }

    @Test
    void rechazoAvisadoPorElChatbotTambienLoRegistraLaPasarela() {
        // ESP-03 7.2: CANAL_CHATBOT es para el aviso del canal (1.7), no para la notificación de 1.2
        pedido.setCanal(CanalVenta.CHATBOT);
        pedidoExiste();
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        servicio.procesar(CODIGO, notificacion(ResultadoPasarela.RECHAZADO, "129.90"));

        verify(maquina).cambiar(eq(pedido), eq(EstadoPedido.ANULADO), eq(ActorHistorial.PASARELA_PAGOS), any(), any());
    }

    // ---------- CA-05: idempotencia ----------

    @Test
    void reintentoDeLaMismaTransaccionNoCambiaNada() {
        pedido.setEstado(EstadoPedido.PAGADO);
        pago.setReferencia(TX);
        pago.setFechaProceso(FECHA_PAGO);
        pedidoExiste();
        when(recibidos.existsByIdEventoAndOrigen(TX, OrigenEvento.PASARELA_PAGOS)).thenReturn(true);
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        NotificacionPagoResponse r = servicio.procesar(CODIGO, notificacion(ResultadoPasarela.APROBADO, "129.90"));

        assertThat(r.nuevoEstado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(r.fechaTransicion()).isEqualTo(FECHA_PAGO);
        verifyNoInteractions(maquina);
        verify(recibidos, never()).saveAndFlush(any());
        verify(pagos, never()).save(any());
    }

    @Test
    void transaccionYaUsadaEnOtroPedidoDa409() {
        pago.setReferencia("TX-OTRA");
        pedidoExiste();
        when(recibidos.existsByIdEventoAndOrigen(TX, OrigenEvento.PASARELA_PAGOS)).thenReturn(true);
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> servicio.procesar(CODIGO, notificacion(ResultadoPasarela.APROBADO, "129.90")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("TRANSACCION_DE_OTRO_PEDIDO");
    }

    @Test
    void dosNotificacionesIgualesALaVezSeRespondenComoReintento() {
        pedidoExiste();
        when(pagos.findByPedidoId(981L)).thenReturn(Optional.of(pago));
        when(recibidos.saveAndFlush(any())).thenAnswer(inv -> {
            // la otra petición ya guardó la clave y el pago
            pago.setReferencia(TX);
            pago.setFechaProceso(FECHA_PAGO);
            throw new DataIntegrityViolationException("evento_recibido_pkey");
        });

        NotificacionPagoResponse r = servicio.procesar(CODIGO, notificacion(ResultadoPasarela.APROBADO, "129.90"));

        assertThat(r.transaccionId()).isEqualTo(TX);
        verifyNoInteractions(maquina);
    }

    // ---------- errores del contrato ----------

    @Test
    void pedidoInexistenteDa404() {
        when(pedidos.findById(981L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.procesar(CODIGO, notificacion(ResultadoPasarela.APROBADO, "129.90")))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_ENCONTRADO");
    }

    @Test
    void codigoDeOtroAnioDa404() {
        pedidoExiste();

        assertThatThrownBy(() -> servicio.procesar("PED-2025-00981", notificacion(ResultadoPasarela.APROBADO, "129.90")))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void pedidoQueNoEstaCreadoDa409() {
        pedido.setEstado(EstadoPedido.PAGADO);
        pedidoExiste();

        assertThatThrownBy(() -> servicio.procesar(CODIGO, notificacion(ResultadoPasarela.RECHAZADO, "129.90")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("ESTADO_NO_PERMITE_PAGO");
        verifyNoInteractions(maquina);
        verify(recibidos, never()).saveAndFlush(any());
    }

    @Test
    void montoDistintoAlTotalDa400() {
        pedidoExiste();

        assertThatThrownBy(() -> servicio.procesar(CODIGO, notificacion(ResultadoPasarela.APROBADO, "100.00")))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("MONTOS_INCONSISTENTES");
        verifyNoInteractions(maquina);
    }

    /** Ejemplo de api-ventas.md 1.2. */
    static NotificacionPagoRequest notificacion(ResultadoPasarela resultado, String monto) {
        return new NotificacionPagoRequest(TX, resultado, new BigDecimal(monto), "PEN", "TARJETA_CREDITO",
                "VISA", "4321", FECHA_PAGO, "AUTH-99021");
    }
}
