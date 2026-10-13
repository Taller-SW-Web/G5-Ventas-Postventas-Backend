package ventas.integracion.envios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.integracion.envios.MarketplaceClient.Envio;

@ExtendWith(MockitoExtension.class)
class EnvioNotificacionesSchedulerTest {

    @Mock NotificacionEnvioRepository notificaciones;
    @Mock MarketplaceClient marketplace;
    @InjectMocks EnvioNotificacionesScheduler scheduler;

    private static NotificacionEnvio aviso() {
        NotificacionEnvio n = new NotificacionEnvio();
        n.setIdEvento(UUID.randomUUID());
        n.setEstadoPublico("DESPACHADO");
        n.setPayload("{}");
        return n;
    }

    private void pendientes(NotificacionEnvio... n) {
        when(notificaciones.findTop50ByEnviadoEnIsNullAndIntentosLessThanOrderByCreadoEnAsc(EnvioNotificacionesScheduler.MAXIMO_INTENTOS))
                .thenReturn(List.of(n));
    }

    @Test
    void aceptadoSeMarcaRechazadoNoSeReintentaYSigue() {
        NotificacionEnvio a = aviso(), b = aviso(), c = aviso();
        pendientes(a, b, c);
        when(marketplace.notificar(a.getIdEvento(), "{}")).thenReturn(Envio.ACEPTADO);
        when(marketplace.notificar(b.getIdEvento(), "{}")).thenReturn(Envio.RECHAZADO);
        when(marketplace.notificar(c.getIdEvento(), "{}")).thenReturn(Envio.ACEPTADO);

        scheduler.enviarPendientes();

        assertThat(a.getEnviadoEn()).isNotNull();
        assertThat(b.getEnviadoEn()).isNull();
        assertThat(b.getIntentos()).isEqualTo(EnvioNotificacionesScheduler.MAXIMO_INTENTOS);
        assertThat(c.getEnviadoEn()).isNotNull();
    }

    @Test
    void marketplaceCaidoSumaIntentoYCortaLaVuelta() {
        NotificacionEnvio a = aviso(), b = aviso();
        pendientes(a, b);
        when(marketplace.notificar(a.getIdEvento(), "{}")).thenReturn(Envio.NO_DISPONIBLE);

        scheduler.enviarPendientes();

        assertThat(a.getIntentos()).isEqualTo(1);
        verify(marketplace, never()).notificar(b.getIdEvento(), "{}");
    }

    @Test
    void enModoSimuladoNoCuentaIntentos() {
        NotificacionEnvio a = aviso();
        pendientes(a);
        when(marketplace.notificar(a.getIdEvento(), "{}")).thenReturn(Envio.SIMULADO);

        scheduler.enviarPendientes();

        assertThat(a.getIntentos()).isZero();
        verify(notificaciones, never()).save(any());
    }
}
