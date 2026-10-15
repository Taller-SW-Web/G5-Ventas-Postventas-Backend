package postventa.reclamo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import postventa.compartido.CanalVenta;

/** La bandeja (2.10) arma su consulta con FiltroReclamos (Specification): todos sus filtros son opcionales. */
public interface ReclamoRepository extends JpaRepository<Reclamo, Long>, JpaSpecificationExecutor<Reclamo> {

    Optional<Reclamo> findByCodigo(String codigo);

    /*
     * Los cambios de estado son un UPDATE condicionado al estado que se leyó: si dos Gestores responden a la vez,
     * solo uno cambia la fila (devuelve 1) y el otro recibe 0.
     */

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reclamo r
            set r.estado = postventa.reclamo.EstadoReclamo.EN_PROCESO, r.atendidoPor = :atendidoPor
            where r.id = :id and r.estado = postventa.reclamo.EstadoReclamo.REGISTRADO
            """)
    int iniciarAtencion(@Param("id") long id, @Param("atendidoPor") String atendidoPor);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reclamo r
            set r.estado = :nuevo, r.respuestaVisibleCliente = :respuesta, r.atendidoPor = :atendidoPor,
                r.fechaAtencion = :fecha
            where r.id = :id and r.estado = :actual
            """)
    int responder(@Param("id") long id, @Param("actual") EstadoReclamo actual, @Param("nuevo") EstadoReclamo nuevo,
                  @Param("respuesta") String respuesta, @Param("atendidoPor") String atendidoPor,
                  @Param("fecha") OffsetDateTime fecha);

    /** Dashboard (2.12): reclamos sin respuesta final cuyo plazo vence dentro del periodo (guía 10.5). */
    @Query("""
            select count(r) from Reclamo r
            where r.estado <> postventa.reclamo.EstadoReclamo.ATENDIDO
              and r.fechaLimiteRespuesta between :desde and :hasta
              and (:canal is null or r.canal = :canal)
            """)
    long contarPendientesSla(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta,
                             @Param("canal") CanalVenta canal);
}
