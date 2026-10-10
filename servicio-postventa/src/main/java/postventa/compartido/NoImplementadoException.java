package postventa.compartido;

/** Se lanza en los endpoints que todavía no están programados (responde 501). */
public class NoImplementadoException extends RuntimeException {

    public NoImplementadoException(String operacion) {
        super("Operación aún no implementada: " + operacion);
    }
}
