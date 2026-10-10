package ventas.integracion.eventos;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Guarda "pedido.pagado" en la columna en lugar del nombre del enum. */
@Converter
public class TipoEventoConverter implements AttributeConverter<TipoEvento, String> {

    @Override
    public String convertToDatabaseColumn(TipoEvento tipo) {
        return tipo == null ? null : tipo.getValor();
    }

    @Override
    public TipoEvento convertToEntityAttribute(String valor) {
        return valor == null ? null : TipoEvento.desde(valor);
    }
}
