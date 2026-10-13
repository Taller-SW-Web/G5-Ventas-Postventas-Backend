package ventas.pedido;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

/** Filtros de 1.4. desde y hasta son fechas (UTC) inclusivas sobre creado_en. */
final class PedidosFiltro {

    private PedidosFiltro() {}

    static Specification<Pedido> de(UUID clienteId, EstadoPedido estado, LocalDate desde, LocalDate hasta) {
        Specification<Pedido> spec = (r, q, cb) -> cb.equal(r.get("idCliente"), clienteId);
        if (estado != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("estado"), estado));
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
}
