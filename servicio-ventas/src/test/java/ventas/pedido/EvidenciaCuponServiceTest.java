package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.compartido.RecursoNoEncontradoException;
import ventas.pedido.dto.EvidenciaCuponResponse;

@ExtendWith(MockitoExtension.class)
class EvidenciaCuponServiceTest {

    static final String RESERVA = "11111111-1111-4111-8111-111111111111";
    static final UUID CLIENTE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock PedidoRepository pedidos;
    EvidenciaCuponService servicio;
    Pedido pedido;

    @BeforeEach
    void armar() {
        servicio = new EvidenciaCuponService(pedidos);
        pedido = new Pedido();
        pedido.setCodigo("PED-2026-90020");
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setIdCliente(CLIENTE);
        pedido.setMoneda("PEN");
        pedido.setCodigoCupon("ENTRENA5");
        pedido.setReservaId(RESERVA);
        pedido.setCreadoEn(OffsetDateTime.parse("2026-11-21T11:00:00-05:00"));
        pedido.agregarDetalle(detalle("ADI-ENT22-AZU-M", "32a4172c-81b2-b643-1d0f-ae66f627f2ef", 1, "89.90"));
        org.mockito.Mockito.lenient().when(pedidos.findByCodigo("PED-2026-90020")).thenReturn(Optional.of(pedido));
    }

    private static DetallePedido detalle(String sku, String producto, int cantidad, String precio) {
        DetallePedido d = new DetallePedido();
        d.setSku(sku);
        d.setIdProducto(producto);
        d.setCantidad(cantidad);
        d.setPrecioUnitario(new BigDecimal(precio));
        return d;
    }

    @Test
    void devuelveLaFotoComercialConLosNombresDelContrato() {
        EvidenciaCuponResponse r = servicio.evidencia("PED-2026-90020");

        assertThat(r.orderId()).isEqualTo("PED-2026-90020");
        assertThat(r.commercialSnapshotRef()).isEqualTo(r.orderId());
        assertThat(r.reservationId()).isEqualTo(RESERVA);
        assertThat(r.effectiveAt()).isEqualTo(OffsetDateTime.parse("2026-11-21T16:00:00Z"));
        assertThat(r.customerRef()).isEqualTo(CLIENTE.toString());
        assertThat(r.channelId()).isEqualTo("MARKETPLACE");
        assertThat(r.couponCode()).isEqualTo("ENTRENA5");
        assertThat(r.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo("ADI-ENT22-AZU-M");
            assertThat(l.productId()).isEqualTo("32a4172c-81b2-b643-1d0f-ae66f627f2ef");
            assertThat(l.quantity()).isEqualTo(1);
            assertThat(l.currency()).isEqualTo("PEN");
            assertThat(l.regularPrice()).isEqualTo("89.9");
            assertThat(l.offerPrice()).isNull();
        });
    }

    @Test
    void consolidaLasLineasDelMismoSku() {
        pedido.agregarDetalle(detalle("ADI-ENT22-AZU-M", "32a4172c-81b2-b643-1d0f-ae66f627f2ef", 2, "89.90"));
        assertThat(servicio.evidencia("PED-2026-90020").lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualTo(3));
    }

    @Test
    void sinCuponOSinReservaRealResponde404() {
        pedido.setReservaId("SIM-PED-2026-90020");
        assertThatThrownBy(() -> servicio.evidencia("PED-2026-90020")).isInstanceOf(RecursoNoEncontradoException.class);
        pedido.setReservaId(RESERVA);
        pedido.setCodigoCupon(null);
        assertThatThrownBy(() -> servicio.evidencia("PED-2026-90020")).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void pedidoInexistenteResponde404() {
        when(pedidos.findByCodigo("PED-2026-99999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.evidencia("PED-2026-99999")).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void elPrecioVaComoTextoDecimalSinExponente() {
        assertThat(EvidenciaCuponService.precio(new BigDecimal("100.00"))).isEqualTo("100");
        assertThat(EvidenciaCuponService.precio(new BigDecimal("0.50"))).isEqualTo("0.5");
        assertThat(EvidenciaCuponService.precio(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(EvidenciaCuponService.precio(new BigDecimal("69.99"))).isEqualTo("69.99");
    }
}
