package postventa.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import postventa.compartido.CanalVenta;
import postventa.compartido.ConflictoException;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.devolucion.dto.DevolucionDetalle;
import postventa.devolucion.dto.RegistrarDevolucionRequest;
import postventa.devolucion.dto.RegistrarDevolucionResponse;
import postventa.devolucion.dto.ResolucionResponse;
import postventa.dashboard.ProyeccionesService;
import postventa.devolucion.dto.ResolverDevolucionRequest;
import postventa.integracion.PedidoResumen;
import postventa.integracion.ProductosClient;
import postventa.integracion.VentasClient;

/** Criterios de aceptación de ESP-07, ESP-08 y F3, sin base de datos ni otros sistemas. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DevolucionServiceTest {

    static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");
    static final UUID CLIENTE = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
    static final UUID GESTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final String EVIDENCIA = "http://localhost:8082/uploads/evidencias/foto.jpg";
    static final OffsetDateTime AHORA_UTC = OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC);
    static final OffsetDateTime REGISTRO = OffsetDateTime.parse("2026-10-12T09:30:00Z");

    @Mock DevolucionRepository devoluciones;
    @Mock VentasClient ventas;
    @Mock ProductosClient productos;
    @Mock SolicitadorReembolso reembolsos;
    @Mock AlmacenEvidencias almacen;
    @Mock HistorialDevolucionRepository historial;
    @Mock RecepcionDevolucionRepository recepciones;
    @Mock ProyeccionesService proyecciones;

    DevolucionService servicio;

    @BeforeEach
    void preparar() {
        servicio = new DevolucionService(devoluciones, ventas, productos, reembolsos, almacen,
                historial, recepciones, proyecciones, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(almacen.esPropia(EVIDENCIA)).thenReturn(true);
        // Al resolver se consulta el pedido (canal para el dashboard y precio para el reembolso)
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(devoluciones.save(any())).thenAnswer(inv -> {
            Devolucion d = inv.getArgument(0);
            d.setId(42L);
            return d;
        });
    }

    // ================================================================ ESP-07 (registro)

    @Test
    void aceptaIdsRealesDeProductosComoTexto() {
        // Productos usa UUID o códigos como prd-013-01; antes M2 los convertía a número y respondía 400
        String uuid = "7f3c2a10-9b1e-4c55-8a21-0d4e5f6a7b8c";
        PedidoResumen pedido = new PedidoResumen("PED-2026-00981", "ENTREGADO", CLIENTE, new BigDecimal("129.90"), "PEN",
                List.of(new PedidoResumen.Item("prd-013-01", 1, new BigDecimal("99.90")),
                        new PedidoResumen.Item(uuid, 1, new BigDecimal("30.00"))),
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).minusDays(2));
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(pedido));

        servicio.registrar(new RegistrarDevolucionRequest("PED-2026-00981", TipoDevolucion.DEVOLUCION_DINERO,
                MotivoDevolucion.OTRO, null, List.of(new RegistrarDevolucionRequest.Item("prd-013-01", 1),
                new RegistrarDevolucionRequest.Item(uuid, 1)), null), Solicitante.servicio());

        ArgumentCaptor<Devolucion> guardada = ArgumentCaptor.forClass(Devolucion.class);
        verify(devoluciones).save(guardada.capture());
        assertThat(guardada.getValue().getItems()).extracting(DevolucionItem::getIdProducto)
                .containsExactly("prd-013-01", uuid);
    }

    @Test
    void ca01_registraDevolucionDePedidoEntregadoEnEstadoSolicitada() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));

        RegistrarDevolucionResponse r = servicio.registrar(solicitud(MotivoDevolucion.PRODUCTO_DEFECTUOSO, EVIDENCIA),
                Solicitante.servicio());

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.SOLICITADA);
        assertThat(r.pedidoId()).isEqualTo("PED-2026-00981");
        ArgumentCaptor<Devolucion> guardada = ArgumentCaptor.forClass(Devolucion.class);
        verify(devoluciones).save(guardada.capture());
        Devolucion d = guardada.getValue();
        assertThat(d.getIdPedido()).isEqualTo(981L);
        assertThat(d.getIdCliente()).isEqualTo(CLIENTE);
        assertThat(d.getFechaRegistro()).isEqualTo(AHORA_UTC);
        assertThat(d.getItems()).singleElement().satisfies(i -> {
            assertThat(i.getIdProducto()).isEqualTo("PROD-101");
            assertThat(i.getCantidad()).isEqualTo(1);
        });
        assertThat(d.getEvidencias()).singleElement().satisfies(e -> assertThat(e.getUrl()).isEqualTo(EVIDENCIA));
    }

    @Test
    void ca02_rechazaPedidoQueNoEstaEntregado() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(new PedidoResumen(
                "PED-2026-00981", "DESPACHADO", CLIENTE, new BigDecimal("129.90"), "PEN", items(), null)));

        assertThatThrownBy(() -> servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio()))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_ENTREGADO");
        verify(devoluciones, never()).save(any());
    }

    @Test
    void ca04_exigeEvidenciaCuandoElMotivoLaRequiere() {
        assertThatThrownBy(() -> servicio.registrar(solicitud(MotivoDevolucion.PRODUCTO_DANADO), Solicitante.servicio()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("EVIDENCIA_REQUERIDA");
        verifyNoInteractions(ventas);
        verify(devoluciones, never()).save(any());
    }

    @Test
    void motivoOtroNoExigeEvidencia() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));

        assertThat(servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio()).estado())
                .isEqualTo(EstadoDevolucion.SOLICITADA);
    }

    @Test
    void ca05_devuelveElIdentificadorPublicoDeLaDevolucion() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));

        RegistrarDevolucionResponse r = servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio());

        assertThat(r.devolucionId()).isEqualTo("DEV-2026-0042");
        assertThat(r.fechaRegistro()).isEqualTo(OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
    }

    @Test
    void rechazaSolicitudFueraDelPlazoDe7Dias() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(8)));

        assertThatThrownBy(() -> servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("PLAZO_VENCIDO");
    }

    @Test
    void elSeptimoDiaTodaviaEstaEnPlazo() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(7)));

        assertThat(servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio()).estado())
                .isEqualTo(EstadoDevolucion.SOLICITADA);
    }

    @Test
    void pedidoInexistenteResponde404() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio()))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_ENCONTRADO");
    }

    @Test
    void clienteNoPuedeDevolverUnPedidoAjeno() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));

        assertThatThrownBy(() -> servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.cliente(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        verify(devoluciones, never()).save(any());
    }

    @Test
    void evidenciaQueNoSalioDelUploadSeRechaza() {
        assertThatThrownBy(() -> servicio.registrar(
                solicitud(MotivoDevolucion.PRODUCTO_DEFECTUOSO, "https://otro-sitio.com/foto.jpg"), Solicitante.servicio()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("EVIDENCIA_INVALIDA");
    }

    @Test
    void productoQueNoEstaEnElPedidoSeRechaza() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        RegistrarDevolucionRequest s = new RegistrarDevolucionRequest("PED-2026-00981", TipoDevolucion.CAMBIO,
                MotivoDevolucion.OTRO, null, List.of(new RegistrarDevolucionRequest.Item("PROD-555", 1)), null);

        assertThatThrownBy(() -> servicio.registrar(s, Solicitante.servicio()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ITEM_INVALIDO");
    }

    @Test
    void noSeDevuelvenDosVecesLasMismasUnidades() {
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(devoluciones.unidadesEnCurso(981L)).thenReturn(List.of(unidades("PROD-101", 1L)));

        assertThatThrownBy(() -> servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio()))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("DEVOLUCION_EN_CURSO");
    }

    // ================================================================ consulta (ESP-07 CA-06, ESP-08 CA-01)

    @Test
    void ca06_laConsultaMuestraPedidoClienteFechaMotivoYEstado() {
        when(devoluciones.findById(42L)).thenReturn(Optional.of(devolucion(EstadoDevolucion.SOLICITADA, TipoDevolucion.CAMBIO)));

        DevolucionDetalle d = servicio.consultar("DEV-2026-0042", Solicitante.cliente(CLIENTE));

        assertThat(d.pedidoId()).isEqualTo("PED-2026-00981");
        assertThat(d.clienteId()).isEqualTo(CLIENTE);
        assertThat(d.motivo()).isEqualTo(MotivoDevolucion.PRODUCTO_DEFECTUOSO);
        assertThat(d.fechaRegistro()).isEqualTo(REGISTRO);
        assertThat(d.estado()).isEqualTo(EstadoDevolucion.SOLICITADA);
        assertThat(d.resolucion()).isNull();
        assertThat(d.evidencias()).hasSize(1);
    }

    @Test
    void esp08_ca06_laConsultaMuestraActorFechaYReferenciaDeLaResolucion() {
        Devolucion d = devolucion(EstadoDevolucion.COMPLETADA, TipoDevolucion.DEVOLUCION_DINERO);
        d.setResolucion(TipoResolucion.REEMBOLSO);
        d.setIdAutorizador(GESTOR);
        d.setFechaResolucion(AHORA_UTC);
        d.setReferenciaOperacion("REEM-00001");
        when(devoluciones.findById(42L)).thenReturn(Optional.of(d));
        when(reembolsos.consultar("DEV-2026-0042")).thenReturn(Optional.of(reembolso("EXITOSO")));

        DevolucionDetalle.Resolucion r = servicio.consultar("DEV-2026-0042", Solicitante.gestor(GESTOR)).resolucion();

        assertThat(r.decision()).isEqualTo(DecisionDevolucion.APROBADA);
        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.REEMBOLSO);
        assertThat(r.autorizadorId()).isEqualTo(GESTOR);
        assertThat(r.fechaResolucion()).isEqualTo(AHORA_UTC);
        assertThat(r.referenciaOperacion()).isEqualTo("REEM-00001");
        assertThat(r.reembolso().estado()).isEqualTo("EXITOSO");
    }

    @Test
    void esp08_ca01_alAbrirloElGestorPasaAEnEvaluacion() {
        Devolucion d = devolucion(EstadoDevolucion.SOLICITADA, TipoDevolucion.CAMBIO);
        when(devoluciones.findById(42L)).thenReturn(Optional.of(d));

        assertThat(servicio.consultar("DEV-2026-0042", Solicitante.gestor(GESTOR)).estado())
                .isEqualTo(EstadoDevolucion.EN_EVALUACION);
        assertThat(d.getEstado()).isEqualTo(EstadoDevolucion.EN_EVALUACION);
    }

    @Test
    void clienteNoVeDevolucionesAjenas() {
        when(devoluciones.findById(42L)).thenReturn(Optional.of(devolucion(EstadoDevolucion.SOLICITADA, TipoDevolucion.CAMBIO)));

        assertThatThrownBy(() -> servicio.consultar("DEV-2026-0042", Solicitante.cliente(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void codigoDeOtroAnioNoEncuentraLaDevolucion() {
        when(devoluciones.findById(42L)).thenReturn(Optional.of(devolucion(EstadoDevolucion.SOLICITADA, TipoDevolucion.CAMBIO)));

        assertThatThrownBy(() -> servicio.consultar("DEV-2025-0042", Solicitante.gestor(GESTOR)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    // ================================================================ ESP-08 (resolución)

    @Test
    void esp08_ca02_cambioConStockPideElReemplazoAM1YEsperaSuEntregaYLaRecepcionFisica() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(productos.hayDisponibilidad(any())).thenReturn(true);
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.CAMBIO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(conSku()));
        when(ventas.solicitarReemplazo(eq("DEV-2026-0042"), eq("PED-2026-00981"), any()))
                .thenReturn(new VentasClient.Reemplazo("PED-2026-01002", "EN_PREPARACION"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.APROBADA);
        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.CAMBIO);
        assertThat(r.referenciaOperacion()).isEqualTo("PED-2026-01002");
        assertThat(r.autorizadorId()).isEqualTo(GESTOR);
        assertThat(r.fechaResolucion()).isEqualTo(AHORA_UTC);
        // M1 repone por SKU: se toma del pedido original
        verify(ventas).solicitarReemplazo("DEV-2026-0042", "PED-2026-00981",
                List.of(new VentasClient.LineaReemplazo("SKU-101", 1)));
        verify(devoluciones).guardarReferencia(42L, "PED-2026-01002");
        verify(productos, never()).reintegrar(any(), any(), any(), any());
        verify(devoluciones, never()).completar(anyLong());
        verifyNoInteractions(reembolsos);
    }

    @Test
    void esp08_ca03_reembolsoPideAF4ConClaveDeIdempotenciaYEsperaLaRecepcionFisica() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        ArgumentCaptor<SolicitadorReembolso.Solicitud> pedido = ArgumentCaptor.forClass(SolicitadorReembolso.Solicitud.class);
        verify(reembolsos).solicitar(pedido.capture());
        assertThat(pedido.getValue().claveIdempotencia()).isEqualTo("DEVOLUCION-42");
        assertThat(pedido.getValue().devolucionId()).isEqualTo("DEV-2026-0042");
        assertThat(pedido.getValue().monto()).isEqualByComparingTo("99.90");
        assertThat(pedido.getValue().moneda()).isEqualTo("PEN");
        // El dinero no espera al producto (regla 16), pero el expediente sí
        assertThat(r.estado()).isEqualTo(EstadoDevolucion.APROBADA);
        assertThat(r.referenciaOperacion()).isEqualTo("REEM-00001");
        verify(productos, never()).reintegrar(any(), any(), any(), any());
        verify(ventas, never()).solicitarReemplazo(any(), any(), any());
    }

    @Test
    void reembolsoNoConfirmadoDejaLaDevolucionAprobada() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("PENDIENTE"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.APROBADA);
        assertThat(r.referenciaOperacion()).isEqualTo("REEM-00001");
        verify(devoluciones).guardarReferencia(42L, "REEM-00001");
        verify(devoluciones, never()).completar(anyLong());
    }

    @Test
    void siM1YaNoEncuentraElPedidoAlReembolsarRespondePedidoInconsistente() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_INCONSISTENTE");
        verifyNoInteractions(reembolsos);
        verify(devoluciones, never()).completar(anyLong());
    }

    @Test
    void reintentarUnaAprobadaPendienteNoReintegraNiApruebaDeNuevo() {
        Devolucion d = devolucion(EstadoDevolucion.APROBADA, TipoDevolucion.DEVOLUCION_DINERO);
        d.setResolucion(TipoResolucion.REEMBOLSO);
        d.setIdAutorizador(GESTOR);
        d.setFechaResolucion(REGISTRO);
        d.setReferenciaOperacion("REEM-00001");
        d.setRequiereDevolucionFisica(false);
        conItems(d);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verify(devoluciones, never()).aprobar(anyLong(), any(), any(), any(), anyBoolean());
        verify(productos, never()).reintegrar(any(), any(), any(), any());
        verify(devoluciones, never()).guardarReferencia(anyLong(), any());
        assertThat(r.fechaResolucion()).isEqualTo(REGISTRO);
    }

    @Test
    void esp08_ca04_rechazoRegistraFundamentoYAutorizador() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.rechazar(42L, GESTOR, "Signos de manipulación", AHORA_UTC)).thenReturn(1);

        ResolucionResponse r = servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.RECHAZADA, "  Signos de manipulación ", null, null),
                Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.RECHAZADA);
        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.RECHAZO);
        assertThat(r.fundamento()).isEqualTo("Signos de manipulación");
        assertThat(r.fechaResolucion()).isEqualTo(AHORA_UTC);
        verifyNoInteractions(productos, reembolsos);
        verify(ventas, never()).solicitarReemplazo(any(), any(), any());
    }

    @Test
    void f3_ca06_rechazoSinFundamentoSeRechaza() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.RECHAZADA, "   ", null, null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("FUNDAMENTO_REQUERIDO");
        verify(devoluciones, never()).rechazar(anyLong(), any(), any(), any());
    }

    @Test
    void esp08_ca05_cambioSinStockResponde409YNoCambiaElExpediente() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(productos.hayDisponibilidad(any())).thenReturn(false);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("SIN_STOCK");
        verify(devoluciones, never()).aprobar(anyLong(), any(), any(), any(), anyBoolean());
        verify(productos, never()).reintegrar(any(), any(), any(), any());
    }

    @Test
    void esp08_ca05_cambioSinStockSePuedeResolverComoReembolso() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.APROBADA, null, null, TipoResolucion.REEMBOLSO, false),
                Solicitante.gestor(GESTOR));

        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.REEMBOLSO);
        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verify(productos, never()).hayDisponibilidad(any());
        verify(ventas, never()).solicitarReemplazo(any(), any(), any());
    }

    @Test
    void devolucionDeDineroNoSePuedeResolverComoCambio() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(TipoResolucion.CAMBIO), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("RESOLUCION_INVALIDA");
    }

    @Test
    void unaDevolucionRechazadaNoSeVuelveAResolver() {
        Devolucion d = devolucion(EstadoDevolucion.RECHAZADA, TipoDevolucion.DEVOLUCION_DINERO);
        d.setResolucion(TipoResolucion.RECHAZO);
        conItems(d);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("DEVOLUCION_NO_RESOLUBLE");
    }

    @Test
    void f3_cambioYReembolsoNuncaALaVez_siOtroGestorYaResolvioResponde409() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(0);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("DEVOLUCION_NO_RESOLUBLE");
        verifyNoInteractions(reembolsos);
        verify(productos, never()).reintegrar(any(), any(), any(), any());
    }

    @Test
    void unaAprobadaComoCambioNoSePuedePasarAReembolso() {
        Devolucion d = devolucion(EstadoDevolucion.APROBADA, TipoDevolucion.CAMBIO);
        d.setResolucion(TipoResolucion.CAMBIO);
        conItems(d);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(TipoResolucion.REEMBOLSO), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("DEVOLUCION_NO_RESOLUBLE");
        verifyNoInteractions(reembolsos);
    }

    // ================================================================ historial (RNF-03) y dashboard (ESP-15)

    @Test
    void elRegistroDejaElPrimerRegistroDelHistorialConElCanalQueLoHizo() {
        servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.servicio("modulo-marketplace"));

        ArgumentCaptor<Devolucion> guardada = ArgumentCaptor.forClass(Devolucion.class);
        verify(devoluciones).save(guardada.capture());
        assertThat(guardada.getValue().getEstado()).isEqualTo(EstadoDevolucion.SOLICITADA);
        assertThat(guardada.getValue().getHistorial()).singleElement().satisfies(h -> {
            assertThat(h.getEstadoAnterior()).isNull();
            assertThat(h.getEstadoNuevo()).isEqualTo(EstadoDevolucion.SOLICITADA);
            assertThat(h.getActor()).isEqualTo(ActorDevolucion.CANAL);
            assertThat(h.getIdActor()).isEqualTo("modulo-marketplace");
            assertThat(h.getFechaHora()).isEqualTo(AHORA_UTC);
        });
    }

    @Test
    void siLaRegistraElClienteQuedaSuSubEnElHistorial() {
        servicio.registrar(solicitud(MotivoDevolucion.OTRO), Solicitante.cliente(CLIENTE));

        ArgumentCaptor<Devolucion> guardada = ArgumentCaptor.forClass(Devolucion.class);
        verify(devoluciones).save(guardada.capture());
        assertThat(guardada.getValue().getHistorial()).singleElement().satisfies(h -> {
            assertThat(h.getActor()).isEqualTo(ActorDevolucion.CLIENTE);
            assertThat(h.getIdActor()).isEqualTo(CLIENTE.toString());
        });
    }

    @Test
    void cuandoElGestorLaAbreQuedaEnElHistorialYSeVeEnLaConsulta() {
        when(devoluciones.findById(42L)).thenReturn(Optional.of(devolucion(EstadoDevolucion.SOLICITADA, TipoDevolucion.CAMBIO)));

        DevolucionDetalle d = servicio.consultar("DEV-2026-0042", Solicitante.gestor(GESTOR));

        assertThat(d.historial()).singleElement().satisfies(h -> {
            assertThat(h.estadoAnterior()).isEqualTo(EstadoDevolucion.SOLICITADA);
            assertThat(h.estado()).isEqualTo(EstadoDevolucion.EN_EVALUACION);
            assertThat(h.actor()).isEqualTo(ActorDevolucion.GESTOR);
            assertThat(h.idActor()).isEqualTo(GESTOR.toString());
            assertThat(h.fecha()).isEqualTo(AHORA_UTC);
        });
    }

    @Test
    void aprobarGuardaElHistorialYSumaLaDevolucionAlDashboard() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(devoluciones.completar(42L)).thenReturn(1);
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        servicio.resolver("DEV-2026-0042", aprobarSinDevolucionFisica(), Solicitante.gestor(GESTOR));

        ArgumentCaptor<HistorialDevolucion> h = ArgumentCaptor.forClass(HistorialDevolucion.class);
        verify(historial, times(2)).save(h.capture());
        HistorialDevolucion aprobada = h.getAllValues().get(0);
        HistorialDevolucion completada = h.getAllValues().get(1);
        assertThat(aprobada.getEstadoAnterior()).isEqualTo(EstadoDevolucion.EN_EVALUACION);
        assertThat(aprobada.getEstadoNuevo()).isEqualTo(EstadoDevolucion.APROBADA);
        assertThat(aprobada.getActor()).isEqualTo(ActorDevolucion.GESTOR);
        assertThat(aprobada.getDetalle()).isEqualTo("Aprobada como REEMBOLSO");
        assertThat(completada.getEstadoNuevo()).isEqualTo(EstadoDevolucion.COMPLETADA);
        assertThat(completada.getActor()).isEqualTo(ActorDevolucion.SISTEMA);
        assertThat(completada.getDetalle()).contains("REEM-00001");
        verify(proyecciones).devolucionAprobada(AHORA_UTC, CanalVenta.MARKETPLACE);
    }

    @Test
    void unPedidoSinCanalSeApruebaIgualPeroNoSumaAlDashboard() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(new PedidoResumen("PED-2026-00981",
                "ENTREGADO", CLIENTE, new BigDecimal("129.90"), "PEN", items(), AHORA_UTC.minusDays(2), true, null)));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobarSinDevolucionFisica(), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verifyNoInteractions(proyecciones);
    }

    @Test
    void rechazarGuardaElFundamentoEnElHistorialYNoSumaAlDashboard() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.rechazar(42L, GESTOR, "Sin defecto visible", AHORA_UTC)).thenReturn(1);

        servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.RECHAZADA, "Sin defecto visible", null, null),
                Solicitante.gestor(GESTOR));

        ArgumentCaptor<HistorialDevolucion> h = ArgumentCaptor.forClass(HistorialDevolucion.class);
        verify(historial).save(h.capture());
        assertThat(h.getValue().getEstadoAnterior()).isEqualTo(EstadoDevolucion.EN_EVALUACION);
        assertThat(h.getValue().getEstadoNuevo()).isEqualTo(EstadoDevolucion.RECHAZADA);
        assertThat(h.getValue().getDetalle()).isEqualTo("Sin defecto visible");
        verifyNoInteractions(proyecciones);
    }

    @Test
    void elReintentoNoVuelveASumarAlDashboardNiRepiteLaAprobacion() {
        Devolucion d = devolucion(EstadoDevolucion.APROBADA, TipoDevolucion.DEVOLUCION_DINERO);
        d.setResolucion(TipoResolucion.REEMBOLSO);
        d.setIdAutorizador(GESTOR);
        d.setReferenciaOperacion("REEM-00001");
        d.setRequiereDevolucionFisica(false);
        conItems(d);
        when(devoluciones.completar(42L)).thenReturn(1);
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        ArgumentCaptor<HistorialDevolucion> h = ArgumentCaptor.forClass(HistorialDevolucion.class);
        verify(historial).save(h.capture());
        assertThat(h.getValue().getEstadoNuevo()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verifyNoInteractions(proyecciones);
    }

    @Test
    void siOtroGestorYaLaResolvioNoQuedaHistorialNiMetrica() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(0);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class);
        verify(historial, never()).save(any());
        verifyNoInteractions(proyecciones);
    }

    // ================================================================ recepción física (ESP-08 reglas 14 a 16)

    @Test
    void esp08_ca07_aprobarNoReintegraStockAunqueLaResolucionYaEsteAprobada() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(productos.hayDisponibilidad(any())).thenReturn(true);
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.CAMBIO), eq(GESTOR), eq(AHORA_UTC), eq(true))).thenReturn(1);
        when(ventas.solicitarReemplazo(any(), any(), any())).thenReturn(new VentasClient.Reemplazo("PED-2026-01002", "CREADO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.APROBADA);
        verify(devoluciones).aprobar(42L, TipoResolucion.CAMBIO, GESTOR, AHORA_UTC, true);
        verify(productos, never()).reintegrar(any(), any(), any(), any());
    }

    @Test
    void unCambioSiempreRequiereQueElClienteDevuelvaElProducto() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.APROBADA, null, null, null, false), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("RESOLUCION_INVALIDA");
        verify(devoluciones, never()).aprobar(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void esp08_regla16_reembolsoSinDevolucionFisicaSeCompletaSinEsperarRecepcion() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), eq(false))).thenReturn(1);
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobarSinDevolucionFisica(), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verify(devoluciones).completar(42L);
        verifyNoInteractions(recepciones);
    }

    @Test
    void reembolsoConProductoSeCompletaSiYaLlegaronTodasLasUnidades() {
        Devolucion d = devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO);
        conItems(d);
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), eq(true))).thenReturn(1);
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));
        when(recepciones.findByDevolucionIdOrderByIdAsc(42L)).thenReturn(List.of(recepcion(d, "PROD-101", 1, EstadoReintegro.COMPLETADO)));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
    }

    @Test
    void unaRecepcionConReintegroPendienteNoCuentaParaCompletar() {
        Devolucion d = devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO);
        conItems(d);
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.REEMBOLSO), eq(GESTOR), eq(AHORA_UTC), eq(true))).thenReturn(1);
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));
        when(recepciones.findByDevolucionIdOrderByIdAsc(42L)).thenReturn(List.of(recepcion(d, "PROD-101", 1, EstadoReintegro.PENDIENTE)));

        assertThat(servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)).estado())
                .isEqualTo(EstadoDevolucion.APROBADA);
        verify(devoluciones, never()).completar(anyLong());
    }

    @Test
    void laConsultaMuestraLasRecepcionesYSiRequiereDevolucionFisica() {
        Devolucion d = devolucion(EstadoDevolucion.APROBADA, TipoDevolucion.CAMBIO);
        d.setResolucion(TipoResolucion.CAMBIO);
        when(devoluciones.findById(42L)).thenReturn(Optional.of(d));
        when(recepciones.findByDevolucionIdOrderByIdAsc(42L)).thenReturn(List.of(recepcion(d, "PROD-101", 1, EstadoReintegro.COMPLETADO)));

        DevolucionDetalle detalle = servicio.consultar("DEV-2026-0042", Solicitante.gestor(GESTOR));

        assertThat(detalle.resolucion().requiereDevolucionFisica()).isTrue();
        assertThat(detalle.recepciones()).singleElement().satisfies(r -> {
            assertThat(r.sku()).isEqualTo("SKU-101");
            assertThat(r.cantidadApta()).isEqualTo(1);
            assertThat(r.reintegroEstado()).isEqualTo(EstadoReintegro.COMPLETADO);
        });
    }

    // ================================================================ datos de prueba

    private static RegistrarDevolucionRequest solicitud(MotivoDevolucion motivo, String... evidencias) {
        return new RegistrarDevolucionRequest("PED-2026-00981", TipoDevolucion.DEVOLUCION_DINERO, motivo,
                "No enciende", List.of(new RegistrarDevolucionRequest.Item("PROD-101", 1)),
                List.of(evidencias).stream().map(u -> new RegistrarDevolucionRequest.Evidencia(TipoEvidencia.IMAGEN, u)).toList());
    }

    private static PedidoResumen entregado(int diasDesdeLaEntrega) {
        return new PedidoResumen("PED-2026-00981", "ENTREGADO", CLIENTE, new BigDecimal("129.90"), "PEN", items(),
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).minusDays(diasDesdeLaEntrega), true, CanalVenta.MARKETPLACE);
    }

    /** Pedido de 1.3 con SKU en las líneas, como lo manda M1. */
    private static PedidoResumen conSku() {
        return new PedidoResumen("PED-2026-00981", "ENTREGADO", CLIENTE, new BigDecimal("129.90"), "PEN",
                List.of(new PedidoResumen.Item("PROD-101", 1, new BigDecimal("99.90"), "SKU-101"),
                        new PedidoResumen.Item("PROD-999", 1, new BigDecimal("30.00"), "SKU-999")),
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).minusDays(2), true, CanalVenta.MARKETPLACE);
    }

    private static List<PedidoResumen.Item> items() {
        return List.of(new PedidoResumen.Item("PROD-101", 1, new BigDecimal("99.90")),
                new PedidoResumen.Item("PROD-999", 1, new BigDecimal("30.00")));
    }

    private static Devolucion devolucion(EstadoDevolucion estado, TipoDevolucion tipo) {
        Devolucion d = new Devolucion();
        d.setId(42L);
        d.setIdPedido(981L);
        d.setIdCliente(CLIENTE);
        d.setTipo(tipo);
        d.setMotivo(MotivoDevolucion.PRODUCTO_DEFECTUOSO);
        d.setEstado(estado);
        d.setFecha(LocalDate.of(2026, 10, 12));
        d.setFechaRegistro(REGISTRO);
        d.agregarItem("PROD-101", 1);
        d.agregarEvidencia(EVIDENCIA, TipoEvidencia.IMAGEN);
        return d;
    }

    private void conItems(Devolucion d) {
        when(devoluciones.findWithItemsById(42L)).thenReturn(Optional.of(d));
    }

    private static ResolverDevolucionRequest aprobarSinDevolucionFisica() {
        return new ResolverDevolucionRequest(DecisionDevolucion.APROBADA, null, null, null, false);
    }

    private static RecepcionDevolucion recepcion(Devolucion d, String idProducto, int recibidas, EstadoReintegro estado) {
        RecepcionDevolucion r = new RecepcionDevolucion();
        r.setDevolucion(d);
        r.setOperationId(UUID.randomUUID());
        r.setSku("SKU-101");
        r.setIdProducto(idProducto);
        r.setCantidadRecibida(recibidas);
        r.setCantidadApta(recibidas);
        r.setReintegroEstado(estado);
        r.setFechaHora(AHORA_UTC);
        return r;
    }

    private static ResolverDevolucionRequest aprobar(TipoResolucion tipoResolucion) {
        return new ResolverDevolucionRequest(DecisionDevolucion.APROBADA, null, null, tipoResolucion);
    }

    private static SolicitadorReembolso.Resultado reembolso(String estado) {
        return new SolicitadorReembolso.Resultado("REEM-00001", estado, new BigDecimal("99.90"), "PEN",
                "TX-SIM-000001", OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
    }

    private static DevolucionRepository.UnidadesDevueltas unidades(String idProducto, Long cantidad) {
        return new DevolucionRepository.UnidadesDevueltas() {
            @Override public String getIdProducto() { return idProducto; }
            @Override public Long getCantidad() { return cantidad; }
        };
    }

    // ================================================================ orden de reemplazo de M1 (CAMBIO)

    private Devolucion cambioAprobado(String referencia) {
        Devolucion d = devolucion(EstadoDevolucion.APROBADA, TipoDevolucion.CAMBIO);
        d.setResolucion(TipoResolucion.CAMBIO);
        d.setReferenciaOperacion(referencia);
        d.setFechaResolucion(AHORA_UTC.minusDays(1));
        conItems(d);
        return d;
    }

    @Test
    void laEntregaDelReemplazoCompletaElCambioSiYaLlegoElProducto() {
        Devolucion d = cambioAprobado("PED-2026-01002");
        when(recepciones.findByDevolucionIdOrderByIdAsc(42L)).thenReturn(List.of(recepcion(d, "PROD-101", 1, EstadoReintegro.COMPLETADO)));
        when(devoluciones.completar(42L)).thenReturn(1);

        servicio.reemplazoEntregado("DEV-2026-0042", "PED-2026-01002");

        verify(devoluciones).completar(42L);
        verify(historial).save(argThat(h -> h.getEstadoNuevo() == EstadoDevolucion.COMPLETADA
                && h.getDetalle().contains("PED-2026-01002")));
    }

    @Test
    void laEntregaDelReemplazoSinRecepcionDejaElCambioAprobado() {
        cambioAprobado("PED-2026-01002");

        servicio.reemplazoEntregado("DEV-2026-0042", "PED-2026-01002");

        verify(devoluciones, never()).completar(anyLong());
    }

    @Test
    void unAvisoDeOtroReemplazoODeUnaDevolucionInexistenteSeIgnora() {
        cambioAprobado("PED-2026-01005");
        servicio.reemplazoEntregado("DEV-2026-0042", "PED-2026-01002");
        servicio.reemplazoAnulado("DEV-2026-0042", "PED-2026-01002", "RESERVA_EXPIRADA");
        servicio.reemplazoEntregado("DEV-2026-0077", "PED-2026-01002");

        verify(devoluciones, never()).completar(anyLong());
        verify(devoluciones, never()).reabrirCambio(anyLong(), any());
        verifyNoInteractions(historial);
    }

    @Test
    void siM1AnulaElReemplazoElCambioVuelveAEvaluacion() {
        cambioAprobado("PED-2026-01002");
        when(devoluciones.reabrirCambio(42L, "PED-2026-01002")).thenReturn(1);

        servicio.reemplazoAnulado("DEV-2026-0042", "PED-2026-01002", "RESERVA_EXPIRADA");

        verify(historial).save(argThat(h -> h.getEstadoAnterior() == EstadoDevolucion.APROBADA
                && h.getEstadoNuevo() == EstadoDevolucion.EN_EVALUACION && h.getDetalle().contains("RESERVA_EXPIRADA")));
    }

    @Test
    void reaprobarTrasAnularseElReemplazoNoSumaOtraVezAlDashboard() {
        Devolucion d = devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO);
        d.setFechaResolucion(AHORA_UTC.minusDays(1));   // ya se aprobó una vez
        conItems(d);
        when(productos.hayDisponibilidad(any())).thenReturn(true);
        when(devoluciones.aprobar(eq(42L), eq(TipoResolucion.CAMBIO), eq(GESTOR), eq(AHORA_UTC), anyBoolean())).thenReturn(1);
        when(ventas.solicitarReemplazo(any(), any(), any())).thenReturn(new VentasClient.Reemplazo("PED-2026-01007", "CREADO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.referenciaOperacion()).isEqualTo("PED-2026-01007");
        verifyNoInteractions(proyecciones);
    }

    @Test
    void cambioEntregadoPreguntaAM1PorElReemplazo() {
        Devolucion d = cambioAprobado("PED-2026-01002");
        when(ventas.consultarPedido("PED-2026-01002")).thenReturn(Optional.of(entregado(0)));
        assertThat(servicio.cambioEntregado(d)).isTrue();

        when(ventas.consultarPedido("PED-2026-01002")).thenThrow(new org.springframework.security.access.AccessDeniedException("403"));
        assertThat(servicio.cambioEntregado(d)).isFalse();

        d.setReferenciaOperacion(null);
        assertThat(servicio.cambioEntregado(d)).isFalse();
    }
}
