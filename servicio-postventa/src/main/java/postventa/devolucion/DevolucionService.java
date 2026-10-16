package postventa.devolucion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import postventa.compartido.CodigoPublico;
import postventa.compartido.ConflictoException;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.dashboard.ProyeccionesService;
import postventa.devolucion.dto.DevolucionDetalle;
import postventa.devolucion.dto.ListadoDevolucionesResponse;
import postventa.devolucion.dto.RegistrarDevolucionRequest;
import postventa.devolucion.dto.RegistrarDevolucionResponse;
import postventa.devolucion.dto.ResolucionResponse;
import postventa.devolucion.dto.ResolverDevolucionRequest;
import postventa.integracion.DespachoClient;
import postventa.integracion.PedidoResumen;
import postventa.integracion.ProductosClient;
import postventa.integracion.ProductosClient.ItemProducto;
import postventa.integracion.VentasClient;

/**
 * F3: registro (ESP-07) y resolución (ESP-08) de devoluciones y cambios. Endpoints 2.2 a 2.5.
 * El pedido se consulta siempre a M1 por API (VentasClient); M2 nunca escribe sobre M1.
 * Las llamadas a otros sistemas van fuera de transacción: primero se guarda el dictamen, luego se coordina.
 * Cada cambio de estado deja su registro en historial_devolucion (RNF-03), en la misma transacción que el cambio.
 */
@Slf4j
@Service
public class DevolucionService {

    static final Duration PLAZO = Duration.ofDays(7);
    static final int TAMANO_MAXIMO_PAGINA = 100;

    private final DevolucionRepository devoluciones;
    private final VentasClient ventas;
    private final ProductosClient productos;
    private final DespachoClient despacho;
    private final SolicitadorReembolso reembolsos;
    private final AlmacenEvidencias almacen;
    private final HistorialDevolucionRepository historial;
    private final ProyeccionesService proyecciones;
    private final TransactionTemplate tx;
    private final Clock reloj;

    @Autowired
    public DevolucionService(DevolucionRepository devoluciones, VentasClient ventas, ProductosClient productos,
                             DespachoClient despacho, SolicitadorReembolso reembolsos, AlmacenEvidencias almacen,
                             HistorialDevolucionRepository historial, ProyeccionesService proyecciones,
                             PlatformTransactionManager transacciones) {
        this(devoluciones, ventas, productos, despacho, reembolsos, almacen, historial, proyecciones,
                new TransactionTemplate(transacciones), Clock.systemUTC());
    }

    DevolucionService(DevolucionRepository devoluciones, VentasClient ventas, ProductosClient productos,
                      DespachoClient despacho, SolicitadorReembolso reembolsos, AlmacenEvidencias almacen,
                      HistorialDevolucionRepository historial, ProyeccionesService proyecciones,
                      TransactionTemplate tx, Clock reloj) {
        this.devoluciones = devoluciones;
        this.ventas = ventas;
        this.productos = productos;
        this.despacho = despacho;
        this.reembolsos = reembolsos;
        this.almacen = almacen;
        this.historial = historial;
        this.proyecciones = proyecciones;
        this.tx = tx;
        this.reloj = reloj;
    }

    // ---------------------------------------------------------------- 2.2 Registrar (ESP-07)

    /**
     * Orden de las validaciones: primero lo que se puede revisar sin M1 (evidencia, códigos), luego el pedido
     * (existe, titular, ENTREGADO, plazo) y por último los ítems contra lo comprado.
     * Un solo save: el expediente se guarda con sus ítems y evidencias o no se guarda (ESP-07 flujo E).
     */
    public RegistrarDevolucionResponse registrar(RegistrarDevolucionRequest solicitud, Solicitante quien) {
        validarEvidencias(solicitud);
        long idPedido = CodigoPublico.id(solicitud.pedidoId());
        Map<String, Integer> pedidas = agruparItems(solicitud.items());

        PedidoResumen pedido = ventas.consultarPedido(solicitud.pedidoId())
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "No existe el pedido " + solicitud.pedidoId()));
        if (quien.esCliente() && !quien.id().equals(pedido.clienteId())) {
            throw new AccessDeniedException("El pedido no pertenece al cliente autenticado");
        }
        if (!pedido.entregado()) {
            throw new ConflictoException("PEDIDO_NO_ENTREGADO",
                    "No es posible solicitar una devolución porque el pedido aún no ha sido entregado.");
        }
        OffsetDateTime ahora = ahora();
        if (pedido.fechaEntrega() == null || ahora.isAfter(pedido.fechaEntrega().plus(PLAZO))) {
            throw new SolicitudInvalidaException("PLAZO_VENCIDO",
                    "La devolución solo puede solicitarse dentro de los 7 días naturales desde la entrega.");
        }
        validarItems(pedido, idPedido, pedidas);

        Devolucion devolucion = new Devolucion();
        devolucion.setIdPedido(idPedido);
        devolucion.setIdCliente(pedido.clienteId());
        devolucion.setTipo(solicitud.tipo());
        devolucion.setMotivo(solicitud.motivo());
        devolucion.setDescripcion(textoOpcional(solicitud.descripcion()));
        devolucion.setFecha(ahora.toLocalDate());
        devolucion.setFechaRegistro(ahora);
        pedidas.forEach(devolucion::agregarItem);
        solicitud.evidenciasOVacio().forEach(e -> devolucion.agregarEvidencia(e.url().trim(), e.tipo()));
        devolucion.cambiarEstado(EstadoDevolucion.SOLICITADA, quien.actor(), quien.idActor(),
                "Solicitud registrada", ahora);
        Devolucion guardada = devoluciones.save(devolucion);

        log.info("Devolución {} registrada para el pedido {}", guardada.codigo(), solicitud.pedidoId());
        return new RegistrarDevolucionResponse(guardada.codigo(), solicitud.pedidoId(), guardada.getTipo(),
                guardada.getEstado(), ahora);
    }

    private void validarEvidencias(RegistrarDevolucionRequest solicitud) {
        List<RegistrarDevolucionRequest.Evidencia> evidencias = solicitud.evidenciasOVacio();
        if (solicitud.motivo().requiereEvidencia() && evidencias.isEmpty()) {
            throw new SolicitudInvalidaException("EVIDENCIA_REQUERIDA",
                    "El motivo " + solicitud.motivo() + " exige adjuntar al menos una evidencia.");
        }
        for (RegistrarDevolucionRequest.Evidencia e : evidencias) {
            if (!almacen.esPropia(e.url().trim())) {
                throw new SolicitudInvalidaException("EVIDENCIA_INVALIDA",
                        "La evidencia debe subirse primero con POST /api/v2/devoluciones/evidencias/upload");
            }
        }
    }

    /** productoId (tal cual lo da Productos) -> cantidad, sumando las líneas repetidas del mismo producto. */
    private static Map<String, Integer> agruparItems(List<RegistrarDevolucionRequest.Item> items) {
        Map<String, Integer> pedidas = new LinkedHashMap<>();
        for (RegistrarDevolucionRequest.Item item : items) {
            pedidas.merge(item.productoId().trim(), item.cantidad(), Integer::sum);
        }
        return pedidas;
    }

    /** Cada producto debe estar en el pedido y no se pueden devolver más unidades de las compradas. */
    private void validarItems(PedidoResumen pedido, long idPedido, Map<String, Integer> pedidas) {
        Map<String, Long> enCurso = new LinkedHashMap<>();
        devoluciones.unidadesEnCurso(idPedido).forEach(u -> enCurso.put(u.getIdProducto(), u.getCantidad()));
        pedidas.forEach((idProducto, cantidad) -> {
            long compradas = pedido.items().stream()
                    .filter(i -> idProducto.equals(i.productoId()))
                    .mapToLong(PedidoResumen.Item::cantidad)
                    .sum();
            if (compradas == 0) {
                throw new SolicitudInvalidaException("ITEM_INVALIDO",
                        "El producto " + idProducto + " no pertenece al pedido.");
            }
            if (cantidad > compradas) {
                throw new SolicitudInvalidaException("ITEM_INVALIDO",
                        "Se pidieron " + cantidad + " unidades de " + idProducto + " y el pedido tiene " + compradas + ".");
            }
            if (enCurso.getOrDefault(idProducto, 0L) + cantidad > compradas) {
                throw new ConflictoException("DEVOLUCION_EN_CURSO",
                        "Ya existe una devolución para esas unidades de " + idProducto + ".");
            }
        });
    }

    // ---------------------------------------------------------------- 2.3 Consultar

    /**
     * El cliente solo ve sus expedientes. Cuando el Gestor abre un expediente SOLICITADA, pasa a
     * EN_EVALUACION (F3 CA-04, ESP-08 CA-01): el contrato no tiene otro endpoint para "abrir para revisión".
     */
    @Transactional
    public DevolucionDetalle consultar(String devolucionId, Solicitante quien) {
        Devolucion d = buscar(devolucionId, false);
        if (quien.esCliente() && !quien.id().equals(d.getIdCliente())) {
            throw new AccessDeniedException("La devolución no pertenece al cliente autenticado");
        }
        if (quien.esGestor() && d.getEstado() == EstadoDevolucion.SOLICITADA) {
            d.cambiarEstado(EstadoDevolucion.EN_EVALUACION, quien.actor(), quien.idActor(),
                    "El Gestor abrió el expediente", ahora());
            log.info("Devolución {} pasa a EN_EVALUACION", d.codigo());
        }
        return detalle(d);
    }

    private DevolucionDetalle detalle(Devolucion d) {
        DevolucionDetalle.Resolucion resolucion = null;
        if (d.getResolucion() != null) {
            DevolucionDetalle.Reembolso reembolso = d.getResolucion() == TipoResolucion.REEMBOLSO
                    ? reembolsos.consultar(d.codigo()).map(DevolucionService::bloqueReembolso).orElse(null)
                    : null;
            resolucion = new DevolucionDetalle.Resolucion(
                    d.getResolucion() == TipoResolucion.RECHAZO ? DecisionDevolucion.RECHAZADA : DecisionDevolucion.APROBADA,
                    d.getResolucion(), d.getFundamentoRechazo(), d.getIdAutorizador(), d.getFechaResolucion(),
                    d.getReferenciaOperacion(), reembolso);
        }
        return new DevolucionDetalle(d.codigo(), codigoPedido(d), d.getIdCliente(), d.getTipo(), d.getEstado(),
                d.getMotivo(), d.getDescripcion(),
                d.getItems().stream().map(i -> new DevolucionDetalle.Item(i.getIdProducto(), i.getCantidad())).toList(),
                resolucion,
                d.getEvidencias().stream().map(e -> new DevolucionDetalle.Evidencia(e.getTipo(), e.getUrl())).toList(),
                d.getFechaRegistro(),
                d.getHistorial().stream().map(h -> new DevolucionDetalle.Historial(h.getEstadoAnterior(),
                        h.getEstadoNuevo(), h.getActor(), h.getIdActor(), h.getDetalle(), h.getFechaHora())).toList());
    }

    private static DevolucionDetalle.Reembolso bloqueReembolso(SolicitadorReembolso.Resultado r) {
        return new DevolucionDetalle.Reembolso(r.reembolsoId(), r.estado(), r.monto(), r.moneda(),
                r.transaccionPasarelaId(), r.fechaEjecucion());
    }

    // ---------------------------------------------------------------- 2.4 y 2.4b Listar

    @Transactional(readOnly = true)
    public ListadoDevolucionesResponse listar(UUID clienteId, EstadoDevolucion estado, TipoDevolucion tipo,
                                              int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO_PAGINA) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + TAMANO_MAXIMO_PAGINA);
        }
        Page<Devolucion> resultado = devoluciones.buscar(clienteId, estado, tipo,
                PageRequest.of(pagina, tamano, Sort.by(Sort.Direction.DESC, "id")));
        List<ListadoDevolucionesResponse.Resumen> contenido = resultado.getContent().stream()
                .map(d -> new ListadoDevolucionesResponse.Resumen(d.codigo(), codigoPedido(d), d.getTipo(),
                        d.getEstado(), d.getMotivo(), estadoReembolso(d), d.getFechaRegistro()))
                .toList();
        return new ListadoDevolucionesResponse(clienteId, contenido, pagina, resultado.getTotalPages(),
                resultado.getTotalElements());
    }

    private String estadoReembolso(Devolucion d) {
        if (d.getResolucion() != TipoResolucion.REEMBOLSO) {
            return null;
        }
        return reembolsos.consultar(d.codigo()).map(SolicitadorReembolso.Resultado::estado).orElse(null);
    }

    // ---------------------------------------------------------------- 2.5 Resolver (ESP-08)

    /**
     * RECHAZADA: exige fundamento. APROBADA: según el camino, reembolso (F4) o cambio (Productos + Despacho).
     * El expediente queda APROBADA y pasa a COMPLETADA solo cuando F4 o Despacho confirman (ESP-08 regla 11).
     * Si quedó APROBADA sin confirmar, repetir la misma aprobación reintenta la coordinación sin duplicar nada.
     */
    public ResolucionResponse resolver(String devolucionId, ResolverDevolucionRequest solicitud, Solicitante quien) {
        Devolucion d = buscar(devolucionId, true);
        UUID autorizador = autorizador(solicitud, quien);
        if (solicitud.decision() == DecisionDevolucion.RECHAZADA) {
            return rechazar(d, solicitud.fundamento(), autorizador, quien);
        }

        TipoResolucion camino = camino(d, solicitud.tipoResolucion());
        boolean reintento = d.getEstado() == EstadoDevolucion.APROBADA && d.getResolucion() == camino;
        if (!reintento && !d.getEstado().resoluble()) {
            throw noResoluble(d);
        }
        List<ItemProducto> items = d.getItems().stream()
                .map(i -> new ItemProducto(i.getIdProducto(), i.getCantidad()))
                .toList();
        // El pedido da el canal para la métrica del dashboard y el precio para el reembolso
        String pedidoId = codigoPedido(d);
        PedidoResumen pedido = ventas.consultarPedido(pedidoId)
                .orElseThrow(() -> new ConflictoException("PEDIDO_INCONSISTENTE",
                        "Ventas ya no encuentra el pedido " + pedidoId + "; la devolución no se puede resolver."));

        if (!reintento) {
            OffsetDateTime ahora = ahora();
            if (camino == TipoResolucion.CAMBIO && !productos.hayDisponibilidad(items)) {
                throw new ConflictoException("SIN_STOCK",
                        "No existe disponibilidad para realizar el cambio solicitado. Puede resolverse como reembolso o rechazo.");
            }
            EstadoDevolucion anterior = d.getEstado();
            tx.executeWithoutResult(estado -> {
                if (devoluciones.aprobar(d.getId(), camino, autorizador, ahora) == 0) {
                    throw noResoluble(d);
                }
                historial.save(HistorialDevolucion.de(d, anterior, EstadoDevolucion.APROBADA, quien.actor(),
                        quien.idActor(), "Aprobada como " + camino, ahora));
                if (pedido.canal() != null) {
                    proyecciones.devolucionAprobada(ahora, pedido.canal());
                } else {
                    log.warn("Pedido {} sin canal: la devolución {} no suma al dashboard", pedidoId, d.codigo());
                }
            });
            d.setIdAutorizador(autorizador);
            d.setFechaResolucion(ahora);
            log.info("Devolución {} APROBADA como {}", d.codigo(), camino);
            // La aprobación del Gestor confirma la recepción de la unidad devuelta (ACUERDO_G6_PRODUCTOS)
            productos.reintegrar(d.codigo(), items);
        }

        // La referencia se guarda apenas se conoce; COMPLETADA solo cuando F4 o Despacho confirman
        String referencia;
        boolean confirmada;
        if (camino == TipoResolucion.REEMBOLSO) {
            SolicitadorReembolso.Resultado r = reembolsar(d, pedido);
            referencia = r.reembolsoId();
            confirmada = r.exitoso();
        } else {
            Optional<String> despachado = despacho.programarCambio(d.codigo(), codigoPedido(d), d.getIdCliente(), items);
            referencia = despachado.orElse(null);
            confirmada = despachado.isPresent();
        }
        if (referencia != null && !referencia.equals(d.getReferenciaOperacion())) {
            devoluciones.guardarReferencia(d.getId(), referencia);
        }
        EstadoDevolucion estado = EstadoDevolucion.APROBADA;
        if (confirmada) {
            OffsetDateTime fin = ahora();
            String detalle = (camino == TipoResolucion.REEMBOLSO ? "Reembolso confirmado por F4: "
                    : "Cambio confirmado por Despacho: ") + referencia;
            tx.executeWithoutResult(t -> {
                if (devoluciones.completar(d.getId()) == 1) {
                    historial.save(HistorialDevolucion.de(d, EstadoDevolucion.APROBADA, EstadoDevolucion.COMPLETADA,
                            ActorDevolucion.SISTEMA, null, detalle, fin));
                }
            });
            estado = EstadoDevolucion.COMPLETADA;
            log.info("Devolución {} COMPLETADA ({})", d.codigo(), referencia);
        }
        return new ResolucionResponse(d.codigo(), estado, DecisionDevolucion.APROBADA, camino, null,
                d.getIdAutorizador(), d.getFechaResolucion(), referencia);
    }

    private ResolucionResponse rechazar(Devolucion d, String fundamento, UUID autorizador, Solicitante quien) {
        if (fundamento == null || fundamento.isBlank()) {
            throw new SolicitudInvalidaException("FUNDAMENTO_REQUERIDO",
                    "El rechazo exige un fundamento que lo explique.");
        }
        OffsetDateTime ahora = ahora();
        EstadoDevolucion anterior = d.getEstado();
        tx.executeWithoutResult(estado -> {
            if (devoluciones.rechazar(d.getId(), autorizador, fundamento.trim(), ahora) == 0) {
                throw noResoluble(d);
            }
            historial.save(HistorialDevolucion.de(d, anterior, EstadoDevolucion.RECHAZADA, quien.actor(),
                    quien.idActor(), fundamento.trim(), ahora));
        });
        log.info("Devolución {} RECHAZADA", d.codigo());
        return new ResolucionResponse(d.codigo(), EstadoDevolucion.RECHAZADA, DecisionDevolucion.RECHAZADA,
                TipoResolucion.RECHAZO, fundamento.trim(), autorizador, ahora, null);
    }

    /** Sin tipoResolucion se sigue lo que pidió el cliente. Un cambio puede derivarse a reembolso, no al revés. */
    private static TipoResolucion camino(Devolucion d, TipoResolucion solicitado) {
        if (solicitado == null) {
            return d.getTipo() == TipoDevolucion.CAMBIO ? TipoResolucion.CAMBIO : TipoResolucion.REEMBOLSO;
        }
        if (solicitado == TipoResolucion.RECHAZO) {
            throw new SolicitudInvalidaException("RESOLUCION_INVALIDA",
                    "Para rechazar use decision RECHAZADA con su fundamento.");
        }
        if (solicitado == TipoResolucion.CAMBIO && d.getTipo() == TipoDevolucion.DEVOLUCION_DINERO) {
            throw new SolicitudInvalidaException("RESOLUCION_INVALIDA",
                    "El cliente pidió la devolución de su dinero; no se puede resolver como cambio.");
        }
        return solicitado;
    }

    /**
     * Pide el reembolso a F4 por el valor de las unidades devueltas, con el precio del snapshot del pedido.
     * Clave de idempotencia DEVOLUCION-{id} (guía 7.5): un reintento nunca paga dos veces.
     */
    private SolicitadorReembolso.Resultado reembolsar(Devolucion d, PedidoResumen pedido) {
        String pedidoId = codigoPedido(d);
        BigDecimal monto = d.getItems().stream()
                .map(i -> precio(pedido, i.getIdProducto()).multiply(BigDecimal.valueOf(i.getCantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SolicitadorReembolso.Resultado r = reembolsos.solicitar(new SolicitadorReembolso.Solicitud(
                "DEVOLUCION-" + d.getId(), d.codigo(), pedidoId, monto, pedido.moneda(),
                "Devolución " + d.codigo() + ": " + d.getMotivo(), d.getIdAutorizador()));
        log.info("Reembolso {} de la devolución {}: {}", r.reembolsoId(), d.codigo(), r.estado());
        return r;
    }

    private static BigDecimal precio(PedidoResumen pedido, String idProducto) {
        return pedido.items().stream()
                .filter(i -> idProducto.equals(i.productoId()))
                .map(PedidoResumen.Item::precioUnitario)
                .findFirst()
                .orElseThrow(() -> new ConflictoException("PEDIDO_INCONSISTENTE",
                        "El producto " + idProducto + " ya no figura en el pedido " + pedido.pedidoId() + "."));
    }

    /** Con token, el sub del Gestor. Sin seguridad (solo local), el autorizadorId del cuerpo si es un UUID. */
    private static UUID autorizador(ResolverDevolucionRequest solicitud, Solicitante quien) {
        if (quien.id() != null) {
            return quien.id();
        }
        try {
            return solicitud.autorizadorId() == null ? null : UUID.fromString(solicitud.autorizadorId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ConflictoException noResoluble(Devolucion d) {
        return new ConflictoException("DEVOLUCION_NO_RESOLUBLE",
                "La devolución " + d.codigo() + " no puede resolverse en su estado actual.");
    }

    // ---------------------------------------------------------------- comunes

    /** DEV-AAAA-NNNN: el año también tiene que coincidir, si no es otro expediente. */
    private Devolucion buscar(String devolucionId, boolean conItems) {
        long id = CodigoPublico.id(devolucionId);
        Optional<Devolucion> encontrada = conItems ? devoluciones.findWithItemsById(id) : devoluciones.findById(id);
        return encontrada
                .filter(d -> d.codigo().equals(devolucionId.trim()))
                .orElseThrow(() -> new RecursoNoEncontradoException("DEVOLUCION_NO_ENCONTRADA",
                        "No existe la devolución " + devolucionId));
    }

    /**
     * M2 solo guarda el número del pedido (id_pedido). El año del código se toma de la fecha de la devolución:
     * coincide salvo para un pedido de fin de año devuelto en enero. CodigoPublico.id ignora el año, así que
     * la consulta a M1 funciona igual.
     */
    private static String codigoPedido(Devolucion d) {
        return CodigoPublico.pedido(d.getIdPedido(), d.getFecha().getYear());
    }

    private static String textoOpcional(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private OffsetDateTime ahora() {
        return OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
