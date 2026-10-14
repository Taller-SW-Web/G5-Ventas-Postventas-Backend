package postventa.integracion.eventos;

import java.util.Objects;

import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import postventa.compartido.SolicitudInvalidaException;

/**
 * Valida la estructura del evento y las condiciones de cada tipo.
 * No registra eventos ni modifica las proyecciones.
 */
@Component
public class ValidadorEventoPedido {

    private final Validator validator;

    public ValidadorEventoPedido(Validator validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * Debe ejecutarse antes de registrar o aplicar un evento nuevo.
     */
    public void validar(EventoPedidoPayload evento) {
        if (evento == null) {
            throw invalido("El evento es obligatorio");
        }

        if (!validator.validate(evento).isEmpty()) {
            throw invalido(
                    "El evento contiene campos obligatorios ausentes "
                            + "o valores no permitidos");
        }

        switch (evento.tipo()) {
            case "pedido.pagado" -> validarPago(evento.datos());
            case "pedido.anulado" -> validarAnulacion(evento.datos());
            case "pedido.entregado" -> {
                // La entrega no requiere motivo ni ítems.
            }
            default -> throw invalido("El tipo de evento no está permitido");
        }
    }

    private static void validarPago(EventoPedidoPayload.Datos datos) {
        if (datos.items() == null || datos.items().isEmpty()) {
            throw invalido(
                    "El evento pedido.pagado debe incluir al menos un ítem");
        }
    }

    private static void validarAnulacion(EventoPedidoPayload.Datos datos) {
        if (datos.motivo() == null || datos.motivo().isBlank()) {
            throw invalido(
                    "El evento pedido.anulado debe indicar el motivo");
        }
    }

    private static SolicitudInvalidaException invalido(String mensaje) {
        return new SolicitudInvalidaException(
                "DATOS_INVALIDOS",
                mensaje);
    }
}
