package postventa.reclamo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import postventa.compartido.CodigoPublico;

/**
 * Filtros de la bandeja (2.10 y 2.10b). documento, clienteId y estado son los del contrato; buscar, sla,
 * desde, hasta y orden los agrega M2 para la bandeja del mockup 07 y la propuesta UX 01. Todos son opcionales.
 *
 * buscar: un código de pedido (PED-AAAA-NNNNN) filtra por pedido; cualquier otro texto busca por código de
 * reclamo exacto, documento exacto o parte del nombre del consumidor.
 * desde / hasta: día de registro (UTC), ambos inclusivos.
 */
public record FiltroReclamos(String documento, UUID clienteId, EstadoReclamo estado, String buscar,
                             EstadoSla sla, LocalDate desde, LocalDate hasta, Orden orden) {

    public enum Orden { RECIENTES, ANTIGUOS }

    /** También lo usa ReclamoService para validar el pedidoId al registrar. */
    static final Pattern CODIGO_PEDIDO = Pattern.compile("(?i)PED-\\d{4}-\\d+");

    /** Solo los filtros del contrato (lo que usa /me y los tests). */
    public static FiltroReclamos de(String documento, UUID clienteId, EstadoReclamo estado) {
        return new FiltroReclamos(documento, clienteId, estado, null, null, null, null, null);
    }

    Sort ordenamiento() {
        Sort.Direction direccion = orden == Orden.ANTIGUOS ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direccion, "fechaRegistro", "id");
    }

    Specification<Reclamo> especificacion(LocalDate hoy) {
        return (r, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            String doc = texto(documento);
            if (doc != null) {
                condiciones.add(cb.equal(r.get("documento"), doc));
            }
            if (clienteId != null) {
                condiciones.add(cb.equal(r.get("idCliente"), clienteId));
            }
            if (estado != null) {
                condiciones.add(cb.equal(r.get("estado"), estado));
            }
            String busqueda = texto(buscar);
            if (busqueda != null) {
                if (CODIGO_PEDIDO.matcher(busqueda).matches()) {
                    condiciones.add(cb.equal(r.get("idPedido"), CodigoPublico.id(busqueda)));
                } else {
                    String patron = "%" + busqueda.toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
                            .replace("_", "\\_") + "%";
                    condiciones.add(cb.or(
                            cb.equal(cb.upper(r.get("codigo")), busqueda.toUpperCase()),
                            cb.equal(r.get("documento"), busqueda),
                            cb.like(cb.lower(r.get("nombreConsumidor")), patron, '\\')));
                }
            }
            if (sla == EstadoSla.ATENDIDO) {
                condiciones.add(cb.equal(r.get("estado"), EstadoReclamo.ATENDIDO));
            } else if (sla != null) {
                condiciones.add(cb.notEqual(r.get("estado"), EstadoReclamo.ATENDIDO));
                CalculadoraSla.RangoLimite rango = CalculadoraSla.rangoLimite(sla, hoy);
                if (rango.desde() != null) {
                    condiciones.add(cb.greaterThanOrEqualTo(r.get("fechaLimiteRespuesta"), rango.desde()));
                }
                if (rango.hasta() != null) {
                    condiciones.add(cb.lessThanOrEqualTo(r.get("fechaLimiteRespuesta"), rango.hasta()));
                }
            }
            if (desde != null) {
                condiciones.add(cb.greaterThanOrEqualTo(r.get("fechaRegistro"), inicioDelDia(desde)));
            }
            if (hasta != null) {
                condiciones.add(cb.lessThan(r.get("fechaRegistro"), inicioDelDia(hasta.plusDays(1))));
            }
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
    }

    private static OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
