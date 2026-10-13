package postventa.reembolso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import postventa.compartido.ConflictoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.integracion.PasarelaClient;
import postventa.integracion.PedidoResumen;
import postventa.integracion.VentasClient;
import postventa.reembolso.dto.ProcesarReembolsoRequest;
import postventa.reembolso.dto.ProcesarReembolsoResponse;

/** Criterios de aceptación de F4/ESP-09 y ESP-10 sin base de datos ni pasarela real. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReembolsoServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneOffset.UTC);

    @Mock ReembolsoRepository repositorio;
    @Mock VentasClient ventas;
    @Mock PasarelaClient pasarela;

    private ReembolsoService servicio;

    @BeforeEach
    void preparar() {
        servicio = new ReembolsoService(repositorio, ventas, pasarela, RELOJ);
        when(repositorio.saldoComprometido(981L)).thenReturn(BigDecimal.ZERO);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(pedido()));
        when(repositorio.save(any())).thenAnswer(invocacion -> {
            Reembolso reembolso = invocacion.getArgument(0);
            reembolso.setId(42L);
            return reembolso;
        });
        when(pasarela.solicitarExtorno(any(), any(), any(), any()))
                .thenReturn(new PasarelaClient.ResultadoExtorno(EstadoReembolso.EXITOSO,
                        "TX-001", "Extorno confirmado"));
    }

    @Test
    void procesaReembolsoYRegistraPrimerIntento() {
        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.reembolsoId()).isEqualTo("REEM-00042");
        assertThat(respuesta.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        assertThat(respuesta.transaccionPasarelaId()).isEqualTo("TX-001");
        assertThat(respuesta.fechaEjecucion()).isEqualTo(OffsetDateTime.ofInstant(RELOJ.instant(), ZoneOffset.UTC));
        ArgumentCaptor<Reembolso> guardado = ArgumentCaptor.forClass(Reembolso.class);
        verify(repositorio).save(guardado.capture());
        assertThat(guardado.getValue().getIdPedido()).isEqualTo(981L);
        assertThat(guardado.getValue().getIntentos()).singleElement().satisfies(intento -> {
            assertThat(intento.getNumeroIntento()).isEqualTo(1);
            assertThat(intento.getEstado()).isEqualTo(EstadoReembolso.EXITOSO);
        });
    }

    @Test
    void claveExitosaRepetidaDevuelveLaMismaOperacionSinLlamarPasarela() {
        Reembolso existente = reembolso(EstadoReembolso.EXITOSO);
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));

        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.reembolsoId()).isEqualTo("REEM-00042");
        assertThat(respuesta.transaccionPasarelaId()).isEqualTo("TX-001");
        verifyNoInteractions(ventas, pasarela);
        verify(repositorio, never()).save(any());
    }

    @Test
    void mismaClaveConOtroPedidoSeRechaza() {
        Reembolso existente = reembolso(EstadoReembolso.EXITOSO);
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));
        ProcesarReembolsoRequest distintaReferencia = new ProcesarReembolsoRequest(
                OrigenReembolso.ANULACION, "ANUL-00012", "PED-2026-00982",
                new BigDecimal("129.90"), "PEN", "Duplicado", "SISTEMA_M1");

        assertThatThrownBy(() -> servicio.procesar("clave-001", distintaReferencia))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("CLAVE_IDEMPOTENCIA_EN_CONFLICTO");
        verifyNoInteractions(ventas, pasarela);
    }

    @Test
    void rechazaMontoQueExcedeElSaldoDelPedido() {
        when(repositorio.saldoComprometido(981L)).thenReturn(new BigDecimal("100.00"));

        assertThatThrownBy(() -> servicio.procesar("clave-001", solicitud()))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("SALDO_INSUFICIENTE");
        verify(repositorio, never()).save(any());
        verifyNoInteractions(pasarela);
    }

    @Test
    void reintentaUnaOperacionPendienteConLaMismaClaveYConservaElHistorial() {
        Reembolso existente = reembolso(EstadoReembolso.PENDIENTE);
        existente.agregarIntento(EstadoReembolso.PENDIENTE, null, "Tiempo de espera agotado");
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));

        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        assertThat(existente.getIntentos()).hasSize(2);
        assertThat(existente.getIntentos().get(1).getNumeroIntento()).isEqualTo(2);
        verify(pasarela).solicitarExtorno("clave-001", "REEM-00042", new BigDecimal("129.90"), "PEN");
        verifyNoInteractions(ventas);
    }

    @Test
    void exigeLaClaveDeIdempotencia() {
        assertThatThrownBy(() -> servicio.procesar(" ", solicitud()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CLAVE_IDEMPOTENCIA_REQUERIDA");
        verifyNoInteractions(repositorio, ventas, pasarela);
    }

    private static ProcesarReembolsoRequest solicitud() {
        return new ProcesarReembolsoRequest(OrigenReembolso.ANULACION, "ANUL-00012", "PED-2026-00981",
                new BigDecimal("129.90"), "PEN", "Anulación confirmada", "SISTEMA_M1");
    }

    private static PedidoResumen pedido() {
        return new PedidoResumen("PED-2026-00981", PedidoResumen.ENTREGADO, null,
                new BigDecimal("129.90"), "PEN", java.util.List.of(), null);
    }

    private static Reembolso reembolso(EstadoReembolso estado) {
        Reembolso reembolso = new Reembolso();
        reembolso.setId(42L);
        reembolso.setIdPedido(981L);
        reembolso.setIdOrigen(12L);
        reembolso.setTipoOrigen(OrigenReembolso.ANULACION);
        reembolso.setIdempotencyKey("clave-001");
        reembolso.setMonto(new BigDecimal("129.90"));
        reembolso.setMoneda("PEN");
        reembolso.setEstado(estado);
        reembolso.setIdTransaccion("TX-001");
        return reembolso;
    }
}
