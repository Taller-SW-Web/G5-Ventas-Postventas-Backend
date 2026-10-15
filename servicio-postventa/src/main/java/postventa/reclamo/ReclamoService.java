package postventa.reclamo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CanalVenta;
import postventa.compartido.CodigoPublico;
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

/**
 * F6: Libro de Reclamaciones. Registro (ESP-13), consulta y listado, y atención por el Gestor (ESP-14).
 * Endpoints 2.8 a 2.11 y 2.10b. Registrar y atender suman en agregado_metricas en la misma transacción.
 */
@Slf4j
@Service
public class ReclamoService {

    static final int TAMANO_MAXIMO_PAGINA = 100;

    /** fechaLimiteSLA es el último segundo del día límite, en UTC (guía 10.2). */
    private static final LocalTime FIN_DEL_DIA = LocalTime.of(23, 59, 59);

    private final ReclamoRepository reclamos;
    private final ProyeccionesService proyecciones;
    private final Clock reloj;

    @Autowired
    public ReclamoService(ReclamoRepository reclamos, ProyeccionesService proyecciones) {
        this(reclamos, proyecciones, Clock.systemUTC());
    }

    ReclamoService(ReclamoRepository reclamos, ProyeccionesService proyecciones, Clock reloj) {
        this.reclamos = reclamos;
        this.proyecciones = proyecciones;
        this.reloj = reloj;
    }

    // ---------------------------------------------------------------- 2.8 Registrar (ESP-13)

    /**
     * Los datos ya llegan validados por el DTO (motivo del catálogo, consumidor completo). Si algo falla aquí,
     * la transacción se revierte y no queda código generado (ESP-13 CA-04).
     */
    @Transactional
    public RegistrarReclamoResponse registrar(RegistrarReclamoRequest solicitud, Solicitante quien) {
        Long idPedido = textoOpcional(solicitud.pedidoId()) == null ? null
                : CodigoPublico.id(solicitud.pedidoId().trim());
        OffsetDateTime ahora = ahora();

        Reclamo reclamo = new Reclamo();
        reclamo.setCodigo(CodigoPublico.temporal());
        reclamo.setIdPedido(idPedido);
        reclamo.setTipo(solicitud.tipo());
        reclamo.setCanal(solicitud.canal());
        reclamo.setMotivo(solicitud.motivo());
        reclamo.setDetalle(solicitud.detalle().trim());
        reclamo.setNombreConsumidor(solicitud.consumidor().nombreCompleto().trim());
        reclamo.setDocumento(solicitud.consumidor().documento().trim());
        reclamo.setEmail(solicitud.consumidor().email().trim());
        reclamo.setTelefono(solicitud.consumidor().telefono().trim());
        reclamo.setIdCliente(quien.esCliente() ? quien.id() : null);
        reclamo.setEstado(EstadoReclamo.REGISTRADO);
        reclamo.setPlazoDiasHabiles(CalculadoraSla.PLAZO_DIAS_HABILES);
        reclamo.setFechaRegistro(ahora);
        reclamo.setFechaLimiteRespuesta(
                CalculadoraSla.sumarDiasHabiles(ahora.toLocalDate(), CalculadoraSla.PLAZO_DIAS_HABILES));

        Reclamo guardado = reclamos.saveAndFlush(reclamo);
        guardado.setCodigo(CodigoPublico.reclamo(guardado.getId(), ahora.getYear()));
        proyecciones.reclamoRegistrado(ahora, guardado.getCanal());

        log.info("Reclamo {} registrado por el canal {}", guardado.getCodigo(), guardado.getCanal());
        return new RegistrarReclamoResponse(guardado.getCodigo(), guardado.getCodigo(), guardado.getEstado(),
                guardado.getMotivo(), guardado.getPlazoDiasHabiles(), fechaLimiteSla(guardado), null);
    }

    // ---------------------------------------------------------------- 2.9 Consultar

    /** El cliente solo ve sus propios reclamos (403 como en F3); el Gestor ve todos. */
    @Transactional(readOnly = true)
    public ReclamoDetalle consultar(String codigo, Solicitante quien) {
        Reclamo r = buscar(codigo);
        if (quien.esCliente() && !quien.id().equals(r.getIdCliente())) {
            throw new AccessDeniedException("El reclamo no pertenece al cliente autenticado");
        }
        LocalDate hoy = hoy();
        Boolean slaCumplido = r.getFechaAtencion() == null ? null
                : !r.getFechaAtencion().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
                        .isAfter(r.getFechaLimiteRespuesta());
        return new ReclamoDetalle(r.getCodigo(), r.getCodigo(), r.codigoPedido(), r.getTipo(), r.getCanal(),
                r.getMotivo(), r.getDetalle(), r.getEstado(), r.getPlazoDiasHabiles(), fechaLimiteSla(r),
                r.getFechaRegistro(), r.getFechaAtencion(), r.getRespuestaVisibleCliente(),
                new ReclamoDetalle.Consumidor(r.getNombreConsumidor(), r.getDocumento(), r.getEmail(),
                        r.getTelefono()),
                r.getAtendidoPor(), CalculadoraSla.estado(r.getEstado(), r.getFechaLimiteRespuesta(), hoy),
                diasRestantes(r, hoy), slaCumplido);
    }

    // ---------------------------------------------------------------- 2.10 y 2.10b Listar

    /** 2.10 (Gestor, filtros opcionales) y 2.10b (idCliente = sub del token). Más recientes primero. */
    @Transactional(readOnly = true)
    public ListadoReclamosResponse listar(String documento, UUID idCliente, EstadoReclamo estado,
                                          int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO_PAGINA) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + TAMANO_MAXIMO_PAGINA);
        }
        Page<Reclamo> resultado = reclamos.buscar(textoOpcional(documento), idCliente, estado,
                PageRequest.of(pagina, tamano, Sort.by(Sort.Direction.DESC, "fechaRegistro", "id")));
        LocalDate hoy = hoy();
        return new ListadoReclamosResponse(resultado.getContent().stream()
                .map(r -> new ListadoReclamosResponse.Resumen(r.getCodigo(), r.getCodigo(), r.getTipo(),
                        r.getMotivo(), r.getEstado(), r.getFechaRegistro(), fechaLimiteSla(r), r.codigoPedido(),
                        r.getCanal(), r.getNombreConsumidor(),
                        CalculadoraSla.estado(r.getEstado(), r.getFechaLimiteRespuesta(), hoy),
                        diasRestantes(r, hoy)))
                .toList(), pagina, resultado.getTotalPages(), resultado.getTotalElements());
    }

    // ---------------------------------------------------------------- 2.11 Responder (ESP-14)

    /**
     * EN_PROCESO: "Iniciar atención", solo desde REGISTRADO y sin respuesta.
     * ATENDIDO / DERIVADO: exigen respuestaVisibleCliente (400 RESPUESTA_VACIA, el reclamo no cambia) y
     * registran la hora UTC de la respuesta. Un reclamo ATENDIDO ya no se vuelve a responder (409).
     */
    @Transactional
    public RespuestaReclamoResponse responder(String codigo, ResponderReclamoRequest solicitud) {
        EstadoReclamo nuevo = solicitud.nuevoEstado();
        if (nuevo == EstadoReclamo.REGISTRADO) {
            throw new SolicitudInvalidaException("ESTADO_INVALIDO",
                    "nuevoEstado debe ser EN_PROCESO, ATENDIDO o DERIVADO");
        }
        String respuesta = textoOpcional(solicitud.respuestaVisibleCliente());
        if (nuevo != EstadoReclamo.EN_PROCESO && respuesta == null) {
            throw new SolicitudInvalidaException("RESPUESTA_VACIA",
                    "respuestaVisibleCliente es obligatoria para cerrar el reclamo");
        }
        String atendidoPor = solicitud.atendidoPor().trim();

        Reclamo r = buscar(codigo);
        EstadoReclamo actual = r.getEstado();
        if (actual == EstadoReclamo.ATENDIDO) {
            throw new ConflictoException("RECLAMO_YA_ATENDIDO", "El reclamo " + r.getCodigo() + " ya fue atendido");
        }

        if (nuevo == EstadoReclamo.EN_PROCESO) {
            if (actual != EstadoReclamo.REGISTRADO || reclamos.iniciarAtencion(r.getId(), atendidoPor) == 0) {
                throw noRespondible(r, nuevo);
            }
            return new RespuestaReclamoResponse(r.getCodigo(), EstadoReclamo.EN_PROCESO, null,
                    r.getRespuestaVisibleCliente());
        }

        if (nuevo == EstadoReclamo.DERIVADO && actual == EstadoReclamo.DERIVADO) {
            throw noRespondible(r, nuevo);
        }
        OffsetDateTime ahora = ahora();
        if (reclamos.responder(r.getId(), actual, nuevo, respuesta, atendidoPor, ahora) == 0) {
            throw noRespondible(r, nuevo);
        }
        if (nuevo == EstadoReclamo.ATENDIDO) {
            proyecciones.reclamoAtendido(ahora, r.getCanal());
        }
        log.info("Reclamo {} pasa de {} a {} por {}", r.getCodigo(), actual, nuevo, atendidoPor);
        return new RespuestaReclamoResponse(r.getCodigo(), nuevo, ahora, respuesta);
    }

    // ---------------------------------------------------------------- Dashboard (2.12)

    /** Reclamos todavía sin respuesta final cuyo plazo vence en el periodo (guía 10.5). */
    @Transactional(readOnly = true)
    public long contarPendientesSla(LocalDate desde, LocalDate hasta, CanalVenta canal) {
        return reclamos.contarPendientesSla(desde, hasta, canal);
    }

    // ----------------------------------------------------------------

    private Reclamo buscar(String codigo) {
        return reclamos.findByCodigo(codigo == null ? "" : codigo.trim())
                .orElseThrow(() -> new RecursoNoEncontradoException("RECLAMO_NO_ENCONTRADO",
                        "No existe el reclamo " + codigo));
    }

    private static ConflictoException noRespondible(Reclamo r, EstadoReclamo nuevo) {
        return new ConflictoException("RECLAMO_NO_RESPONDIBLE",
                "El reclamo " + r.getCodigo() + " no puede pasar a " + nuevo + " desde su estado actual");
    }

    private static OffsetDateTime fechaLimiteSla(Reclamo r) {
        return OffsetDateTime.of(r.getFechaLimiteRespuesta(), FIN_DEL_DIA, ZoneOffset.UTC);
    }

    /** null cuando ya está ATENDIDO (el plazo dejó de correr). */
    private static Integer diasRestantes(Reclamo r, LocalDate hoy) {
        return r.getEstado() == EstadoReclamo.ATENDIDO ? null
                : CalculadoraSla.diasHabilesRestantes(r.getFechaLimiteRespuesta(), hoy);
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDate hoy() {
        return ahora().toLocalDate();
    }

    private static String textoOpcional(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
