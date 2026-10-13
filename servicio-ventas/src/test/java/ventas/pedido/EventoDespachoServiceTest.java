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
import java.time.OffsetDateTime;
import java.util.Map;
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
import ventas.integracion.envios.NotificadorEnvios;
import ventas.integracion.eventos.EventoRecibido;
import ventas.integracion.eventos.EventoRecibidoRepository;
import ventas.integracion.eventos.OrigenEvento;
import ventas.pedido.dto.EventoDespachoRequest;
import ventas.pedido.dto.EventoDespachoResponse;

@ExtendWith(MockitoExtension.class)
class EventoDespachoServiceTest {

    private static final String CODIGO = "PED-2026-00981";
    private static final OffsetDateTime CUANDO = OffsetDateTime.parse("2026-10-12T18:30:00Z");

    @Mock PedidoRepository pedidos;
    @Mock EventoRecibidoRepository recibidos;
    @Mock MaquinaEstados maquina;
    @Mock AnulacionSistemaService anulaciones;
    @Mock ReembolsoAnulacionService reembolsos;
    @Mock NotificadorEnvios envios;

    EventoDespachoService servicio;
    Pedido pedido;

    @BeforeEach
    void armar() {
        servicio = new EventoDespachoService(pedidos, recibidos, maquina, anulaciones, reembolsos, envios,
                TransactionOperations.withoutTransaction());
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo(CODIGO);
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setTotal(new BigDecimal("129.90"));
        pedido.setMoneda("PEN");
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
    }

    @Test
    void enCaminoPasaDeEnPreparacionADespachadoConLaFechaDelEvento() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);

        EventoDespachoResponse r = servicio.procesar(evento(TipoEventoDespacho.DESPACHO_EN_CAMINO, null));

        verify(maquina).cambiar(pedido, EstadoPedido.DESPACHADO, ActorHistorial.SISTEMA_LOGISTICA, null, CUANDO);
        verify(envios).registrar(pedido, "DESP-00412");   // 1.10
        ArgumentCaptor<EventoRecibido> captor = ArgumentCaptor.forClass(EventoRecibido.class);
        verify(recibidos).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getIdEvento()).isEqualTo("G2:evt-1");
        assertThat(captor.getValue().getOrigen()).isEqualTo(OrigenEvento.SISTEMA_LOGISTICA);
        assertThat(r.repetido()).isFalse();
    }

    @Test
    void entregadoPasaDeDespachadoAEntregado() {
        pedido.setEstado(EstadoPedido.DESPACHADO);
        servicio.procesar(evento(TipoEventoDespacho.DESPACHO_ENTREGADO, null));
        verify(maquina).cambiar(pedido, EstadoPedido.ENTREGADO, ActorHistorial.SISTEMA_LOGISTICA, null, CUANDO);
        verify(envios).registrar(pedido, "DESP-00412");
    }

    @Test
    void siYaEstabaEnElDestinoNoHaceNada() {
        pedido.setEstado(EstadoPedido.DESPACHADO);
        assertThat(servicio.procesar(evento(TipoEventoDespacho.DESPACHO_EN_CAMINO, null)).mensaje()).contains("ya estaba");
        verifyNoInteractions(maquina, envios);
    }

    @Test
    void eventoFueraDeOrdenResponde409() {
        pedido.setEstado(EstadoPedido.PAGADO);
        assertThatThrownBy(() -> servicio.procesar(evento(TipoEventoDespacho.DESPACHO_ENTREGADO, null)))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "TRANSICION_NO_PERMITIDA");
        verifyNoInteractions(maquina);
    }

    @Test
    void devueltoAOrigenAnulaYPideElReembolsoDespuesDelCommit() {
        pedido.setEstado(EstadoPedido.DESPACHADO);
        when(anulaciones.registrarEjecutada(981L, "ENTREGA_FALLIDA_DEFINITIVA")).thenReturn("ANUL-0012");

        servicio.procesar(evento(TipoEventoDespacho.DESPACHO_DEVUELTO_A_ORIGEN, Map.of("motivoCierre", "MAXIMO_INTENTOS_ALCANZADO")));

        verify(reembolsos).registrar(eq("ANUL-0012"), eq(981L), eq(new BigDecimal("129.90")), eq("PEN"),
                contains("MAXIMO_INTENTOS_ALCANZADO"));
        verify(maquina).cambiar(pedido, EstadoPedido.ANULADO, ActorHistorial.SISTEMA_LOGISTICA, "ENTREGA_FALLIDA_DEFINITIVA", CUANDO);
        verify(reembolsos).enviar("ANUL-0012");
    }

    @Test
    void devueltoAOrigenDeUnPedidoYaAnuladoNoReabreNada() {
        pedido.setEstado(EstadoPedido.ANULADO);
        servicio.procesar(evento(TipoEventoDespacho.DESPACHO_DEVUELTO_A_ORIGEN, null));
        verifyNoInteractions(maquina, anulaciones, reembolsos);
    }

    @Test
    void reprogramadoGuardaLaNuevaFecha() {
        pedido.setEstado(EstadoPedido.DESPACHADO);
        servicio.procesar(evento(TipoEventoDespacho.DESPACHO_REPROGRAMADO, Map.of("nuevaFechaProgramada", "2026-10-15")));
        assertThat(pedido.getFechaEstimadaEntrega()).isEqualTo(OffsetDateTime.parse("2026-10-15T00:00:00Z"));
        verify(pedidos).save(pedido);
        verifyNoInteractions(maquina);

        servicio.procesar(evento(TipoEventoDespacho.DESPACHO_REPROGRAMADO, Map.of("nuevaFechaProgramada", "2026-10-16T14:00:00-05:00")));
        assertThat(pedido.getFechaEstimadaEntrega()).isEqualTo(OffsetDateTime.parse("2026-10-16T14:00:00-05:00"));
    }

    @Test
    void reprogramadoSinFechaResponde400() {
        pedido.setEstado(EstadoPedido.DESPACHADO);
        assertThatThrownBy(() -> servicio.procesar(evento(TipoEventoDespacho.DESPACHO_REPROGRAMADO, Map.of())))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.procesar(evento(TipoEventoDespacho.DESPACHO_REPROGRAMADO, Map.of("nuevaFechaProgramada", "mañana"))))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void asignadoFallidoYCanceladoSoloSeRegistran() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        for (TipoEventoDespacho t : new TipoEventoDespacho[] {TipoEventoDespacho.DESPACHO_ASIGNADO,
                TipoEventoDespacho.DESPACHO_FALLIDO, TipoEventoDespacho.DESPACHO_CANCELADO}) {
            assertThat(servicio.procesar(evento(t, null)).estadoPedido()).isEqualTo(EstadoPedido.EN_PREPARACION);
        }
        verifyNoInteractions(maquina, anulaciones, reembolsos);
    }

    @Test
    void elMismoIdEventoDosVecesNoHaceNada() {
        pedido.setEstado(EstadoPedido.DESPACHADO);
        when(recibidos.existsByIdEventoAndOrigen("G2:evt-1", OrigenEvento.SISTEMA_LOGISTICA)).thenReturn(true);

        EventoDespachoResponse r = servicio.procesar(evento(TipoEventoDespacho.DESPACHO_EN_CAMINO, null));

        assertThat(r.repetido()).isTrue();
        verify(recibidos, never()).saveAndFlush(any());
        verifyNoInteractions(maquina);
    }

    @Test
    void pedidoInexistenteResponde404() {
        when(pedidos.findById(981L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.procesar(evento(TipoEventoDespacho.DESPACHO_EN_CAMINO, null)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private static EventoDespachoRequest evento(TipoEventoDespacho tipo, Map<String, Object> datos) {
        return new EventoDespachoRequest("evt-1", CODIGO, "DESP-00412", tipo, null, null, CUANDO, datos);
    }
}
