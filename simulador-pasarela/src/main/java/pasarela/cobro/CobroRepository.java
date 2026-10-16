package pasarela.cobro;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

/**
 * Almacenamiento de cobros en memoria.
 * Los registros se pierden al reiniciar el simulador.
 */
@Repository
public class CobroRepository {

    private final ConcurrentMap<String, TransaccionCobro> transacciones =
            new ConcurrentHashMap<>();

    public void guardar(TransaccionCobro transaccion) {
        Objects.requireNonNull(transaccion);

        TransaccionCobro existente = transacciones.putIfAbsent(
                transaccion.getTransaccionId(),
                transaccion);

        if (existente != null) {
            throw new IllegalStateException(
                    "El identificador de transacción ya está registrado");
        }
    }

    public Optional<TransaccionCobro> buscar(String transaccionId) {
        Objects.requireNonNull(transaccionId);

        return Optional.ofNullable(transacciones.get(transaccionId));
    }
}
