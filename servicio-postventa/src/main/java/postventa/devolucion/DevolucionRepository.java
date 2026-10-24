package postventa.devolucion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DevolucionRepository extends JpaRepository<Devolucion, Long> {

    /** Unidades de un producto ya incluidas en devoluciones del pedido que no fueron rechazadas. */
    interface UnidadesDevueltas {
        String getIdProducto();
        Long getCantidad();
    }

    @Query("""
            select d from Devolucion d
            where (:idCliente is null or d.idCliente = :idCliente)
              and (:estado is null or d.estado = :estado)
              and (:tipo is null or d.tipo = :tipo)
            """)
    Page<Devolucion> buscar(@Param("idCliente") UUID idCliente,
                            @Param("estado") EstadoDevolucion estado,
                            @Param("tipo") TipoDevolucion tipo,
                            Pageable pagina);

    @Query("""
            select i.idProducto as idProducto, sum(i.cantidad) as cantidad
            from DevolucionItem i
            where i.devolucion.idPedido = :idPedido
              and i.devolucion.estado <> postventa.devolucion.EstadoDevolucion.RECHAZADA
            group by i.idProducto
            """)
    List<UnidadesDevueltas> unidadesEnCurso(@Param("idPedido") long idPedido);

    /** Con los ítems ya cargados, para usarlos fuera de una transacción. */
    @EntityGraph(attributePaths = "items")
    Optional<Devolucion> findWithItemsById(Long id);

    /*
     * Las transiciones del dictamen son un UPDATE condicionado al estado: si dos Gestores resuelven a la vez,
     * solo uno cambia la fila (devuelve 1) y el otro recibe 0. Así un expediente nunca queda con cambio y
     * reembolso a la vez.
     */

    @Transactional
    @Modifying
    @Query("""
            update Devolucion d
            set d.estado = postventa.devolucion.EstadoDevolucion.APROBADA,
                d.resolucion = :resolucion, d.idAutorizador = :autorizador, d.fechaResolucion = :fecha,
                d.requiereDevolucionFisica = :requiereDevolucionFisica
            where d.id = :id
              and d.estado in (postventa.devolucion.EstadoDevolucion.SOLICITADA,
                               postventa.devolucion.EstadoDevolucion.EN_EVALUACION)
            """)
    int aprobar(@Param("id") long id, @Param("resolucion") TipoResolucion resolucion,
                @Param("autorizador") UUID autorizador, @Param("fecha") OffsetDateTime fecha,
                @Param("requiereDevolucionFisica") boolean requiereDevolucionFisica);

    @Transactional
    @Modifying
    @Query("""
            update Devolucion d
            set d.estado = postventa.devolucion.EstadoDevolucion.RECHAZADA,
                d.resolucion = postventa.devolucion.TipoResolucion.RECHAZO,
                d.idAutorizador = :autorizador, d.fundamentoRechazo = :fundamento,
                d.fechaResolucion = :fecha
            where d.id = :id
              and d.estado in (postventa.devolucion.EstadoDevolucion.SOLICITADA,
                               postventa.devolucion.EstadoDevolucion.EN_EVALUACION)
            """)
    int rechazar(@Param("id") long id, @Param("autorizador") UUID autorizador,
                 @Param("fundamento") String fundamento, @Param("fecha") OffsetDateTime fecha);

    /** reembolsoId de F4 o referencia de Despacho, apenas se conoce (aunque todavía no esté confirmada). */
    @Transactional
    @Modifying
    @Query("update Devolucion d set d.referenciaOperacion = :referencia where d.id = :id")
    int guardarReferencia(@Param("id") long id, @Param("referencia") String referencia);

    @Transactional
    @Modifying
    @Query("""
            update Devolucion d
            set d.estado = postventa.devolucion.EstadoDevolucion.COMPLETADA
            where d.id = :id and d.estado = postventa.devolucion.EstadoDevolucion.APROBADA
            """)
    int completar(@Param("id") long id);

    /**
     * M1 anuló la orden de reemplazo (reserva vencida o sin stock, entrega fallida, Gestor): el cambio aprobado vuelve
     * a evaluación para que el Gestor lo apruebe otra vez (otro reemplazo con reserva nueva) o lo derive a reembolso.
     * Solo si la referencia sigue siendo ese reemplazo (un aviso viejo no reabre un cambio ya rehecho).
     */
    @Transactional
    @Modifying
    @Query("""
            update Devolucion d
            set d.estado = postventa.devolucion.EstadoDevolucion.EN_EVALUACION,
                d.resolucion = null, d.referenciaOperacion = null
            where d.id = :id
              and d.estado = postventa.devolucion.EstadoDevolucion.APROBADA
              and d.resolucion = postventa.devolucion.TipoResolucion.CAMBIO
              and d.referenciaOperacion = :referencia
            """)
    int reabrirCambio(@Param("id") long id, @Param("referencia") String referencia);
}
