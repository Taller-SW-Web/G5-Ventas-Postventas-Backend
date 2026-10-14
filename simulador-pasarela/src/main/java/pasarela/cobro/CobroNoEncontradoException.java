package pasarela.cobro;

/**
 * Indica que el identificador consultado no está registrado.
 */
public class CobroNoEncontradoException extends RuntimeException {

    public CobroNoEncontradoException() {
        super("No existe la transacción indicada");
    }
}
