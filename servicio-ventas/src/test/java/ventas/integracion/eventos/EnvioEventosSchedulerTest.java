package ventas.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.Envio;

@ExtendWith(MockitoExtension.class)
class EnvioEventosSchedulerTest {

    @Mock OutboxEventoRepository outbox;
    @Mock PostventaClient postventa;
    @InjectMocks EnvioEventosScheduler scheduler;

    @Test
    void marcaEnviadosLosAceptadosYSigueSiUnoEsRechazado() {
        OutboxEvento a = evento(), b = evento(), c = evento();
        when(outbox.findTop50ByEnviadoEnIsNullOrderByCreadoEnAsc()).thenReturn(List.of(a, b, c));
        when(postventa.enviarEvento(a.getIdEvento(), a.getPayload())).thenReturn(Envio.ACEPTADO);
        when(postventa.enviarEvento(b.getIdEvento(), b.getPayload())).thenReturn(Envio.RECHAZADO);
        when(postventa.enviarEvento(c.getIdEvento(), c.getPayload())).thenReturn(Envio.ACEPTADO);

        scheduler.enviarPendientes();

        assertThat(a.getEnviadoEn()).isNotNull();
        assertThat(b.getEnviadoEn()).isNull();
        assertThat(b.getIntentos()).isEqualTo(1);
        assertThat(c.getEnviadoEn()).isNotNull();
        verify(outbox).save(a);
        verify(outbox).save(b);
        verify(outbox).save(c);
    }

    @Test
    void siM2NoEstaDisponibleCortaLaVuelta() {
        OutboxEvento a = evento(), b = evento();
        when(outbox.findTop50ByEnviadoEnIsNullOrderByCreadoEnAsc()).thenReturn(List.of(a, b));
        when(postventa.enviarEvento(a.getIdEvento(), a.getPayload())).thenReturn(Envio.NO_DISPONIBLE);

        scheduler.enviarPendientes();

        assertThat(a.getEnviadoEn()).isNull();
        assertThat(a.getIntentos()).isEqualTo(1);
        verify(postventa, never()).enviarEvento(b.getIdEvento(), b.getPayload());
        verify(outbox, never()).save(b);
    }

    @Test
    void sinPendientesNoLlamaAM2() {
        when(outbox.findTop50ByEnviadoEnIsNullOrderByCreadoEnAsc()).thenReturn(List.of());
        scheduler.enviarPendientes();
        verify(postventa, never()).enviarEvento(any(), any());
    }

    private static OutboxEvento evento() {
        OutboxEvento e = new OutboxEvento();
        e.setIdEvento(UUID.randomUUID());
        e.setTipo(TipoEvento.PEDIDO_PAGADO);
        e.setPayload("{\"idEvento\":\"" + e.getIdEvento() + "\"}");
        e.setCreadoEn(OffsetDateTime.now());
        return e;
    }
}
