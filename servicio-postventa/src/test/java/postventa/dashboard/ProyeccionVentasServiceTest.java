package postventa.dashboard;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import postventa.compartido.CanalVenta;
import postventa.dashboard.ProyeccionVentasService.LineaVenta;

/** ESP-15 / guía 10.4: pedido.pagado suma unidades y monto por (periodo, canal, vendedor, producto). */
@ExtendWith(MockitoExtension.class)
class ProyeccionVentasServiceTest {

    @Mock AgregadoVentasRepository ventas;
    @InjectMocks ProyeccionVentasService servicio;

    @Test
    void esp15_ca01_sumaCadaLineaEnElPeriodoUtcDelPago() {
        UUID vendedor = UUID.fromString("3f9a2b7e-6c1d-4e2a-9b3f-7a1d2c4e5f6a");
        // 30/09 23:30 en Lima ya es octubre en UTC
        servicio.pedidoPagado(OffsetDateTime.parse("2026-09-30T23:30:00-05:00"), CanalVenta.RETAIL, vendedor,
                List.of(new LineaVenta("PROD-101", 2, new BigDecimal("259.80")),
                        new LineaVenta("PROD-202", 1, new BigDecimal("49.90"))));

        verify(ventas).sumar("2026-10", CanalVenta.RETAIL, vendedor, "PROD-101", 2, new BigDecimal("259.80"));
        verify(ventas).sumar("2026-10", CanalVenta.RETAIL, vendedor, "PROD-202", 1, new BigDecimal("49.90"));
    }

    @Test
    void sinVendedorUsaElVendedorFijo() {
        servicio.pedidoPagado(OffsetDateTime.parse("2026-09-23T10:00:00Z"), CanalVenta.MARKETPLACE, null,
                List.of(new LineaVenta("PROD-101", 1, new BigDecimal("129.90"))));

        verify(ventas).sumar("2026-09", CanalVenta.MARKETPLACE, ProyeccionVentasService.SIN_VENDEDOR, "PROD-101", 1,
                new BigDecimal("129.90"));
    }

    @Test
    void sinCanalOSinItemsNoSuma() {
        OffsetDateTime fecha = OffsetDateTime.parse("2026-09-23T10:00:00Z");
        servicio.pedidoPagado(fecha, null, null, List.of(new LineaVenta("PROD-101", 1, BigDecimal.ONE)));
        servicio.pedidoPagado(fecha, CanalVenta.CHATBOT, null, null);
        servicio.pedidoPagado(fecha, CanalVenta.CHATBOT, null, List.of());

        verifyNoInteractions(ventas);
    }

    @Test
    void lineaInvalidaSeOmiteSinFrenarLasDemas() {
        servicio.pedidoPagado(OffsetDateTime.parse("2026-09-23T10:00:00Z"), CanalVenta.CHATBOT, null,
                List.of(new LineaVenta(" ", 1, BigDecimal.ONE),
                        new LineaVenta("PROD-101", 0, BigDecimal.ONE),
                        new LineaVenta("X".repeat(65), 1, BigDecimal.ONE),
                        new LineaVenta("PROD-303", 3, new BigDecimal("30.00"))));

        verify(ventas).sumar("2026-09", CanalVenta.CHATBOT, ProyeccionVentasService.SIN_VENDEDOR, "PROD-303", 3,
                new BigDecimal("30.00"));
        verifyNoMoreInteractions(ventas);
    }
}
