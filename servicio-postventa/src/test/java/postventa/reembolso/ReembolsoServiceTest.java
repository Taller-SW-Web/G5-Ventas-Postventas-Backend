package postventa.reembolso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import postventa.compartido.ConflictoException;
import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.ProyeccionesService;
import postventa.devolucion.DevolucionRepository;
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
    @Mock DevolucionRepository devoluciones;
    @Mock ProyeccionesService proyecciones;

    private ReembolsoService servicio;

    @BeforeEach
    void preparar() {
        servicio = new ReembolsoService(repositorio, ventas, pasarela, devoluciones, proyecciones, RELOJ);
        when(repositorio.bloquearReembolsosDelPedido(981L)).thenReturn(java.util.List.of());
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
        verify(repositorio, atLeastOnce()).save(guardado.capture());
        assertThat(guardado.getValue().getIdPedido()).isEqualTo(981L);
        assertThat(guardado.getValue().getIntentos()).singleElement().satisfies(intento -> {
            assertThat(intento.getNumeroIntento()).isEqualTo(1);
            assertThat(intento.getEstado()).isEqualTo(EstadoReembolso.EXITOSO);
        });
        verify(proyecciones).reembolsoExitoso(
                OffsetDateTime.ofInstant(RELOJ.instant(), ZoneOffset.UTC),
                CanalVenta.MARKETPLACE, new BigDecimal("129.90"), "PEN");
    }

    @Test
    void noActualizaElDashboardCuandoElReembolsoQuedaPendienteOFallido() {
        when(pasarela.solicitarExtorno(any(), any(), any(), any()))
                .thenReturn(new PasarelaClient.ResultadoExtorno(EstadoReembolso.PENDIENTE, null, "Timeout"),
                        new PasarelaClient.ResultadoExtorno(EstadoReembolso.FALLIDO, null, "Rechazado"));

        servicio.procesar("clave-pendiente", solicitud());
        servicio.procesar("clave-fallido", solicitud());

        verifyNoInteractions(proyecciones);
    }

    @Test
    void conservaElGestorAutorizadorParaAuditoria() {
        UUID gestor = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ProcesarReembolsoRequest solicitud = new ProcesarReembolsoRequest(OrigenReembolso.ANULACION,
                "ANUL-00012", "PED-2026-00981", new BigDecimal("129.90"), "PEN",
                "Anulación confirmada", gestor.toString());

        servicio.procesar("clave-auditoria", solicitud);

        ArgumentCaptor<Reembolso> guardado = ArgumentCaptor.forClass(Reembolso.class);
        verify(repositorio, atLeastOnce()).save(guardado.capture());
        assertThat(guardado.getValue().getIdAutorizador()).isEqualTo(gestor);
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
    void claveExitosaRepetidaConservaLaFechaDelIntentoRegistrado() {
        Reembolso existente = reembolso(EstadoReembolso.EXITOSO);
        OffsetDateTime ejecucionOriginal = OffsetDateTime.parse("2026-10-12T09:30:00Z");
        existente.agregarIntento(EstadoReembolso.EXITOSO, "TX-001", "Extorno confirmado", ejecucionOriginal);
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));

        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.fechaEjecucion()).isEqualTo(ejecucionOriginal);
        verifyNoInteractions(ventas, pasarela);
    }

    @Test
    void consultaUnReembolsoPorOrigenParaF3() {
        Reembolso existente = reembolso(EstadoReembolso.EXITOSO);
        existente.agregarIntento(EstadoReembolso.EXITOSO, "TX-001", "Extorno confirmado",
                OffsetDateTime.parse("2026-10-12T09:30:00Z"));
        when(repositorio.findByTipoOrigenAndIdOrigen(OrigenReembolso.DEVOLUCION, 42L))
                .thenReturn(Optional.of(existente));

        Optional<ProcesarReembolsoResponse> respuesta = servicio.consultarPorOrigen(OrigenReembolso.DEVOLUCION, 42L);

        assertThat(respuesta).isPresent();
        assertThat(respuesta.orElseThrow().reembolsoId()).isEqualTo("REEM-00042");
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
        Reembolso comprometido = reembolso(EstadoReembolso.EXITOSO);
        comprometido.setMonto(new BigDecimal("100.00"));
        when(repositorio.bloquearReembolsosDelPedido(981L)).thenReturn(java.util.List.of(comprometido));

        assertThatThrownBy(() -> servicio.procesar("clave-001", solicitud()))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("SALDO_INSUFICIENTE");
        verify(repositorio, never()).save(any());
        verifyNoInteractions(pasarela);
    }

    @Test
    void rechazaElReembolsoSiElPagoOriginalNoEstaConfirmado() {
        PedidoResumen sinPagoConfirmado = new PedidoResumen("PED-2026-00981", PedidoResumen.ENTREGADO, null,
                new BigDecimal("129.90"), "PEN", java.util.List.of(), null, false);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(sinPagoConfirmado));

        assertThatThrownBy(() -> servicio.procesar("clave-001", solicitud()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("PAGO_INVALIDO");
        verify(repositorio, never()).save(any());
        verifyNoInteractions(pasarela);
    }

    @Test
    void aceptaLaAnulacionDeM1AunqueElEstadoActualDelPedidoNoSeaAnulado() {
        PedidoResumen pedidoNoAnulado = new PedidoResumen("PED-2026-00981", PedidoResumen.ENTREGADO, null,
                new BigDecimal("129.90"), "PEN", java.util.List.of(), null, true);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(pedidoNoAnulado));

        ProcesarReembolsoResponse respuesta = servicio.procesar("ANULACION-ANUL-00012", solicitud());

        assertThat(respuesta.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        verify(pasarela).solicitarExtorno("ANULACION-ANUL-00012", "REEM-00042",
                new BigDecimal("129.90"), "PEN");
    }

    @Test
    void rechazaUnaDevolucionQueNoExisteOQueNoFueAprobadaParaReembolso() {
        ProcesarReembolsoRequest origenInexistente = new ProcesarReembolsoRequest(
                OrigenReembolso.DEVOLUCION, "DEV-2026-00044", "PED-2026-00981",
                new BigDecimal("10.00"), "PEN", "Origen falso", "GESTOR");
        when(devoluciones.findById(44L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.procesar("clave-dev-001", origenInexistente))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ORIGEN_NO_ELEGIBLE");
        verify(repositorio, never()).save(any());
        verify(ventas).consultarPedido("PED-2026-00981");
        verifyNoInteractions(pasarela);
    }

    @Test
    void ca04_rechazaMontoConMasDeDosDecimales() {
        ProcesarReembolsoRequest conTresDecimales = new ProcesarReembolsoRequest(
                OrigenReembolso.ANULACION, "ANUL-00012", "PED-2026-00981",
                new BigDecimal("10.005"), "PEN", "Monto inválido", "SISTEMA_M1");

        assertThatThrownBy(() -> servicio.procesar("clave-001", conTresDecimales))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("MONTO_INVALIDO");
        verifyNoInteractions(repositorio, ventas, pasarela);
    }

    @Test
    void rechazaUnCodigoDeOrigenCuyoPrefijoNoCorrespondeAlTipo() {
        ProcesarReembolsoRequest origenConPrefijoIncorrecto = new ProcesarReembolsoRequest(
                OrigenReembolso.ANULACION, "DEV-2026-00012", "PED-2026-00981",
                new BigDecimal("10.00"), "PEN", "Origen inválido", "SISTEMA_M1");

        assertThatThrownBy(() -> servicio.procesar("clave-001", origenConPrefijoIncorrecto))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ORIGEN_NO_ELEGIBLE");
        verifyNoInteractions(repositorio, ventas, pasarela);
    }

    @Test
    void elHistorialDeIntentosNoPuedeEditarNiEliminarRegistros() {
        Reembolso reembolso = reembolso(EstadoReembolso.PENDIENTE);
        reembolso.agregarIntento(EstadoReembolso.PENDIENTE, null, "En espera");

        assertThatThrownBy(() -> reembolso.getIntentos().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reservaElSaldoDelPedidoAntesDeCalcularElAcumulado() {
        servicio.procesar("clave-001", solicitud());

        InOrder orden = org.mockito.Mockito.inOrder(repositorio);
        orden.verify(repositorio).bloquearSaldoPedido(981L);
        orden.verify(repositorio).bloquearReembolsosDelPedido(981L);
    }

    @Test
    void reintentaUnaOperacionFallidaConLaMismaClaveYConservaElHistorial() {
        Reembolso existente = reembolso(EstadoReembolso.FALLIDO);
        existente.agregarIntento(EstadoReembolso.FALLIDO, null, "Tiempo de espera agotado");
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));

        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        assertThat(existente.getIntentos()).hasSize(2);
        assertThat(existente.getIntentos().get(1).getNumeroIntento()).isEqualTo(2);
        verify(pasarela).solicitarExtorno("clave-001-2", "REEM-00042", new BigDecimal("129.90"), "PEN");
        verify(ventas).consultarPedido("PED-2026-00981");
    }

    @Test
    void repeticionMientrasLaOperacionEstaPendienteNoLlamaDosVecesALaPasarela() {
        Reembolso existente = reembolso(EstadoReembolso.PENDIENTE);
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));

        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.estado()).isEqualTo(EstadoReembolso.PENDIENTE);
        verifyNoInteractions(ventas, pasarela);
        verify(repositorio, never()).save(any());
    }

    @Test
    void recuperaConLaMismaClaveElResultadoDeUnTimeout() {
        Reembolso existente = reembolso(EstadoReembolso.PENDIENTE);
        existente.agregarIntento(EstadoReembolso.PENDIENTE, null, "Resultado incierto por timeout");
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));

        ProcesarReembolsoResponse respuesta = servicio.procesar("clave-001", solicitud());

        assertThat(respuesta.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        verify(pasarela).solicitarExtorno("clave-001", "REEM-00042", new BigDecimal("129.90"), "PEN");
        assertThat(existente.getIntentos()).hasSize(2);
    }

    @Test
    void conservaLaClaveDelSegundoIntentoDuranteTimeoutsConsecutivos() {
        Reembolso existente = reembolso(EstadoReembolso.FALLIDO);
        existente.agregarIntento(EstadoReembolso.FALLIDO, null, "La pasarela rechazó el extorno");
        when(repositorio.findByIdempotencyKey("clave-001"))
                .thenReturn(Optional.of(existente));
        when(pasarela.solicitarExtorno(any(), any(), any(), any()))
                .thenReturn(new PasarelaClient.ResultadoExtorno(EstadoReembolso.PENDIENTE, null,
                                "Resultado incierto por timeout"),
                        new PasarelaClient.ResultadoExtorno(EstadoReembolso.PENDIENTE, null,
                                "Resultado incierto por timeout"),
                        new PasarelaClient.ResultadoExtorno(EstadoReembolso.EXITOSO, "TX-002",
                                "Extorno confirmado"));

        ProcesarReembolsoResponse segundoIntento = servicio.procesar("clave-001", solicitud());
        ProcesarReembolsoResponse segundoTimeout = servicio.procesar("clave-001", solicitud());
        ProcesarReembolsoResponse recuperado = servicio.procesar("clave-001", solicitud());

        assertThat(segundoIntento.estado()).isEqualTo(EstadoReembolso.PENDIENTE);
        assertThat(segundoTimeout.estado()).isEqualTo(EstadoReembolso.PENDIENTE);
        assertThat(recuperado.estado()).isEqualTo(EstadoReembolso.EXITOSO);
        verify(pasarela, times(3)).solicitarExtorno("clave-001-2", "REEM-00042",
                new BigDecimal("129.90"), "PEN");
    }

    @Test
    void concurrenciaNoComprometeMasDineroQueElPagoDelPedido() throws Exception {
        var porClave = new ConcurrentHashMap<String, Reembolso>();
        var secuencia = new AtomicLong(40);
        var pasarelaIniciada = new CountDownLatch(1);
        var continuarPasarela = new CountDownLatch(1);
        when(repositorio.findByIdempotencyKey(any())).thenAnswer(i -> Optional.ofNullable(porClave.get(i.getArgument(0))));
        when(repositorio.bloquearReembolsosDelPedido(981L)).thenAnswer(i -> porClave.values().stream()
                .filter(r -> r.getIdPedido().equals(981L)).toList());
        org.mockito.Mockito.doAnswer(i -> {
            Reembolso r = i.getArgument(0);
            if (r.getId() == null) r.setId(secuencia.incrementAndGet());
            porClave.put(r.getIdempotencyKey(), r);
            return r;
        }).when(repositorio).save(any());
        when(pasarela.solicitarExtorno(any(), any(), any(), any())).thenAnswer(i -> {
            pasarelaIniciada.countDown();
            assertThat(continuarPasarela.await(2, TimeUnit.SECONDS)).isTrue();
            return new PasarelaClient.ResultadoExtorno(EstadoReembolso.EXITOSO, "TX-001", "Extorno confirmado");
        });
        TransactionOperations unaTransaccionALaVez = new TransactionOperations() {
            @Override public synchronized <T> T execute(TransactionCallback<T> accion) { return accion.doInTransaction(null); }
        };
        ReembolsoService concurrente = new ReembolsoService(repositorio, ventas, pasarela, devoluciones, proyecciones, RELOJ,
                unaTransaccionALaVez);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<ProcesarReembolsoResponse> primero = hilos.submit(() -> concurrente.procesar("clave-001", solicitud()));
            assertThat(pasarelaIniciada.await(2, TimeUnit.SECONDS)).isTrue();
            ProcesarReembolsoRequest segundoOrigen = new ProcesarReembolsoRequest(OrigenReembolso.ANULACION,
                    "ANUL-00013", "PED-2026-00981", new BigDecimal("129.90"), "PEN", "Segundo", "SISTEMA_M1");
            Future<ProcesarReembolsoResponse> segundo = hilos.submit(() -> concurrente.procesar("clave-002", segundoOrigen));

            assertThatThrownBy(() -> segundo.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ConflictoException.class);
            continuarPasarela.countDown();
            assertThat(primero.get(2, TimeUnit.SECONDS).estado()).isEqualTo(EstadoReembolso.EXITOSO);
            verify(pasarela).solicitarExtorno("clave-001", "REEM-00041", new BigDecimal("129.90"), "PEN");
        } finally {
            continuarPasarela.countDown();
            hilos.shutdownNow();
        }
    }

    @Test
    void noReintentaSiElSaldoComprometidoAhoraSuperaElPago() {
        Reembolso existente = reembolso(EstadoReembolso.FALLIDO);
        when(repositorio.findByIdempotencyKey("clave-001")).thenReturn(Optional.of(existente));
        Reembolso comprometido = reembolso(EstadoReembolso.EXITOSO);
        comprometido.setMonto(new BigDecimal("130.00"));
        when(repositorio.bloquearReembolsosDelPedido(981L)).thenReturn(java.util.List.of(comprometido));

        assertThatThrownBy(() -> servicio.procesar("clave-001", solicitud()))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("SALDO_INSUFICIENTE");
        verifyNoInteractions(pasarela);
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
        return new PedidoResumen("PED-2026-00981", "ANULADO", null,
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
