package ventas.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ventas.compartido.ErrorResponse.Detalle;
import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoRequest.Pago;

/**
 * Reglas de 1.1 que no se pueden expresar con anotaciones (ESP-01, secciones 9 y 10).
 * Si algo falla, lanza 400 y el pedido no se crea (la Idempotency-Key tampoco se guarda).
 */
@Component
public class ValidadorPedido {

    static final Duration ANTIGUEDAD_MAXIMA_VENTA = Duration.ofHours(72);
    private static final Set<String> METODOS_RETAIL = Set.of("EFECTIVO", "TARJETA_POS", "MIXTO");

    private final Clock reloj;

    public ValidadorPedido() {
        this(Clock.systemUTC());
    }

    ValidadorPedido(Clock reloj) {
        this.reloj = reloj;
    }

    /** Acuerdo H-02: Idempotency-Key obligatoria, UUID v4, máximo 128 caracteres. */
    public void validarClave(String clave) {
        boolean valida = false;
        if (clave != null && !clave.isBlank() && clave.length() <= 128) {
            try {
                valida = UUID.fromString(clave).version() == 4;
            } catch (IllegalArgumentException e) {
                valida = false;
            }
        }
        if (!valida) {
            throw new SolicitudInvalidaException("CABECERA_FALTANTE",
                    "La cabecera Idempotency-Key es obligatoria y debe ser un UUID v4");
        }
    }

    public void validar(CrearPedidoRequest req) {
        validarDocumento(req.contacto());
        validarContacto(req.contacto());
        if (req.canal() == CanalVenta.RETAIL) {
            validarRetail(req);
        } else {
            validarEnvio(req.envio());
        }
        validarMontos(req);
    }

    private void validarDocumento(CrearPedidoRequest.Contacto c) {
        if (!c.tipoDocumento().esValido(c.numeroDocumento())) {
            throw new SolicitudInvalidaException("DOCUMENTO_INVALIDO",
                    "El número de documento no cumple con el formato requerido para el tipo indicado",
                    List.of(new Detalle("contacto.numeroDocumento", c.tipoDocumento().regla())));
        }
    }

    private void validarContacto(CrearPedidoRequest.Contacto c) {
        if (vacio(c.telefono()) && vacio(c.email())) {
            throw invalido("contacto", "Se requiere al menos telefono o email");
        }
    }

    /** Marketplace y Chatbot: envío a domicilio con dirección completa. */
    private void validarEnvio(CrearPedidoRequest.Envio e) {
        if (e == null) {
            throw invalido("envio", "El bloque envio es obligatorio para este canal");
        }
        List<Detalle> faltan = new ArrayList<>();
        if (vacio(e.direccion())) faltan.add(new Detalle("envio.direccion", "es obligatorio"));
        if (vacio(e.distrito())) faltan.add(new Detalle("envio.distrito", "es obligatorio"));
        if (vacio(e.destinatario())) faltan.add(new Detalle("envio.destinatario", "es obligatorio"));
        if (!faltan.isEmpty()) {
            throw new SolicitudInvalidaException("DATOS_INVALIDOS", "El bloque envio está incompleto", faltan);
        }
    }

    /** Retail (acuerdo G4): tiendaId en vez de dirección, datos del cobro del POS y fechaVenta opcional. */
    private void validarRetail(CrearPedidoRequest req) {
        if (req.envio() == null || vacio(req.envio().tiendaId())) {
            throw invalido("envio.tiendaId", "Para RETAIL es obligatorio el código de la tienda");
        }
        Pago pago = req.pago();
        String metodo = pago.metodoPago();
        if (!METODOS_RETAIL.contains(metodo)) {
            throw invalido("pago.metodoPago", "Para RETAIL debe ser EFECTIVO, TARJETA_POS o MIXTO");
        }
        if (!metodo.equals("EFECTIVO") && vacio(pago.referenciaOperacion())) {
            throw invalido("pago.referenciaOperacion", "Es obligatorio con TARJETA_POS o MIXTO");
        }
        if (metodo.equals("MIXTO")) {
            if (pago.montoEfectivo() == null || pago.montoTarjeta() == null) {
                throw invalido("pago.montoEfectivo", "Con MIXTO se requieren montoEfectivo y montoTarjeta");
            }
            if (dinero(pago.montoEfectivo().add(pago.montoTarjeta())).compareTo(dinero(pago.total())) != 0) {
                throw new SolicitudInvalidaException("MONTOS_INCONSISTENTES",
                        "montoEfectivo + montoTarjeta debe ser igual a total",
                        List.of(new Detalle("pago.total", "esperado " + pago.montoEfectivo().add(pago.montoTarjeta()))));
            }
        }
        if (pago.fechaVenta() != null) {
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            if (pago.fechaVenta().isAfter(ahora) || pago.fechaVenta().isBefore(ahora.minus(ANTIGUEDAD_MAXIMA_VENTA))) {
                throw new SolicitudInvalidaException("FECHA_VENTA_FUERA_DE_RANGO",
                        "fechaVenta no puede ser futura ni tener más de 72 horas",
                        List.of(new Detalle("pago.fechaVenta", "fuera del rango permitido")));
            }
        }
    }

    /** subtotal = suma de ítems; total = subtotal - descuentoCupon + costoEnvio. */
    private void validarMontos(CrearPedidoRequest req) {
        Pago pago = req.pago();
        BigDecimal sumaItems = req.items().stream()
                .map(i -> i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dinero(sumaItems).compareTo(dinero(pago.subtotal())) != 0) {
            throw new SolicitudInvalidaException("MONTOS_INCONSISTENTES",
                    "El subtotal no coincide con la suma de los ítems",
                    List.of(new Detalle("pago.subtotal", "esperado " + dinero(sumaItems))));
        }
        BigDecimal esperado = pago.subtotal().subtract(cero(pago.descuentoCupon())).add(cero(pago.costoEnvio()));
        if (dinero(esperado).compareTo(dinero(pago.total())) != 0) {
            throw new SolicitudInvalidaException("MONTOS_INCONSISTENTES",
                    "El total no cumple total = subtotal - descuentoCupon + costoEnvio",
                    List.of(new Detalle("pago.total", "esperado " + dinero(esperado))));
        }
    }

    private static SolicitudInvalidaException invalido(String campo, String error) {
        return new SolicitudInvalidaException("DATOS_INVALIDOS", "La solicitud tiene campos inválidos",
                List.of(new Detalle(campo, error)));
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static BigDecimal cero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal dinero(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
