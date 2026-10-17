package postventa.reembolso;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CodigoPublico;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SolicitudInvalidaException;
import postventa.integracion.PedidoResumen;
import postventa.integracion.VentasClient;
import postventa.reembolso.dto.DetalleReembolsoResponse;
import postventa.reembolso.dto.ListadoReembolsosResponse;

/**
 * Lectura de la bandeja de reembolsos (mockup 08). Separado de ReembolsoService, que solo ejecuta extornos.
 * Los códigos de pedido y de devolución toman el año de la fecha del reembolso, igual que Reclamo y Devolucion.
 */
@Service
public class ConsultaReembolsosService {

    static final int TAMANO_MAXIMO_PAGINA = 100;

    private final ReembolsoRepository repositorio;
    private final VentasClient ventas;

    public ConsultaReembolsosService(ReembolsoRepository repositorio, VentasClient ventas) {
        this.repositorio = repositorio;
        this.ventas = ventas;
    }

    /** Filtros opcionales; pedidoId es el código PED-AAAA-NNNNN. Más recientes primero. */
    @Transactional(readOnly = true)
    public ListadoReembolsosResponse listar(EstadoReembolso estado, OrigenReembolso origen, String pedidoId,
                                            int pagina, int tamano) {
        return listar(estado, origen, pedidoId, null, null, null, null, pagina, tamano);
    }

    /** Filtros de la bandeja: buscar por cÃ³digo REEM/PED, intervalo inclusivo y orden de fecha. */
    @Transactional(readOnly = true)
    public ListadoReembolsosResponse listar(EstadoReembolso estado, OrigenReembolso origen, String pedidoId,
                                            String buscar, LocalDate desde, LocalDate hasta, Orden orden,
                                            int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO_PAGINA) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + TAMANO_MAXIMO_PAGINA);
        }
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new SolicitudInvalidaException("RANGO_FECHAS_INVALIDO", "desde no puede ser posterior a hasta");
        }
        Long idPedido = pedidoId == null || pedidoId.isBlank() ? null : CodigoPublico.id(pedidoId.trim());
        Long idReembolsoBuscado = idDeCodigo(buscar, "REEM");
        Long idPedidoBuscado = idDeCodigo(buscar, "PED");
        Specification<Reembolso> filtro = (r, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (estado != null) condiciones.add(cb.equal(r.get("estado"), estado));
            if (origen != null) condiciones.add(cb.equal(r.get("tipoOrigen"), origen));
            if (idPedido != null) condiciones.add(cb.equal(r.get("idPedido"), idPedido));
            if (idReembolsoBuscado != null) condiciones.add(cb.equal(r.get("id"), idReembolsoBuscado));
            if (idPedidoBuscado != null) condiciones.add(cb.equal(r.get("idPedido"), idPedidoBuscado));
            if (desde != null) condiciones.add(cb.greaterThanOrEqualTo(r.get("fecha"), desde));
            if (hasta != null) condiciones.add(cb.lessThanOrEqualTo(r.get("fecha"), hasta));
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
        Page<Reembolso> resultado = repositorio.findAll(filtro,
                PageRequest.of(pagina, tamano, ordenar(orden)));
        return new ListadoReembolsosResponse(resultado.getContent().stream().map(ConsultaReembolsosService::resumen)
                .toList(), pagina, resultado.getTotalPages(), resultado.getTotalElements());
    }

    /** Detalle puntual: solo aquÃ­ se consulta M1 para obtener cliente, canal y mÃ©todo de pago. */
    @Transactional(readOnly = true)
    public DetalleReembolsoResponse detalle(String reembolsoId) {
        Reembolso r = repositorio.findById(CodigoPublico.id(reembolsoId.trim()))
                .orElseThrow(() -> new RecursoNoEncontradoException("REEMBOLSO_NO_ENCONTRADO", "No existe el reembolso"));
        int anio = r.getFecha().getYear();
        String pedidoId = CodigoPublico.pedido(r.getIdPedido(), anio);
        PedidoResumen pedido = ventas.consultarPedido(pedidoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO", "No existe el pedido del reembolso"));
        String origenId = r.getTipoOrigen() == OrigenReembolso.ANULACION
                ? CodigoPublico.anulacion(r.getIdOrigen()) : CodigoPublico.devolucion(r.getIdOrigen(), anio);
        List<DetalleReembolsoResponse.Intento> intentos = r.getIntentos().stream()
                .map(i -> new DetalleReembolsoResponse.Intento(i.getNumeroIntento(), i.getEstado(),
                        i.getIdTransaccion(), i.getResultado(), i.getFechaHora())).toList();
        return new DetalleReembolsoResponse(r.codigo(), pedidoId, r.getTipoOrigen(), origenId, r.getMonto(),
                r.getMoneda(), r.getEstado(), r.getIdTransaccion(), r.getFecha(), r.getIdAutorizador(),
                pedido.clienteId(), pedido.nombreCliente(), pedido.canal(), pedido.metodoPago(),
                pedido.total() != null && r.getMonto().compareTo(pedido.total()) == 0, r.getIdempotencyKey(), intentos);
    }

    private static Long idDeCodigo(String buscar, String prefijo) {
        if (buscar == null || buscar.isBlank()) return null;
        String texto = buscar.trim().toUpperCase();
        return texto.matches(prefijo + "-\\d+(?:-\\d+)?") ? CodigoPublico.id(texto) : null;
    }

    private static Sort ordenar(Orden orden) {
        return Sort.by(orden == Orden.ANTIGUOS ? Sort.Direction.ASC : Sort.Direction.DESC, "fecha", "id");
    }

    public enum Orden { RECIENTES, ANTIGUOS }

    private static ListadoReembolsosResponse.Resumen resumen(Reembolso r) {
        int anio = r.getFecha().getYear();
        List<ReembolsoIntento> intentos = r.getIntentos();
        ReembolsoIntento ultimo = intentos.isEmpty() ? null : intentos.get(intentos.size() - 1);
        String origenId = r.getTipoOrigen() == OrigenReembolso.ANULACION
                ? CodigoPublico.anulacion(r.getIdOrigen())
                : CodigoPublico.devolucion(r.getIdOrigen(), anio);
        return new ListadoReembolsosResponse.Resumen(r.codigo(), CodigoPublico.pedido(r.getIdPedido(), anio),
                r.getTipoOrigen(), origenId, r.getMonto(), r.getMoneda(), r.getEstado(), r.getIdTransaccion(),
                r.getFecha(), intentos.size(), ultimo == null ? null : ultimo.getResultado(),
                ultimo == null ? null : ultimo.getFechaHora());
    }
}
