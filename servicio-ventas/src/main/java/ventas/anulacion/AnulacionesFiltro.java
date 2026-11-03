package ventas.anulacion;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import ventas.pedido.CanalVenta;
import ventas.pedido.Pedido;

public final class AnulacionesFiltro {

    private AnulacionesFiltro() {}

    public static Specification<Anulacion> conFiltros(EstadoAnulacion estado, CanalVenta canal, String busqueda) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();

            if (estado != null) {
                predicate = cb.and(predicate, cb.equal(root.get("estado"), estado));
            }

            if (canal != null || (busqueda != null && !busqueda.isBlank())) {
                Join<Anulacion, Pedido> pedidoJoin = root.join("pedido", JoinType.INNER);

                if (canal != null) {
                    predicate = cb.and(predicate, cb.equal(pedidoJoin.get("canal"), canal));
                }

                if (busqueda != null && !busqueda.isBlank()) {
                    String texto = "%" + busqueda.trim().toLowerCase() + "%";
                    predicate = cb.and(predicate, cb.or(
                            cb.like(cb.lower(pedidoJoin.get("codigo")), texto),
                            cb.like(cb.lower(pedidoJoin.get("nombreContacto")), texto)
                    ));
                }
            }

            return predicate;
        };
    }
}
