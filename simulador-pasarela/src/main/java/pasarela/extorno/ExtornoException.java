package pasarela.extorno;

import org.springframework.http.HttpStatus;

/**
 * Error del simulador con código y estado HTTP.
 */
public class ExtornoException extends RuntimeException {

    private final HttpStatus estado;
    private final String codigo;

    public ExtornoException(
            HttpStatus estado,
            String codigo,
            String mensaje) {

        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }

    public HttpStatus getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }
}
