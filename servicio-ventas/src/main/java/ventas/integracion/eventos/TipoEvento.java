package ventas.integracion.eventos;

import java.util.Arrays;

public enum TipoEvento {
    PEDIDO_PAGADO("pedido.pagado"),
    PEDIDO_ANULADO("pedido.anulado"),
    PEDIDO_ENTREGADO("pedido.entregado");

    private final String valor;

    TipoEvento(String valor) { this.valor = valor; }

    public String getValor() { return valor; }

    public static TipoEvento desde(String valor) {
        return Arrays.stream(values())
                .filter(t -> t.valor.equals(valor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tipo de evento desconocido: " + valor));
    }
}
