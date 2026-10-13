package postventa.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

import postventa.compartido.ConflictoException;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.devolucion.dto.DevolucionDetalle;
import postventa.devolucion.dto.RegistrarDevolucionRequest;
import postventa.devolucion.dto.RegistrarDevolucionResponse;
import postventa.devolucion.dto.ResolucionResponse;
import postventa.devolucion.dto.ResolverDevolucionRequest;
import postventa.integracion.DespachoClient;
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

    @Mock DevolucionRepository devoluciones;
    @Mock VentasClient ventas;
    @Mock ProductosClient productos;
    @Mock DespachoClient despacho;
    @Mock SolicitadorReembolso reembolsos;
    @Mock AlmacenEvidencias almacen;

    DevolucionService servicio;

    @BeforeEach
    void preparar() {
        servicio = new DevolucionService(devoluciones, ventas, productos, despacho, reembolsos, almacen,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(almacen.esPropia(EVIDENCIA)).thenReturn(true);
        when(devoluciones.save(any())).thenAnswer(inv -> {
            Devolucion d = inv.getArgument(0);
            d.setId(42L);
            return d;
        });
    }

    // ================================================================ ESP-07 (registro)

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
        assertThat(d.getItems()).singleElement().satisfies(i -> {
            assertThat(i.getIdProducto()).isEqualTo(101L);
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
        when(devoluciones.unidadesEnCurso(981L)).thenReturn(List.of(unidades(101L, 1L)));

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
        assertThat(d.fechaRegistro()).isEqualTo(OffsetDateTime.parse("2026-10-12T00:00:00Z"));
        assertThat(d.estado()).isEqualTo(EstadoDevolucion.SOLICITADA);
        assertThat(d.resolucion()).isNull();
        assertThat(d.evidencias()).hasSize(1);
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
    void esp08_ca02_cambioConStockReintegraCoordinaDespachoYCompleta() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(productos.hayDisponibilidad(any())).thenReturn(true);
        when(devoluciones.aprobar(42L, TipoResolucion.CAMBIO, GESTOR)).thenReturn(1);
        when(despacho.programarCambio(eq("DEV-2026-0042"), eq("PED-2026-00981"), eq(CLIENTE), any()))
                .thenReturn(Optional.of("DESP-1"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.CAMBIO);
        assertThat(r.referenciaOperacion()).isEqualTo("DESP-1");
        assertThat(r.autorizadorId()).isEqualTo(GESTOR);
        verify(productos).reintegrar(eq("DEV-2026-0042"), eq(List.of(new ProductosClient.ItemProducto(101, 1))));
        verify(devoluciones).completar(42L);
        verifyNoInteractions(reembolsos);
    }

    @Test
    void esp08_ca03_reembolsoPideAF4ConClaveDeIdempotenciaYCompleta() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(42L, TipoResolucion.REEMBOLSO, GESTOR)).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        ArgumentCaptor<SolicitadorReembolso.Solicitud> pedido = ArgumentCaptor.forClass(SolicitadorReembolso.Solicitud.class);
        verify(reembolsos).solicitar(pedido.capture());
        assertThat(pedido.getValue().claveIdempotencia()).isEqualTo("DEVOLUCION-42");
        assertThat(pedido.getValue().devolucionId()).isEqualTo("DEV-2026-0042");
        assertThat(pedido.getValue().monto()).isEqualByComparingTo("99.90");
        assertThat(pedido.getValue().moneda()).isEqualTo("PEN");
        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        assertThat(r.referenciaOperacion()).isEqualTo("REEM-00001");
        verify(productos).reintegrar(eq("DEV-2026-0042"), any());
        verifyNoInteractions(despacho);
    }

    @Test
    void reembolsoNoConfirmadoDejaLaDevolucionAprobada() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.aprobar(42L, TipoResolucion.REEMBOLSO, GESTOR)).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("PENDIENTE"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.APROBADA);
        verify(devoluciones, never()).completar(anyLong());
    }

    @Test
    void reintentarUnaAprobadaPendienteNoReintegraNiApruebaDeNuevo() {
        Devolucion d = devolucion(EstadoDevolucion.APROBADA, TipoDevolucion.DEVOLUCION_DINERO);
        d.setResolucion(TipoResolucion.REEMBOLSO);
        d.setIdAutorizador(GESTOR);
        conItems(d);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verify(devoluciones, never()).aprobar(anyLong(), any(), any());
        verify(productos, never()).reintegrar(any(), any());
    }

    @Test
    void esp08_ca04_rechazoRegistraFundamentoYAutorizador() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));
        when(devoluciones.rechazar(42L, GESTOR, "Signos de manipulación")).thenReturn(1);

        ResolucionResponse r = servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.RECHAZADA, "  Signos de manipulación ", null, null),
                Solicitante.gestor(GESTOR));

        assertThat(r.estado()).isEqualTo(EstadoDevolucion.RECHAZADA);
        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.RECHAZO);
        assertThat(r.fundamento()).isEqualTo("Signos de manipulación");
        verifyNoInteractions(productos, despacho, reembolsos);
    }

    @Test
    void f3_ca06_rechazoSinFundamentoSeRechaza() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.DEVOLUCION_DINERO));

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042",
                new ResolverDevolucionRequest(DecisionDevolucion.RECHAZADA, "   ", null, null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("FUNDAMENTO_REQUERIDO");
        verify(devoluciones, never()).rechazar(anyLong(), any(), any());
    }

    @Test
    void esp08_ca05_cambioSinStockResponde409YNoCambiaElExpediente() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(productos.hayDisponibilidad(any())).thenReturn(false);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("SIN_STOCK");
        verify(devoluciones, never()).aprobar(anyLong(), any(), any());
        verify(productos, never()).reintegrar(any(), any());
    }

    @Test
    void esp08_ca05_cambioSinStockSePuedeResolverComoReembolso() {
        conItems(devolucion(EstadoDevolucion.EN_EVALUACION, TipoDevolucion.CAMBIO));
        when(devoluciones.aprobar(42L, TipoResolucion.REEMBOLSO, GESTOR)).thenReturn(1);
        when(ventas.consultarPedido("PED-2026-00981")).thenReturn(Optional.of(entregado(2)));
        when(reembolsos.solicitar(any())).thenReturn(reembolso("EXITOSO"));

        ResolucionResponse r = servicio.resolver("DEV-2026-0042", aprobar(TipoResolucion.REEMBOLSO), Solicitante.gestor(GESTOR));

        assertThat(r.tipoResolucion()).isEqualTo(TipoResolucion.REEMBOLSO);
        assertThat(r.estado()).isEqualTo(EstadoDevolucion.COMPLETADA);
        verify(productos, never()).hayDisponibilidad(any());
        verifyNoInteractions(despacho);
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
        when(devoluciones.aprobar(42L, TipoResolucion.REEMBOLSO, GESTOR)).thenReturn(0);

        assertThatThrownBy(() -> servicio.resolver("DEV-2026-0042", aprobar(null), Solicitante.gestor(GESTOR)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("DEVOLUCION_NO_RESOLUBLE");
        verifyNoInteractions(reembolsos);
        verify(productos, never()).reintegrar(any(), any());
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

    // ================================================================ datos de prueba

    private static RegistrarDevolucionRequest solicitud(MotivoDevolucion motivo, String... evidencias) {
        return new RegistrarDevolucionRequest("PED-2026-00981", TipoDevolucion.DEVOLUCION_DINERO, motivo,
                "No enciende", List.of(new RegistrarDevolucionRequest.Item("PROD-101", 1)),
                List.of(evidencias).stream().map(u -> new RegistrarDevolucionRequest.Evidencia(TipoEvidencia.IMAGEN, u)).toList());
    }

    private static PedidoResumen entregado(int diasDesdeLaEntrega) {
        return new PedidoResumen("PED-2026-00981", "ENTREGADO", CLIENTE, new BigDecimal("129.90"), "PEN", items(),
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).minusDays(diasDesdeLaEntrega));
    }

    private static List<PedidoResumen.Item> items() {
        return List.of(new PedidoResumen.Item(101, 1, new BigDecimal("99.90")),
                new PedidoResumen.Item(999, 1, new BigDecimal("30.00")));
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
        d.agregarItem(101, 1);
        d.agregarEvidencia(EVIDENCIA, TipoEvidencia.IMAGEN);
        return d;
    }

    private void conItems(Devolucion d) {
        when(devoluciones.findWithItemsById(42L)).thenReturn(Optional.of(d));
    }

    private static ResolverDevolucionRequest aprobar(TipoResolucion tipoResolucion) {
        return new ResolverDevolucionRequest(DecisionDevolucion.APROBADA, null, null, tipoResolucion);
    }

    private static SolicitadorReembolso.Resultado reembolso(String estado) {
        return new SolicitadorReembolso.Resultado("REEM-00001", estado, new BigDecimal("99.90"), "PEN",
                "TX-SIM-000001", OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
    }

    private static DevolucionRepository.UnidadesDevueltas unidades(Long idProducto, Long cantidad) {
        return new DevolucionRepository.UnidadesDevueltas() {
            @Override public Long getIdProducto() { return idProducto; }
            @Override public Long getCantidad() { return cantidad; }
        };
    }
}
