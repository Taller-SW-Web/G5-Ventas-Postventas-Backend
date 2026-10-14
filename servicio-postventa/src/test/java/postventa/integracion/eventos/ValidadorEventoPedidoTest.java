package postventa.integracion.eventos;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import postventa.compartido.CanalVenta;
import postventa.compartido.SolicitudInvalidaException;

/**
 * Verifica la estructura y las condiciones específicas de los eventos.
 * Utiliza validación real, sin arrancar Spring ni acceder a la base de datos.
 */
class ValidadorEventoPedidoTest {

    private static ValidatorFactory fabrica;
    private static ValidadorEventoPedido validador;

    @BeforeAll
    static void preparar() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = new ValidadorEventoPedido(fabrica.getValidator());
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pedido.pagado",
            "pedido.anulado",
            "pedido.entregado"
    })
    void aceptaEventosValidos(String tipo) {
        List<EventoPedidoPayload.Item> items =
                tipo.equals("pedido.pagado")
                        ? List.of(itemValido())
                        : null;

        String motivo = tipo.equals("pedido.anulado")
                ? "Solicitud del cliente"
                : null;

        EventoPedidoPayload evento = evento(tipo, motivo, items);

        assertThatCode(() -> validador.validar(evento))
                .doesNotThrowAnyException();
    }

    @Test
    void rechazaEventoNulo() {
        assertThatThrownBy(() -> validador.validar(null))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessage("El evento es obligatorio");
    }

    @Test
    void rechazaTipoDesconocido() {
        EventoPedidoPayload evento = evento(
                "pedido.desconocido",
                null,
                null);

        assertThatThrownBy(() -> validador.validar(evento))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void pagoExigeAlMenosUnItem(List<EventoPedidoPayload.Item> items) {
        EventoPedidoPayload evento = evento(
                "pedido.pagado",
                null,
                items);

        assertThatThrownBy(() -> validador.validar(evento))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessage(
                        "El evento pedido.pagado debe incluir al menos un ítem");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void anulacionExigeMotivo(String motivo) {
        EventoPedidoPayload evento = evento(
                "pedido.anulado",
                motivo,
                null);

        assertThatThrownBy(() -> validador.validar(evento))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessage(
                        "El evento pedido.anulado debe indicar el motivo");
    }

    @Test
    void rechazaItemNuloDentroDeLaLista() {
        // Arrays.asList permite construir el caso inválido con un elemento null.
        List<EventoPedidoPayload.Item> items =
                Arrays.asList((EventoPedidoPayload.Item) null);

        EventoPedidoPayload evento = evento(
                "pedido.pagado",
                null,
                items);

        assertThatThrownBy(() -> validador.validar(evento))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void rechazaCantidadCeroDentroDeUnItem() {
        EventoPedidoPayload.Item item = new EventoPedidoPayload.Item(
                "101",
                0,
                new BigDecimal("129.90"));

        EventoPedidoPayload evento = evento(
                "pedido.pagado",
                null,
                List.of(item));

        assertThatThrownBy(() -> validador.validar(evento))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    private static EventoPedidoPayload evento(
            String tipo,
            String motivo,
            List<EventoPedidoPayload.Item> items) {

        EventoPedidoPayload.Datos datos = new EventoPedidoPayload.Datos(
                CanalVenta.MARKETPLACE,
                "3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a",
                null,
                new BigDecimal("129.90"),
                "PEN",
                motivo,
                items);

        return new EventoPedidoPayload(
                UUID.fromString("3f2c9a10-6b7d-4e21-9c55-1a2b3c4d5e6f"),
                tipo,
                "PED-2026-00981",
                OffsetDateTime.parse("2026-10-14T14:00:00Z"),
                datos);
    }

    private static EventoPedidoPayload.Item itemValido() {
        return new EventoPedidoPayload.Item(
                "101",
                1,
                new BigDecimal("129.90"));
    }
}
