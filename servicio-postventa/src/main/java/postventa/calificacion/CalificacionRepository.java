package postventa.calificacion;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistencia de calificaciones CSAT.
 */
public interface CalificacionRepository
        extends JpaRepository<Calificacion, Long> {

    boolean existsByIdPedido(Long idPedido);

    Optional<Calificacion> findByIdPedido(Long idPedido);

    /**
     * Devuelve 1 si se registra la calificación y 0 si el pedido ya fue calificado.
     * Resuelve también solicitudes simultáneas sin provocar una excepción SQL
     * por el duplicado esperado. Otros errores de integridad se propagan.
     */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            INSERT INTO postventa.calificacion
                (id_pedido, puntaje, comentario, canal, fecha)
            VALUES (:idPedido, :puntaje, :comentario, :canal, :fecha)
            ON CONFLICT (id_pedido) DO NOTHING
            """, nativeQuery = true)
    int insertarSiNoExiste(
            @Param("idPedido") Long idPedido,
            @Param("puntaje") Integer puntaje,
            @Param("comentario") String comentario,
            @Param("canal") String canal,
            @Param("fecha") LocalDate fecha);
}