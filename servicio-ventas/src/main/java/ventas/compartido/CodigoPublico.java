package ventas.compartido;

import java.util.UUID;

/** Conversión entre códigos públicos (PED-2026-00981) e ids numéricos. Regla 3.5 de la guía. */
public final class CodigoPublico {

    private CodigoPublico() {}

    public static String pedido(long id, int anio)     { return "PED-%d-%05d".formatted(anio, id); }
    public static String anulacion(long id)            { return "ANUL-%04d".formatted(id); }
    public static String devolucion(long id, int anio) { return "DEV-%d-%04d".formatted(anio, id); }
    public static String reembolso(long id)            { return "REEM-%05d".formatted(id); }
    public static String reclamo(long id, int anio)    { return "REC-%d-%04d".formatted(anio, id); }

    /**
     * Código provisional para guardar antes de conocer el id (codigo es NOT NULL y varchar(30)).
     * Se reemplaza por el definitivo en la misma transacción. Tiene 28 caracteres.
     */
    public static String temporal() {
        return "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /** Devuelve el id numérico de cualquier código (el número después del último guion). */
    public static long id(String codigo) {
        try {
            return Long.parseLong(codigo.substring(codigo.lastIndexOf('-') + 1));
        } catch (RuntimeException e) {
            throw new SolicitudInvalidaException("CODIGO_INVALIDO", "Código con formato inválido: " + codigo);
        }
    }
}
