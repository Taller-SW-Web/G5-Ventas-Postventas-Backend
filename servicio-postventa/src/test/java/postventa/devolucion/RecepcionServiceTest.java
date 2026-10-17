package postventa.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import postventa.compartido.ConflictoException;
import postventa.compartido.DependenciaNoDisponibleException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.devolucion.dto.ConfirmarRecepcionRequest;
import postventa.devolucion.dto.ConfirmarRecepcionResponse;
import postventa.integracion.PedidoResumen;
import postventa.integracion.ProductosClient;
import postventa.integracion.ProductosClient.LineaReintegro;
import postventa.integracion.VentasClient;

/** 2.5b: ESP-08 8.2b, reglas 14 a 16 y CA-07/CA-08, sin base de datos ni otros sistemas. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecepcionServiceTest {

    static final Instant AHORA = Instant.parse("2026-10-16T15:00:00Z");
    static final UUID GESTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock DevolucionService devoluciones;
    @Mock RecepcionDevolucionRepository recepciones;
    @Mock HistorialDevolucionRepository historial;
    @Mock VentasClient ventas;
    @Mock ProductosClient productos;
    @Mock SolicitadorReembolso reembolsos;

    RecepcionService servicio;
    Devolucion devolucion;
    /** Lo que quedaría guardado en postventa.devolucion_recepcion. */
    final List<RecepcionDevolucion> guardadas = new ArrayList<>();

    @BeforeEach
    void preparar() {
        servicio = new RecepcionService(devoluciones, recepciones, historial, ventas, productos, reembolsos,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(AHORA, ZoneOffset.UTC));
        devolucion = aprobada(TipoResolucion.REEMBOLSO);
        when(devoluciones.buscar("DEV-2026-0042", true)).thenReturn(devolucion);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(new PedidoResumen("PED-2026-00981",
                "ENTREGADO", null, new BigDecimal("180.00"), "PEN",
                List.of(new PedidoResumen.Item("PROD-101", 3, new BigDecimal("50.00"), "SKU-101"),
                        new PedidoResumen.Item("PROD-999", 1, new BigDecimal("30.00"), "SKU-999")),
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).minusDays(2))));
        when(recepciones.findByDevolucionIdOrderByIdAsc(42L)).thenAnswer(inv -> new ArrayList<>(guardadas));
        when(recepciones.saveAll(any())).thenAnswer(inv -> {
            Iterable<RecepcionDevolucion> filas = inv.getArgument(0);
            filas.forEach(r -> {
                if (!guardadas.contains(r)) {
                    guardadas.add(r);
                }
            });
            return List.copyOf(guardadas);
        });
    }

    @Test
    void esp08_ca08_soloSeReintegranLasUnidadesAptas() {
        ConfirmarRecepcionResponse r = servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 3, 1), Solicitante.gestor(GESTOR));

        verify(productos).reintegrar(any(UUID.class), eq("PED-2026-00981"), eq("DEV-2026-0042"),
                eq(List.of(new LineaReintegro("SKU-101", 1))));
        assertThat(r.lineas()).containsExactly(new ConfirmarRecepcionResponse.Linea("SKU-101", 1));
        assertThat(r.reintegroSolicitado()).isTrue();
        assertThat(r.actor()).isEqualTo(GESTOR.toString());
        assertThat(guardadas).singleElement().satisfies(f -> {
            assertThat(f.getIdProducto()).isEqualTo("PROD-101");
            assertThat(f.getCantidadRecibida()).isEqualTo(3);
            assertThat(f.getCantidadApta()).isEqualTo(1);
            assertThat(f.getReintegroEstado()).isEqualTo(EstadoReintegro.COMPLETADO);
        });
        ArgumentCaptor<HistorialDevolucion> h = ArgumentCaptor.forClass(HistorialDevolucion.class);
        verify(historial).save(h.capture());
        assertThat(h.getValue().getEstadoNuevo()).isEqualTo(EstadoDevolucion.APROBADA);
        assertThat(h.getValue().getDetalle()).contains("SKU-101 recibidas 3, aptas 1");
    }

    @Test
    void masAptasQueRecibidasResponde400() {
        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 2), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CANTIDAD_INVALIDA");
        verifyNoInteractions(productos);
        verify(recepciones, never()).saveAll(any());
    }

    @Test
    void unSkuQueNoEsDelPedidoResponde400() {
        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-OTRO", 1, 1), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CANTIDAD_INVALIDA");
    }

    @Test
    void unSkuDelPedidoQueNoSeDevolvioResponde400() {
        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-999", 1, 1), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CANTIDAD_INVALIDA");
    }

    @Test
    void sinAprobarResponde409() {
        devolucion.setEstado(EstadoDevolucion.EN_EVALUACION);

        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 1), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("RESOLUCION_NO_APROBADA");
    }

    @Test
    void esp08_regla16_unReembolsoSinDevolucionFisicaNoAceptaRecepcion() {
        devolucion.setRequiereDevolucionFisica(false);

        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 1), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("RESOLUCION_NO_APROBADA");
        verifyNoInteractions(productos);
    }

    @Test
    void esp08_regla15_unaRecepcionParcialPosteriorEsOtraOperacion() {
        servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 1), Solicitante.gestor(GESTOR));
        servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 2, 2), Solicitante.gestor(GESTOR));

        ArgumentCaptor<UUID> operaciones = ArgumentCaptor.forClass(UUID.class);
        verify(productos, times(2)).reintegrar(operaciones.capture(), any(), any(), any());
        assertThat(operaciones.getAllValues().get(0)).isNotEqualTo(operaciones.getAllValues().get(1));
        assertThat(guardadas).hasSize(2);
    }

    @Test
    void noSePuedenRecibirMasUnidadesDeLasDevueltas() {
        servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 2, 2), Solicitante.gestor(GESTOR));

        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 2, 2), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CANTIDAD_INVALIDA");
    }

    @Test
    void siYaLlegaronTodasLasUnidadesResponde409YNoDuplicaElReintegro() {
        servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 3, 3), Solicitante.gestor(GESTOR));

        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 1), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("RECEPCION_YA_CONFIRMADA");
        verify(productos, times(1)).reintegrar(any(), any(), any(), any());
    }

    @Test
    void siProductosNoRespondeQuedaPendienteYElReintentoUsaElMismoOperationId() {
        doThrow(new DependenciaNoDisponibleException("PRODUCTOS_NO_DISPONIBLE", "caído"))
                .doNothing()
                .when(productos).reintegrar(any(), any(), any(), any());

        assertThatThrownBy(() -> servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 1), Solicitante.gestor(GESTOR)))
                .isInstanceOf(DependenciaNoDisponibleException.class);
        assertThat(guardadas).singleElement().extracting(RecepcionDevolucion::getReintegroEstado)
                .isEqualTo(EstadoReintegro.PENDIENTE);

        ConfirmarRecepcionResponse r = servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 1), Solicitante.gestor(GESTOR));

        ArgumentCaptor<UUID> operaciones = ArgumentCaptor.forClass(UUID.class);
        verify(productos, times(2)).reintegrar(operaciones.capture(), any(), any(), any());
        assertThat(operaciones.getAllValues().get(0)).isEqualTo(operaciones.getAllValues().get(1));
        assertThat(guardadas).singleElement().extracting(RecepcionDevolucion::getReintegroEstado)
                .isEqualTo(EstadoReintegro.COMPLETADO);
        assertThat(r.recepcionConfirmada()).isTrue();
    }

    @Test
    void sinUnidadesAptasNoSeLlamaAProductos() {
        ConfirmarRecepcionResponse r = servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 1, 0), Solicitante.gestor(GESTOR));

        verifyNoInteractions(productos);
        assertThat(r.reintegroSolicitado()).isFalse();
        assertThat(guardadas).singleElement().extracting(RecepcionDevolucion::getReintegroEstado)
                .isEqualTo(EstadoReintegro.COMPLETADO);
    }

    @Test
    void conElReembolsoYaConfirmadoLaRecepcionCompletaLaDevolucion() {
        when(reembolsos.consultar("DEV-2026-0042")).thenReturn(Optional.of(new SolicitadorReembolso.Resultado(
                "REEM-00001", "EXITOSO", new BigDecimal("150.00"), "PEN", "TX-1", OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC))));
        when(devoluciones.completarSiCorresponde(devolucion, TipoResolucion.REEMBOLSO, true, null)).thenReturn(true);

        ConfirmarRecepcionResponse r = servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 3, 3), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
    }

    @Test
    void conElReembolsoPendienteSigueAprobada() {
        when(reembolsos.consultar("DEV-2026-0042")).thenReturn(Optional.empty());

        ConfirmarRecepcionResponse r = servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 3, 3), Solicitante.gestor(GESTOR));

        verify(devoluciones).completarSiCorresponde(devolucion, TipoResolucion.REEMBOLSO, false, null);
        assertThat(r.estado()).isEqualTo(EstadoDevolucion.APROBADA);
    }

    @Test
    void enUnCambioCuentaComoConfirmadoSiM1YaEntregoElReemplazo() {
        devolucion = aprobada(TipoResolucion.CAMBIO);
        devolucion.setReferenciaOperacion("PED-2026-01002");
        when(devoluciones.buscar("DEV-2026-0042", true)).thenReturn(devolucion);
        when(devoluciones.cambioEntregado(devolucion)).thenReturn(true);

        servicio.confirmar("DEV-2026-0042", recepcion("SKU-101", 3, 3), Solicitante.gestor(GESTOR));

        verify(devoluciones).completarSiCorresponde(eq(devolucion), eq(TipoResolucion.CAMBIO), eq(true), eq("PED-2026-01002"));
        verifyNoInteractions(reembolsos);
    }

    @Test
    void lineasRepetidasDelMismoSkuSeSuman() {
        servicio.confirmar("DEV-2026-0042", new ConfirmarRecepcionRequest(List.of(
                new ConfirmarRecepcionRequest.Linea("SKU-101", 1, 1),
                new ConfirmarRecepcionRequest.Linea("SKU-101", 1, 0)), null), Solicitante.gestor(GESTOR));

        assertThat(guardadas).singleElement().satisfies(f -> {
            assertThat(f.getCantidadRecibida()).isEqualTo(2);
            assertThat(f.getCantidadApta()).isEqualTo(1);
        });
    }

    // ================================================================ datos de prueba

    private static Devolucion aprobada(TipoResolucion resolucion) {
        Devolucion d = new Devolucion();
        d.setId(42L);
        d.setIdPedido(981L);
        d.setTipo(resolucion == TipoResolucion.CAMBIO ? TipoDevolucion.CAMBIO : TipoDevolucion.DEVOLUCION_DINERO);
        d.setMotivo(MotivoDevolucion.PRODUCTO_DEFECTUOSO);
        d.setEstado(EstadoDevolucion.APROBADA);
        d.setResolucion(resolucion);
        d.setFecha(LocalDate.of(2026, 10, 12));
        d.agregarItem("PROD-101", 3);
        return d;
    }

    private static ConfirmarRecepcionRequest recepcion(String sku, int recibidas, int aptas) {
        return new ConfirmarRecepcionRequest(List.of(new ConfirmarRecepcionRequest.Linea(sku, recibidas, aptas)), null);
    }
}
