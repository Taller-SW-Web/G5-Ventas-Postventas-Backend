package ventas.compartido;

/** 422: la solicitud es válida pero un módulo externo no la puede atender (p. ej. Despacho sin cobertura). */
public class NoProcesableException extends RuntimeException {

    private final String codigo;

    public NoProcesableException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String getCodigo() { return codigo; }
}
