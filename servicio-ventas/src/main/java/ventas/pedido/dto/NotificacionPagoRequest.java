package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import ventas.pedido.ResultadoPasarela;

/** Cuerpo de 1.2 Notificación de pago (api-ventas.md 1.2). Lo envían la pasarela o el chatbot. */
public record NotificacionPagoRequest(
        @NotBlank @Size(max = 100) String transaccionId,   // clave de idempotencia de la notificación
        @NotNull ResultadoPasarela resultado,
        @NotNull @PositiveOrZero BigDecimal monto,
        @NotNull @Pattern(regexp = "PEN|USD") String moneda,
        @Size(max = 30) String metodo,
        @Size(max = 20) String marcaTarjeta,
        @Pattern(regexp = "\\d{4}") String ultimosCuatroDigitos,
        OffsetDateTime fechaPago,
        @Size(max = 50) String codigoAutorizacion) {}
