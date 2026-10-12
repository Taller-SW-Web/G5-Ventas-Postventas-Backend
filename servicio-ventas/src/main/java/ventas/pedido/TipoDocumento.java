package ventas.pedido;

import java.util.regex.Pattern;

/** Tipos de documento de contacto.tipoDocumento (api-ventas.md 1.1, auditoría A8). */
public enum TipoDocumento {
    DNI("^[0-9]{8}$", "se requieren exactamente 8 dígitos numéricos"),
    RUC("^(10|15|17|20)[0-9]{9}$", "se requieren 11 dígitos que empiecen en 10, 15, 17 o 20"),
    CE("^[a-zA-Z0-9]{8,12}$", "se requieren de 8 a 12 caracteres alfanuméricos"),
    PASAPORTE("^[a-zA-Z0-9]{6,12}$", "se requieren de 6 a 12 caracteres alfanuméricos");

    private final Pattern formato;
    private final String regla;

    TipoDocumento(String regex, String regla) {
        this.formato = Pattern.compile(regex);
        this.regla = regla;
    }

    public boolean esValido(String numero) {
        return numero != null && formato.matcher(numero).matches();
    }

    public String regla() {
        return "Para tipoDocumento '" + name() + "' " + regla;
    }
}
