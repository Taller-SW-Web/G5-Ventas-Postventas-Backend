package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.CrearPedidoRequest;
import ventas.pedido.dto.CrearPedidoRequest.Contacto;
import ventas.pedido.dto.CrearPedidoRequest.Envio;
import ventas.pedido.dto.CrearPedidoRequest.Item;
import ventas.pedido.dto.CrearPedidoRequest.Pago;

class ValidadorPedidoTest {

    static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");
    final ValidadorPedido validador = new ValidadorPedido(Clock.fixed(AHORA, ZoneOffset.UTC));

    // ---------- Idempotency-Key ----------

    @Test
    void claveUuidV4EsValida() {
        assertThatCode(() -> validador.validarClave(UUID.randomUUID().toString())).doesNotThrowAnyException();
    }

    @Test
    void claveAusenteOMalFormadaDaCabeceraFaltante() {
        for (String clave : new String[] {null, "", "abc", "11111111-1111-1111-1111-111111111111"}) {
            assertThatThrownBy(() -> validador.validarClave(clave))
                    .isInstanceOf(SolicitudInvalidaException.class)
                    .extracting("codigo").isEqualTo("CABECERA_FALTANTE");
        }
    }

    // ---------- Documento ----------

    @ParameterizedTest(name = "{0} {1} es válido")
    @CsvSource({"DNI, 72458912", "RUC, 20123456789", "CE, AB123456", "PASAPORTE, X12345"})
    void documentosValidos(TipoDocumento tipo, String numero) {
        assertThatCode(() -> validador.validar(chatbot(contacto(tipo, numero)))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} {1} es inválido")
    @CsvSource({"DNI, 7245891", "DNI, 7245891A", "RUC, 30123456789", "CE, AB-12345", "PASAPORTE, X1234"})
    void ca02_documentoInvalidoDa400ConDetalle(TipoDocumento tipo, String numero) {
        assertThatThrownBy(() -> validador.validar(chatbot(contacto(tipo, numero))))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("DOCUMENTO_INVALIDO");
    }

    @Test
    void sinTelefonoNiEmailSeRechaza() {
        Contacto c = new Contacto(UUID.randomUUID(), "Juan", TipoDocumento.DNI, "72458912", null, " ");
        assertThatThrownBy(() -> validador.validar(chatbot(c)))
                .extracting("codigo").isEqualTo("DATOS_INVALIDOS");
    }

    // ---------- Envío ----------

    @Test
    void marketplaceSinEnvioSeRechaza() {
        CrearPedidoRequest r = new CrearPedidoRequest(CanalVenta.MARKETPLACE, contacto(TipoDocumento.DNI, "72458912"),
                items(), null, null, pago("TARJETA_CREDITO", "129.90", "10.00", "10.00", "129.90"));
        assertThatThrownBy(() -> validador.validar(r)).extracting("codigo").isEqualTo("DATOS_INVALIDOS");
    }

    @Test
    void envioSinDireccionSeRechaza() {
        Envio incompleto = new Envio("DELIVERY", new BigDecimal("10.00"), "Juan", "Lima", "Lima", "San Miguel", null, null, null);
        CrearPedidoRequest r = new CrearPedidoRequest(CanalVenta.CHATBOT, contacto(TipoDocumento.DNI, "72458912"),
                items(), null, incompleto, pago("TARJETA_CREDITO", "129.90", "10.00", "10.00", "129.90"));
        assertThatThrownBy(() -> validador.validar(r)).extracting("codigo").isEqualTo("DATOS_INVALIDOS");
    }

    // ---------- Montos ----------

    @Test
    void ejemploDelContratoEsValido() {
        assertThatCode(() -> validador.validar(chatbot(contacto(TipoDocumento.DNI, "72458912")))).doesNotThrowAnyException();
    }

    @Test
    void totalQueNoCuadraSeRechaza() {
        CrearPedidoRequest r = new CrearPedidoRequest(CanalVenta.CHATBOT, contacto(TipoDocumento.DNI, "72458912"),
                items(), null, envio(), pago("TARJETA_CREDITO", "129.90", "10.00", "10.00", "139.90"));
        assertThatThrownBy(() -> validador.validar(r)).extracting("codigo").isEqualTo("MONTOS_INCONSISTENTES");
    }

    @Test
    void subtotalDistintoALaSumaDeItemsSeRechaza() {
        CrearPedidoRequest r = new CrearPedidoRequest(CanalVenta.CHATBOT, contacto(TipoDocumento.DNI, "72458912"),
                items(), null, envio(), pago("TARJETA_CREDITO", "100.00", "0", "0", "100.00"));
        assertThatThrownBy(() -> validador.validar(r)).extracting("codigo").isEqualTo("MONTOS_INCONSISTENTES");
    }

    // ---------- Retail ----------

    @Test
    void retailEnEfectivoConTiendaEsValido() {
        assertThatCode(() -> validador.validar(retail("EFECTIVO", null, null, null, null))).doesNotThrowAnyException();
    }

    @Test
    void retailSinTiendaSeRechaza() {
        CrearPedidoRequest r = new CrearPedidoRequest(CanalVenta.RETAIL, contacto(TipoDocumento.DNI, "72458912"),
                items(), null, null, pago("EFECTIVO", "129.90", "0", "0", "129.90"));
        assertThatThrownBy(() -> validador.validar(r)).extracting("codigo").isEqualTo("DATOS_INVALIDOS");
    }

    @Test
    void retailConTarjetaSinReferenciaSeRechaza() {
        assertThatThrownBy(() -> validador.validar(retail("TARJETA_POS", null, null, null, null)))
                .extracting("codigo").isEqualTo("DATOS_INVALIDOS");
    }

    @Test
    void retailConMetodoDeOtroCanalSeRechaza() {
        assertThatThrownBy(() -> validador.validar(retail("TARJETA_CREDITO", "VOU-1", null, null, null)))
                .extracting("codigo").isEqualTo("DATOS_INVALIDOS");
    }

    @Test
    void retailMixtoDebeSumarElTotal() {
        assertThatCode(() -> validador.validar(retail("MIXTO", "VOU-1", "29.90", "100.00", null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validador.validar(retail("MIXTO", "VOU-1", "20.00", "100.00", null)))
                .extracting("codigo").isEqualTo("MONTOS_INCONSISTENTES");
    }

    @Test
    void fechaVentaDeHace73HorasOFuturaSeRechaza() {
        OffsetDateTime ahora = OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC);
        assertThatCode(() -> validador.validar(retail("EFECTIVO", null, null, null, ahora.minusHours(71)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validador.validar(retail("EFECTIVO", null, null, null, ahora.minusHours(73))))
                .extracting("codigo").isEqualTo("FECHA_VENTA_FUERA_DE_RANGO");
        assertThatThrownBy(() -> validador.validar(retail("EFECTIVO", null, null, null, ahora.plusHours(1))))
                .extracting("codigo").isEqualTo("FECHA_VENTA_FUERA_DE_RANGO");
    }

    // ---------- datos de prueba (ejemplo de api-ventas.md 1.1) ----------

    static Contacto contacto(TipoDocumento tipo, String numero) {
        return new Contacto(UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a"), "Juan Pérez Rodríguez",
                tipo, numero, "+51999888777", "juan.perez@example.com");
    }

    static List<Item> items() {
        return List.of(new Item("PROD-101", "SKU-AUR-01", "Audífonos", 1, new BigDecimal("129.90")));
    }

    static Envio envio() {
        return new Envio("DELIVERY", new BigDecimal("10.00"), "Juan Pérez", "Lima", "Lima", "San Miguel",
                "Av. La Marina 1234", "Frente al centro comercial", null);
    }

    static Pago pago(String metodo, String subtotal, String descuento, String envio, String total) {
        return new Pago(metodo, "PEN", new BigDecimal(subtotal), new BigDecimal(descuento), new BigDecimal(envio),
                new BigDecimal(total), null, null, null, null);
    }

    static CrearPedidoRequest chatbot(Contacto c) {
        return new CrearPedidoRequest(CanalVenta.CHATBOT, c, items(), null, envio(),
                pago("TARJETA_CREDITO", "129.90", "10.00", "10.00", "129.90"));
    }

    static CrearPedidoRequest retail(String metodo, String referencia, String efectivo, String tarjeta, OffsetDateTime fechaVenta) {
        Pago p = new Pago(metodo, "PEN", new BigDecimal("129.90"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("129.90"), referencia,
                efectivo == null ? null : new BigDecimal(efectivo), tarjeta == null ? null : new BigDecimal(tarjeta), fechaVenta);
        Envio tienda = new Envio(null, null, null, null, null, null, null, null, "TIENDA-MIRAFLORES");
        return new CrearPedidoRequest(CanalVenta.RETAIL, contacto(TipoDocumento.DNI, "72458912"), items(), null, tienda, p);
    }
}
