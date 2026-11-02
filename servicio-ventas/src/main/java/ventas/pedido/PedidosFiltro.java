package ventas.pedido;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtros de 1.4, todos opcionales. desde y hasta son fechas (UTC) inclusivas sobre creado_en.
 * canal, buscar y orden van además del contrato (bandeja del mockup 02).
 * buscar: un código PED-AAAA-NNNNN filtra por ese pedido; cualquier otro texto busca el número de documento
 * exacto o parte del nombre del contacto.
 */
final class PedidosFiltro {

    enum Orden { RECIENTES, ANTIGUOS }

    private static final Pattern CODIGO_PEDIDO = Pattern.compile("(?i)PED-\\d{4}-\\d+");

    private PedidosFiltro() {}

    static Specification<Pedido> de(UUID clienteId, EstadoPedido estado, CanalVenta canal, String buscar,
                                    LocalDate desde, LocalDate hasta) {
        Specification<Pedido> spec = (r, q, cb) -> cb.conjunction();
        if (clienteId != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("idCliente"), clienteId));
        }
        if (estado != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("estado"), estado));
        }
        if (canal != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("canal"), canal));
        }
        String texto = buscar == null || buscar.isBlank() ? null : buscar.trim();
        if (texto != null && CODIGO_PEDIDO.matcher(texto).matches()) {
            String codigo = texto.toUpperCase();
            spec = spec.and((r, q, cb) -> cb.equal(r.get("codigo"), codigo));
        } else if (texto != null) {
            String patron = "%" + texto.toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%";
            spec = spec.and((r, q, cb) -> cb.or(
                    cb.equal(r.get("numeroDocumento"), texto),
                    cb.like(cb.lower(r.get("nombreContacto")), patron, '\\')));
        }
        if (desde != null) {
            OffsetDateTime inicio = desde.atStartOfDay().atOffset(ZoneOffset.UTC);
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("creadoEn"), inicio));
        }
        if (hasta != null) {
            OffsetDateTime fin = hasta.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            spec = spec.and((r, q, cb) -> cb.lessThan(r.get("creadoEn"), fin));
        }
        return spec;
    }

    /**
     * 1.4b /pedidos/me con rango de fechas (acuerdo con G1 Marketplace, 2026-11-02): desde y hasta son días
     * inclusivos en hora de Perú (America/Lima), el mismo día que ve el cliente en la pantalla del canal.
     */
    static final ZoneId ZONA_CLIENTE = ZoneId.of("America/Lima");

    static Specification<Pedido> mios(UUID clienteId, EstadoPedido estado, LocalDate desde, LocalDate hasta) {
        Specification<Pedido> spec = (r, q, cb) -> cb.equal(r.get("idCliente"), clienteId);
        if (estado != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("estado"), estado));
        }
        if (desde != null) {
            OffsetDateTime inicio = inicioDelDia(desde);
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("creadoEn"), inicio));
        }
        if (hasta != null) {
            OffsetDateTime fin = finDelDia(hasta);
            spec = spec.and((r, q, cb) -> cb.lessThan(r.get("creadoEn"), fin));
        }
        return spec;
    }

    /** 00:00 de ese día en Perú (inclusive). */
    static OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(ZONA_CLIENTE).toOffsetDateTime();
    }

    /** 00:00 del día siguiente en Perú (exclusivo), para que hasta incluya todo su día. */
    static OffsetDateTime finDelDia(LocalDate dia) {
        return dia.plusDays(1).atStartOfDay(ZONA_CLIENTE).toOffsetDateTime();
    }

    static Sort orden(Orden orden) {
        Sort.Direction direccion = orden == Orden.ANTIGUOS ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direccion, "creadoEn", "id");
    }
}
