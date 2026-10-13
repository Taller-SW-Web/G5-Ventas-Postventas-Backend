package ventas.pedido.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import ventas.pedido.CanalVenta;
import ventas.pedido.TipoDocumento;

/** Cuerpo de 1.1 Crear pedido. Los nombres son los del contrato (api-ventas.md 1.1). */
public record CrearPedidoRequest(
        @NotNull CanalVenta canal,
        @NotNull @Valid Contacto contacto,
        @NotEmpty List<@NotNull @Valid Item> items,
        @Valid Cupon cupon,
        @Valid Envio envio,          // obligatorio salvo RETAIL (lo revisa ValidadorPedido)
        @NotNull @Valid Pago pago) {

    public record Contacto(
            @NotNull UUID clienteId,  // sub del cliente en Seguridad
            @NotBlank @Size(max = 150) String nombreCompleto,
            @NotNull TipoDocumento tipoDocumento,
            @NotBlank String numeroDocumento,
            @Size(max = 20) String telefono,
            @Email @Size(max = 150) String email) {}

    public record Item(
            @NotBlank String productoId,
            @NotBlank @Size(max = 50) String sku,
            @Size(max = 255) String descripcion,
            @NotNull @Positive Integer cantidad,
            @NotNull @PositiveOrZero BigDecimal precioUnitario) {}

    public record Cupon(
            @NotBlank @Size(max = 40) String codigo,
            @NotNull @PositiveOrZero BigDecimal descuento,
            Boolean aplicado) {}

    public record Envio(
            @Size(max = 30) String modalidad,
            @PositiveOrZero BigDecimal costo,
            @Size(max = 150) String destinatario,
            @Size(max = 80) String departamento,
            @Size(max = 80) String provincia,
            @Size(max = 80) String distrito,
            @Size(max = 255) String direccion,
            @Size(max = 255) String referencia,
            @Size(max = 60) String tiendaId) {}   // solo RETAIL (acuerdo G4, R5)

    public record Pago(
            @NotBlank @Size(max = 30) String metodoPago,
            @NotNull @Pattern(regexp = "PEN|USD") String moneda,
            @NotNull @PositiveOrZero BigDecimal subtotal,
            @PositiveOrZero BigDecimal descuentoCupon,
            @PositiveOrZero BigDecimal costoEnvio,
            @NotNull @PositiveOrZero BigDecimal total,
            @Size(max = 100) String referenciaOperacion,   // RETAIL con TARJETA_POS o MIXTO
            @PositiveOrZero BigDecimal montoEfectivo,      // RETAIL con MIXTO
            @PositiveOrZero BigDecimal montoTarjeta,       // RETAIL con MIXTO
            OffsetDateTime fechaVenta) {}                  // RETAIL sin conexión, máx. 72 h
}
