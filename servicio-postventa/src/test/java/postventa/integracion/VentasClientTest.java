package postventa.integracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class VentasClientTest {

    static final UUID CLIENTE = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
    static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");

    @Test
    void laFechaDeEntregaEsLaDelHistorialEntregado() {
        VentasClient.PedidoM1 m1 = new VentasClient.PedidoM1("PED-2026-00981", "ENTREGADO",
                new VentasClient.ContactoM1(CLIENTE), new VentasClient.PagoM1(new BigDecimal("129.90"), "PEN"),
                List.of(new VentasClient.ItemM1("PROD-101", 2, new BigDecimal("64.95"))),
                OffsetDateTime.parse("2026-10-11T09:00:00Z"),
                List.of(new VentasClient.HistorialM1("`CREADO`", OffsetDateTime.parse("2026-10-01T10:00:00Z")),
                        new VentasClient.HistorialM1("ENTREGADO", OffsetDateTime.parse("2026-10-05T18:30:00Z"))));

        PedidoResumen p = VentasClient.convertir(m1);

        assertThat(p.entregado()).isTrue();
        assertThat(p.fechaEntrega()).isEqualTo(OffsetDateTime.parse("2026-10-05T18:30:00Z"));
        assertThat(p.clienteId()).isEqualTo(CLIENTE);
        assertThat(p.items()).containsExactly(new PedidoResumen.Item(101, 2, new BigDecimal("64.95")));
    }

    @Test
    void unPedidoNoEntregadoNoTieneFechaDeEntrega() {
        VentasClient.PedidoM1 m1 = new VentasClient.PedidoM1("PED-2026-00981", "`EN_PREPARACION`", null, null,
                null, null, null);

        PedidoResumen p = VentasClient.convertir(m1);

        assertThat(p.estado()).isEqualTo("EN_PREPARACION");
        assertThat(p.fechaEntrega()).isNull();
        assertThat(p.items()).isEmpty();
    }

    @Test
    void elModoSimuladoTieneCodigosParaProbarErrores() {
        VentasClient cliente = new VentasClient(true, "http://localhost:1", CLIENTE, Clock.fixed(AHORA, ZoneOffset.UTC));

        assertThat(cliente.consultarPedido("PED-2026-00404")).isEmpty();
        assertThat(cliente.consultarPedido("PED-2026-00409")).get().extracting(PedidoResumen::estado).isEqualTo("DESPACHADO");
        assertThat(cliente.consultarPedido("PED-2026-00030")).get().extracting(PedidoResumen::fechaEntrega)
                .isEqualTo(OffsetDateTime.parse("2026-09-12T15:00:00Z"));
        assertThat(cliente.consultarPedido("PED-2026-00001")).get().satisfies(p -> {
            assertThat(p.entregado()).isTrue();
            assertThat(p.clienteId()).isEqualTo(CLIENTE);
        });
    }
}
