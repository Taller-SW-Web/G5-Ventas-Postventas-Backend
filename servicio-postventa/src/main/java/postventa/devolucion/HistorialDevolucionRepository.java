package postventa.devolucion;

import org.springframework.data.jpa.repository.JpaRepository;

/** Solo se insertan registros: el historial nunca se edita ni se borra. */
public interface HistorialDevolucionRepository extends JpaRepository<HistorialDevolucion, Long> {
}
