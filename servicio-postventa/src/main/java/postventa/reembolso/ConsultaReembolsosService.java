package postventa.reembolso;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.compartido.CodigoPublico;
import postventa.compartido.SolicitudInvalidaException;
import postventa.reembolso.dto.ListadoReembolsosResponse;

/**
 * Lectura de la bandeja de reembolsos (mockup 08). Separado de ReembolsoService, que solo ejecuta extornos.
 * Los códigos de pedido y de devolución toman el año de la fecha del reembolso, igual que Reclamo y Devolucion.
 */
@Service
public class ConsultaReembolsosService {

    static final int TAMANO_MAXIMO_PAGINA = 100;

    private final ReembolsoRepository repositorio;

    public ConsultaReembolsosService(ReembolsoRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** Filtros opcionales; pedidoId es el código PED-AAAA-NNNNN. Más recientes primero. */
    @Transactional(readOnly = true)
    public ListadoReembolsosResponse listar(EstadoReembolso estado, OrigenReembolso origen, String pedidoId,
                                            int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO_PAGINA) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + TAMANO_MAXIMO_PAGINA);
        }
        Long idPedido = pedidoId == null || pedidoId.isBlank() ? null : CodigoPublico.id(pedidoId.trim());
        Specification<Reembolso> filtro = (r, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (estado != null) condiciones.add(cb.equal(r.get("estado"), estado));
            if (origen != null) condiciones.add(cb.equal(r.get("tipoOrigen"), origen));
            if (idPedido != null) condiciones.add(cb.equal(r.get("idPedido"), idPedido));
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
        Page<Reembolso> resultado = repositorio.findAll(filtro,
                PageRequest.of(pagina, tamano, Sort.by(Sort.Direction.DESC, "fecha", "id")));
        return new ListadoReembolsosResponse(resultado.getContent().stream().map(ConsultaReembolsosService::resumen)
                .toList(), pagina, resultado.getTotalPages(), resultado.getTotalElements());
    }

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
