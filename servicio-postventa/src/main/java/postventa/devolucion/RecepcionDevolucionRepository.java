package postventa.devolucion;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecepcionDevolucionRepository extends JpaRepository<RecepcionDevolucion, Long> {

    /** Todas las líneas recibidas de una devolución, en orden de registro. */
    List<RecepcionDevolucion> findByDevolucionIdOrderByIdAsc(Long idDevolucion);
}
