package postventa.calificacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import postventa.calificacion.dto.ListadoCalificacionesResponse;
import postventa.compartido.CanalVenta;
import postventa.compartido.CodigoPublico;
import postventa.compartido.SolicitudInvalidaException;

/**
 * Lectura de calificaciones CSAT para el Gestor. El código del pedido toma el año de la fecha de la
 * calificación, igual que Reclamo y Devolucion.
 */
@Service
public class ConsultaCalificacionesService {

    static final int TAMANO_MAXIMO_PAGINA = 100;

    private final CalificacionRepository repositorio;

    public ConsultaCalificacionesService(CalificacionRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** Filtros opcionales: canal, puntaje y rango de fechas (inclusivo). Más recientes primero. */
    @Transactional(readOnly = true)
    public ListadoCalificacionesResponse listar(CanalVenta canal, Integer puntaje, LocalDate desde, LocalDate hasta,
                                                int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO_PAGINA) {
            throw new SolicitudInvalidaException("PAGINACION_INVALIDA",
                    "pagina debe ser 0 o más y tamano entre 1 y " + TAMANO_MAXIMO_PAGINA);
        }
        if (puntaje != null && (puntaje < 1 || puntaje > 5)) {
            throw new SolicitudInvalidaException("PUNTAJE_INVALIDO", "puntaje debe estar entre 1 y 5");
        }
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new SolicitudInvalidaException("RANGO_FECHAS_INVALIDO", "desde no puede ser posterior a hasta");
        }
        Specification<Calificacion> sinPuntaje = filtro(canal, null, desde, hasta);
        Page<Calificacion> resultado = repositorio.findAll(filtro(canal, puntaje, desde, hasta),
                PageRequest.of(pagina, tamano, Sort.by(Sort.Direction.DESC, "fecha", "id")));
        List<ListadoCalificacionesResponse.Item> contenido = resultado.getContent().stream()
                .map(c -> new ListadoCalificacionesResponse.Item(
                        CodigoPublico.pedido(c.getIdPedido(), c.getFecha().getYear()), c.getPuntaje(),
                        c.getComentario(), c.getCanal(), c.getFecha()))
                .toList();
        return new ListadoCalificacionesResponse(contenido, pagina, resultado.getTotalPages(),
                resultado.getTotalElements(), resumen(sinPuntaje));
    }

    /** Distribución y promedio sobre el canal y las fechas, sin el filtro de puntaje (es lo que se grafica). */
    private ListadoCalificacionesResponse.Resumen resumen(Specification<Calificacion> filtro) {
        Map<Integer, Long> distribucion = new LinkedHashMap<>();
        long total = 0;
        long suma = 0;
        for (int nota = 1; nota <= 5; nota++) {
            int n = nota;
            long cantidad = repositorio.count(filtro.and((c, q, cb) -> cb.equal(c.get("puntaje"), n)));
            distribucion.put(nota, cantidad);
            total += cantidad;
            suma += cantidad * nota;
        }
        BigDecimal promedio = total == 0 ? null
                : BigDecimal.valueOf(suma).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new ListadoCalificacionesResponse.Resumen(total, promedio, distribucion);
    }

    private static Specification<Calificacion> filtro(CanalVenta canal, Integer puntaje, LocalDate desde,
                                                      LocalDate hasta) {
        return (c, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (canal != null) condiciones.add(cb.equal(c.get("canal"), canal));
            if (puntaje != null) condiciones.add(cb.equal(c.get("puntaje"), puntaje));
            if (desde != null) condiciones.add(cb.greaterThanOrEqualTo(c.get("fecha"), desde));
            if (hasta != null) condiciones.add(cb.lessThanOrEqualTo(c.get("fecha"), hasta));
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
    }
}
