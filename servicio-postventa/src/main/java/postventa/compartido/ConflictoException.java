package postventa.compartido;

/** 409: el estado no permite la operación, duplicado o sin stock. */
public class ConflictoException extends RuntimeException {

    private final String codigo;

    public ConflictoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String getCodigo() { return codigo; }
}
