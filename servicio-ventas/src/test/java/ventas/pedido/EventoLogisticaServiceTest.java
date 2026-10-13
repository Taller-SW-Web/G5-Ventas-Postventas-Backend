package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import ventas.anulacion.AnulacionSistemaService;
import ventas.anulacion.ReembolsoAnulacionService;
import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.PostventaClient.Envio;
import ventas.integracion.PostventaClient.RespuestaReembolso;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.EventoLogisticaRequest;
import ventas.pedido.dto.EventoLogisticaResponse;

@ExtendWith(MockitoExtension.class)
class EventoLogisticaServiceTest {

    private static final String CODIGO = "PED-2026-00981";
    private static final String CLAVE = "PED-2026-00981:GR-00892:ENTREGA_FALLIDA_DEFINITIVA";

    @Mock PedidoRepository pedidos;
    @Mock EventoRecibidoRepository recibidos;
    @Mock MaquinaEstados maquina;
    @Mock AnulacionSistemaService anulaciones;
    @Mock ReembolsoAnulacionService reembolsos;

    EventoLogisticaService servicio;
    Pedido pedido;

    @BeforeEach
    void armar() {
        servicio = new EventoLogisticaService(pedidos, recibidos, maquina, anulaciones, reembolsos,
                TransactionOperations.withoutTransaction());
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo(CODIGO);
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setEstado(EstadoPedido.DESPACHADO);
        pedido.setTotal(new BigDecimal("129.90"));
        pedido.setMoneda("PEN");
    }

    @Test
    void entregaFallidaAnulaYPideElReembolsoAM2() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(anulaciones.registrarEjecutada(981L, "ENTREGA_FALLIDA_DEFINITIVA")).thenReturn("ANUL-0012");
        when(reembolsos.enviar("ANUL-0012")).thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-90815", "EXITOSO", null));

        EventoLogisticaResponse r = servicio.procesar(CODIGO, evento(null));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, "ENTREGA_FALLIDA_DEFINITIVA");
        ArgumentCaptor<EventoRecibido> captor = ArgumentCaptor.forClass(EventoRecibido.class);
        verify(recibidos).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getIdEvento()).isEqualTo(CLAVE);
        assertThat(captor.getValue().getOrigen()).isEqualTo(OrigenEvento.SISTEMA_LOGISTICA);
        // La solicitud se guarda en la misma transacción que la anulación, antes de llamar a M2
        verify(reembolsos).registrar(eq("ANUL-0012"), eq(981L), eq(new BigDecimal("129.90")), eq("PEN"), contains("GR-00892"));

        assertThat(r.pedidoId()).isEqualTo(CODIGO);
        assertThat(r.anulacionId()).isEqualTo("ANUL-0012");
        assertThat(r.reembolso().solicitudId()).isEqualTo("REEM-90815");
        assertThat(r.reembolso().estado()).isEqualTo("EXITOSO");
        assertThat(r.reembolso().monto()).isEqualByComparingTo("129.90");
    }

    @Test
    void siM2NoRespondeElReembolsoQuedaEnProceso() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(anulaciones.registrarEjecutada(981L, "ENTREGA_FALLIDA_DEFINITIVA")).thenReturn("ANUL-0012");
        when(reembolsos.enviar("ANUL-0012")).thenReturn(new RespuestaReembolso(Envio.NO_DISPONIBLE, null, null, "M2 caído"));

        EventoLogisticaResponse r = servicio.procesar(CODIGO, evento(true));

        assertThat(r.reembolso().solicitudId()).isNull();
        assertThat(r.reembolso().estado()).isEqualTo("EN_PROCESO");
    }

    @Test
    void siM2RechazaElReembolsoLoInforma() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(anulaciones.registrarEjecutada(981L, "ENTREGA_FALLIDA_DEFINITIVA")).thenReturn("ANUL-0012");
        when(reembolsos.enviar("ANUL-0012")).thenReturn(new RespuestaReembolso(Envio.RECHAZADO, null, null, "SALDO_INSUFICIENTE"));

        assertThat(servicio.procesar(CODIGO, evento(true)).reembolso().estado()).isEqualTo("RECHAZADO");
    }

    @Test
    void sinReembolsoSoloAnula() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(anulaciones.registrarEjecutada(981L, "ENTREGA_FALLIDA_DEFINITIVA")).thenReturn("ANUL-0012");

        EventoLogisticaResponse r = servicio.procesar(CODIGO, evento(false));

        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, "ENTREGA_FALLIDA_DEFINITIVA");
        verify(reembolsos, never()).registrar(any(), any(), any(), any(), any());
        assertThat(r.reembolso()).isNull();
    }

    @Test
    void elMismoEventoDosVecesNoAnulaDeNuevoYReenviaElReembolsoConLaMismaAnulacion() {
        pedido.setEstado(EstadoPedido.ANULADO);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(recibidos.existsByIdEventoAndOrigen(CLAVE, OrigenEvento.SISTEMA_LOGISTICA)).thenReturn(true);
        when(anulaciones.ejecutadaDe(981L)).thenReturn(Optional.of("ANUL-0012"));
        when(reembolsos.enviar("ANUL-0012")).thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-90815", null, null));

        EventoLogisticaResponse r = servicio.procesar(CODIGO, evento(null));

        verifyNoInteractions(maquina);
        verify(anulaciones, never()).registrarEjecutada(any(), any());
        verify(recibidos, never()).saveAndFlush(any());
        verify(reembolsos, never()).registrar(any(), any(), any(), any(), any());
        verify(reembolsos).enviar("ANUL-0012");
        assertThat(r.estadoPedido()).isEqualTo(EstadoPedido.ANULADO);
        assertThat(r.anulacionId()).isEqualTo("ANUL-0012");
    }

    @Test
    void pedidoQueNoEstaDespachadoResponde409() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));

        assertThatThrownBy(() -> servicio.procesar(CODIGO, evento(null)))
                .isInstanceOf(ConflictoException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_NO_PERMITIDA");
        verifyNoInteractions(maquina, anulaciones, reembolsos);
    }

    @Test
    void otroEventoResponde400() {
        assertThatThrownBy(() -> servicio.procesar(CODIGO,
                new EventoLogisticaRequest("ENTREGA_REPROGRAMADA", "GR-00892", null, null)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasFieldOrPropertyWithValue("codigo", "EVENTO_NO_SOPORTADO");
        verifyNoInteractions(pedidos, maquina, reembolsos);
    }

    @Test
    void pedidoInexistenteResponde404() {
        when(pedidos.findById(981L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.procesar(CODIGO, evento(null)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private static EventoLogisticaRequest evento(Boolean requiereReembolso) {
        return new EventoLogisticaRequest("ENTREGA_FALLIDA_DEFINITIVA", "GR-00892",
                "Dirección inexistente tras 3 visitas; devuelto a almacén central", requiereReembolso);
    }
}
