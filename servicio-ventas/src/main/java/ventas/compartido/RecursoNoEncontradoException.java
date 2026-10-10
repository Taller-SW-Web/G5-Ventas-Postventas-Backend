package ventas.compartido;

/** 404: el recurso no existe. */
public class RecursoNoEncontradoException extends RuntimeException {

    private final String codigo;

    public RecursoNoEncontradoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String getCodigo() { return codigo; }
}
