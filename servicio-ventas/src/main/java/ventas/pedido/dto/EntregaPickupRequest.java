package ventas.pedido.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de confirmación de entrega física en mostrador (RF-19 de Retail / F1).
 */
public record EntregaPickupRequest(
        @NotBlank(message = "El DNI de quien recoge es obligatorio")
        @Pattern(regexp = "^\\d{8}$", message = "El DNI debe tener exactamente 8 dígitos")
        String dniRecoge,

        @NotBlank(message = "El nombre de quien recoge es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
        String nombreRecoge,

        boolean esTerceroAutorizado,

        String codigoRetiro) {}
