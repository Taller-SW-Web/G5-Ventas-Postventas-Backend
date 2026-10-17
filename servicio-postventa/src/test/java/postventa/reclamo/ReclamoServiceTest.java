package postventa.reclamo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import postventa.compartido.CanalVenta;
import postventa.compartido.ConflictoException;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.ProyeccionesService;
import postventa.devolucion.Solicitante;
import postventa.reclamo.dto.ListadoReclamosResponse;
import postventa.reclamo.dto.ReclamoDetalle;
import postventa.reclamo.dto.RegistrarReclamoRequest;
import postventa.reclamo.dto.RegistrarReclamoResponse;
import postventa.reclamo.dto.RespuestaReclamoResponse;
import postventa.reclamo.dto.ResponderReclamoRequest;

/** Criterios de aceptación de ESP-13, ESP-14 y F6 (CA-01 a CA-07), sin base de datos. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReclamoServiceTest {

    static final Instant AHORA = Instant.parse("2026-09-23T00:10:00Z");
    static final OffsetDateTime AHORA_UTC = OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC);
    static final UUID CLIENTE = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
    static final UUID OTRO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock ReclamoRepository reclamos;
    @Mock ProyeccionesService proyecciones;

    ReclamoService servicio;

    @BeforeEach
    void preparar() {
        servicio = new ReclamoService(reclamos, proyecciones, Clock.fixed(AHORA, ZoneOffset.UTC));
        when(reclamos.saveAndFlush(any())).thenAnswer(inv -> {
            Reclamo r = inv.getArgument(0);
            r.setId(15L);
            return r;
        });
    }

    // ================================================================ ESP-13 (registro)

    @Test
    void esp13_ca01_ca03_registraConCodigoEstadoInicialYSla() {
        RegistrarReclamoResponse r = servicio.registrar(solicitud("PED-2026-00981", TipoReclamo.RECLAMO),
                Solicitante.servicio());

        assertThat(r.reclamoId()).isEqualTo("REC-2026-0015");
        assertThat(r.codigoSeguimiento()).isEqualTo("REC-2026-0015");
        assertThat(r.estado()).isEqualTo(EstadoReclamo.REGISTRADO);
        assertThat(r.plazoDiasHabiles()).isEqualTo(15);
        assertThat(r.fechaLimiteSLA()).isEqualTo(OffsetDateTime.parse("2026-10-14T23:59:59Z"));
        assertThat(r.respuestaVisibleCliente()).isNull();

        ArgumentCaptor<Reclamo> guardado = ArgumentCaptor.forClass(Reclamo.class);
        verify(reclamos).saveAndFlush(guardado.capture());
        Reclamo reclamo = guardado.getValue();
        assertThat(reclamo.getCodigo()).isEqualTo("REC-2026-0015"); // el temporal se reemplazó
        assertThat(reclamo.getIdPedido()).isEqualTo(981L);
        assertThat(reclamo.getIdCliente()).isNull();                // canal: sin sub de cliente
        assertThat(reclamo.getFechaLimiteRespuesta()).isEqualTo(LocalDate.parse("2026-10-14"));
        verify(proyecciones).reclamoRegistrado(AHORA_UTC, CanalVenta.CHATBOT);
    }

    @Test
    void esp13_ca02_quejaGeneralSinPedido() {
        servicio.registrar(solicitud(null, TipoReclamo.QUEJA), Solicitante.servicio());

        ArgumentCaptor<Reclamo> guardado = ArgumentCaptor.forClass(Reclamo.class);
        verify(reclamos).saveAndFlush(guardado.capture());
        assertThat(guardado.getValue().getIdPedido()).isNull();
        assertThat(guardado.getValue().getTipo()).isEqualTo(TipoReclamo.QUEJA);
    }

    @Test
    void clienteQueRegistraQuedaComoTitular() {
        servicio.registrar(solicitud(null, TipoReclamo.RECLAMO), Solicitante.cliente(CLIENTE));

        ArgumentCaptor<Reclamo> guardado = ArgumentCaptor.forClass(Reclamo.class);
        verify(reclamos).saveAndFlush(guardado.capture());
        assertThat(guardado.getValue().getIdCliente()).isEqualTo(CLIENTE);
    }

    @Test
    void esp13_ca04_pedidoMalFormadoNoGeneraCodigo() {
        assertThatThrownBy(() -> servicio.registrar(solicitud("PED-XX", TipoReclamo.RECLAMO), Solicitante.servicio()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CODIGO_INVALIDO");
        verifyNoInteractions(reclamos, proyecciones);
    }

    @Test
    void esp13_ca04_codigoDeOtroRecursoComoPedidoNoGeneraCodigo() {
        // Termina en número (CodigoPublico.id lo aceptaría), pero no es un pedido
        assertThatThrownBy(() -> servicio.registrar(solicitud("REC-2026-0001", TipoReclamo.RECLAMO),
                Solicitante.servicio()))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CODIGO_INVALIDO");
        verifyNoInteractions(reclamos, proyecciones);
    }

    // ================================================================ 2.9 consulta (F6 CA-06)

    @Test
    void consultaInexistenteResponde404() {
        when(reclamos.findByCodigo("REC-2026-9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.consultar("REC-2026-9999", Solicitante.gestor(null)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("RECLAMO_NO_ENCONTRADO");
    }

    @Test
    void clienteNoVeReclamosAjenos() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.REGISTRADO, CLIENTE)));

        assertThatThrownBy(() -> servicio.consultar("REC-2026-0015", Solicitante.cliente(OTRO)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void esp14_ca02_ca04_clienteVeSuReclamoConLaRespuestaVisible() {
        Reclamo r = reclamo(EstadoReclamo.ATENDIDO, CLIENTE);
        r.setRespuestaVisibleCliente("Se coordinó la entrega prioritaria.");
        r.setFechaAtencion(OffsetDateTime.parse("2026-09-23T08:30:00Z"));
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(r));

        ReclamoDetalle d = servicio.consultar("REC-2026-0015", Solicitante.cliente(CLIENTE));

        assertThat(d.respuestaVisibleCliente()).isEqualTo("Se coordinó la entrega prioritaria.");
        assertThat(d.pedidoId()).isEqualTo("PED-2026-00981");
        assertThat(d.estadoSla()).isEqualTo(EstadoSla.ATENDIDO);
        assertThat(d.slaCumplido()).isTrue();
        assertThat(d.diasHabilesRestantes()).isNull();
    }

    @Test
    void esp14_ca04_respuestaFueraDePlazoMarcaSlaExcedido() {
        Reclamo r = reclamo(EstadoReclamo.ATENDIDO, CLIENTE);
        r.setFechaAtencion(OffsetDateTime.parse("2026-10-15T10:00:00Z")); // un día después del límite
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(r));

        assertThat(servicio.consultar("REC-2026-0015", Solicitante.gestor(null)).slaCumplido()).isFalse();
    }

    @Test
    void reclamoAbiertoMuestraSemaforoYDiasRestantes() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.EN_PROCESO, null)));

        ReclamoDetalle d = servicio.consultar("REC-2026-0015", Solicitante.gestor(null));

        assertThat(d.estadoSla()).isEqualTo(EstadoSla.EN_PLAZO);
        assertThat(d.diasHabilesRestantes()).isEqualTo(15);
        assertThat(d.slaCumplido()).isNull();
    }

    // ================================================================ ESP-14 (atención)

    @Test
    void esp14_ca01_atiendeYRegistraLaHoraUtc() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.EN_PROCESO, null)));
        when(reclamos.responder(15L, EstadoReclamo.EN_PROCESO, EstadoReclamo.ATENDIDO, "Respuesta formal",
                "GESTOR-02", AHORA_UTC)).thenReturn(1);

        RespuestaReclamoResponse r = servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.ATENDIDO, "  Respuesta formal  ", "GESTOR-02"));

        assertThat(r.estado()).isEqualTo(EstadoReclamo.ATENDIDO);
        assertThat(r.fechaRespuesta()).isEqualTo(AHORA_UTC);
        assertThat(r.respuestaVisibleCliente()).isEqualTo("Respuesta formal");
        verify(proyecciones).reclamoAtendido(AHORA_UTC, CanalVenta.CHATBOT);
    }

    @Test
    void esp14_ca03_respuestaVaciaNoCierraElReclamo() {
        assertThatThrownBy(() -> servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.ATENDIDO, "   ", "GESTOR-02")))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("RESPUESTA_VACIA");
        verifyNoInteractions(reclamos, proyecciones);
    }

    @Test
    void reclamoYaAtendidoResponde409() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.ATENDIDO, null)));

        assertThatThrownBy(() -> servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.ATENDIDO, "Otra respuesta", "GESTOR-02")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("RECLAMO_YA_ATENDIDO");
        verify(reclamos, never()).responder(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void iniciarAtencionPasaAEnProcesoSinRespuesta() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.REGISTRADO, null)));
        when(reclamos.iniciarAtencion(15L, "GESTOR-02")).thenReturn(1);

        RespuestaReclamoResponse r = servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.EN_PROCESO, null, "GESTOR-02"));

        assertThat(r.estado()).isEqualTo(EstadoReclamo.EN_PROCESO);
        assertThat(r.fechaRespuesta()).isNull();
        verifyNoInteractions(proyecciones);
    }

    @Test
    void iniciarAtencionDosVecesResponde409() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.EN_PROCESO, null)));

        assertThatThrownBy(() -> servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.EN_PROCESO, null, "GESTOR-02")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo("RECLAMO_NO_RESPONDIBLE");
        verify(reclamos, never()).iniciarAtencion(anyLong(), anyString());
    }

    @Test
    void dosGestoresALaVezSoloUnoCierra() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.EN_PROCESO, null)));
        when(reclamos.responder(anyLong(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.ATENDIDO, "Respuesta", "GESTOR-02")))
                .isInstanceOf(ConflictoException.class);
        verifyNoInteractions(proyecciones);
    }

    @Test
    void derivadoExigeRespuestaYNoSumaAtendidos() {
        when(reclamos.findByCodigo("REC-2026-0015")).thenReturn(Optional.of(reclamo(EstadoReclamo.EN_PROCESO, null)));
        when(reclamos.responder(eq(15L), eq(EstadoReclamo.EN_PROCESO), eq(EstadoReclamo.DERIVADO), any(), any(), any()))
                .thenReturn(1);

        RespuestaReclamoResponse r = servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.DERIVADO, "Se derivó a mediación", "GESTOR-02"));

        assertThat(r.estado()).isEqualTo(EstadoReclamo.DERIVADO);
        verifyNoInteractions(proyecciones);
    }

    @Test
    void nuevoEstadoRegistradoNoSePermite() {
        assertThatThrownBy(() -> servicio.responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.REGISTRADO, "x", "GESTOR-02")))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ESTADO_INVALIDO");
    }

    // ================================================================ 2.10 listado

    @Test
    void paginacionFueraDeRango() {
        assertThatThrownBy(() -> servicio.listar(FiltroReclamos.de(null, null, null), 0, 101))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(FiltroReclamos.de(null, null, null), -1, 10))
                .isInstanceOf(SolicitudInvalidaException.class);
        verifyNoInteractions(reclamos);
    }

    @Test
    void fechaDeCreacionInvertidaResponde400() {
        FiltroReclamos filtro = new FiltroReclamos(null, null, null, null, null,
                LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-01"), null);

        assertThatThrownBy(() -> servicio.listar(filtro, 0, 10))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("RANGO_FECHAS_INVALIDO");
        verifyNoInteractions(reclamos);
    }

    @Test
    void listadoArmaElResumenConSemaforoYOrden() {
        when(reclamos.findAll(ArgumentMatchers.<Specification<Reclamo>>any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(reclamo(EstadoReclamo.EN_PROCESO, null)),
                        inv.getArgument(1), 1));
        FiltroReclamos filtro = new FiltroReclamos(null, null, null, "juan", EstadoSla.EN_PLAZO, null, null,
                FiltroReclamos.Orden.ANTIGUOS);

        ListadoReclamosResponse r = servicio.listar(filtro, 0, 5);

        assertThat(r.totalElementos()).isEqualTo(1);
        ListadoReclamosResponse.Resumen fila = r.contenido().get(0);
        assertThat(fila.pedidoId()).isEqualTo("PED-2026-00981");
        assertThat(fila.nombreConsumidor()).isEqualTo("Juan Pérez");
        assertThat(fila.estadoSla()).isEqualTo(EstadoSla.EN_PLAZO);
        assertThat(fila.diasHabilesRestantes()).isEqualTo(15);
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(reclamos).findAll(ArgumentMatchers.<Specification<Reclamo>>any(), pagina.capture());
        assertThat(pagina.getValue().getSort().getOrderFor("fechaRegistro").isAscending()).isTrue();
    }

    // ================================================================ datos de prueba

    private static RegistrarReclamoRequest solicitud(String pedidoId, TipoReclamo tipo) {
        return new RegistrarReclamoRequest(pedidoId, tipo, CanalVenta.CHATBOT,
                MotivoReclamo.INCUMPLIMIENTO_PLAZO_ENTREGA, "El pedido no llegó en la fecha programada",
                new RegistrarReclamoRequest.Consumidor("Juan Pérez", "72458912", "juan.perez@example.com",
                        "+51999888777"));
    }

    private static Reclamo reclamo(EstadoReclamo estado, UUID cliente) {
        Reclamo r = new Reclamo();
        r.setId(15L);
        r.setCodigo("REC-2026-0015");
        r.setIdPedido(981L);
        r.setTipo(TipoReclamo.RECLAMO);
        r.setCanal(CanalVenta.CHATBOT);
        r.setMotivo(MotivoReclamo.INCUMPLIMIENTO_PLAZO_ENTREGA);
        r.setDetalle("El pedido no llegó");
        r.setNombreConsumidor("Juan Pérez");
        r.setDocumento("72458912");
        r.setEmail("juan.perez@example.com");
        r.setTelefono("+51999888777");
        r.setEstado(estado);
        r.setPlazoDiasHabiles(15);
        r.setFechaRegistro(AHORA_UTC);
        r.setFechaLimiteRespuesta(LocalDate.parse("2026-10-14"));
        r.setIdCliente(cliente);
        return r;
    }
}
