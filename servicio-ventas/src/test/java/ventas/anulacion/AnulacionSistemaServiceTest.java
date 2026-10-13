package ventas.anulacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnulacionSistemaServiceTest {

    @Mock AnulacionRepository anulaciones;
    @InjectMocks AnulacionSistemaService servicio;

    @Test
    void guardaLaAnulacionEjecutadaYDevuelveSuCodigo() {
        when(anulaciones.saveAndFlush(any(Anulacion.class))).thenAnswer(inv -> {
            Anulacion a = inv.getArgument(0);
            a.setId(7L);
            return a;
        });

        String codigo = servicio.registrarEjecutada(981L, "STOCK_NO_DISPONIBLE");

        assertThat(codigo).isEqualTo("ANUL-0007");
        ArgumentCaptor<Anulacion> captor = ArgumentCaptor.forClass(Anulacion.class);
        verify(anulaciones).saveAndFlush(captor.capture());
        Anulacion a = captor.getValue();
        assertThat(a.getIdPedido()).isEqualTo(981L);
        assertThat(a.getMotivo()).isEqualTo("STOCK_NO_DISPONIBLE");
        assertThat(a.getEstado()).isEqualTo(EstadoAnulacion.EJECUTADA);
        assertThat(a.getFecha()).isNotNull();
        assertThat(a.getIdSolicitante()).isNull();   // la disparó un sistema
    }

    @Test
    void siElPedidoYaTeniaUnaSolicitudRechazadaReutilizaEsaFila() {
        Anulacion previa = new Anulacion();
        previa.setId(5L);
        previa.setIdPedido(981L);
        previa.setMotivo("CAMBIO_DE_OPINION");
        previa.setEstado(EstadoAnulacion.RECHAZADA);
        when(anulaciones.findByIdPedido(981L)).thenReturn(Optional.of(previa));
        when(anulaciones.saveAndFlush(previa)).thenReturn(previa);

        assertThat(servicio.registrarEjecutada(981L, "ENTREGA_FALLIDA_DEFINITIVA")).isEqualTo("ANUL-0005");
        assertThat(previa.getEstado()).isEqualTo(EstadoAnulacion.EJECUTADA);
        assertThat(previa.getMotivo()).isEqualTo("ENTREGA_FALLIDA_DEFINITIVA");
    }

    @Test
    void ejecutadaDeSoloDevuelveAnulacionesEjecutadas() {
        Anulacion a = new Anulacion();
        a.setId(12L);
        a.setEstado(EstadoAnulacion.PENDIENTE_AUTORIZACION);
        when(anulaciones.findByIdPedido(981L)).thenReturn(Optional.of(a));
        assertThat(servicio.ejecutadaDe(981L)).isEmpty();

        a.setEstado(EstadoAnulacion.EJECUTADA);
        assertThat(servicio.ejecutadaDe(981L)).contains("ANUL-0012");
    }
}
