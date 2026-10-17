package postventa.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import postventa.compartido.CanalVenta;

/** ESP-15 CA-01: cada venta suma sobre la fila (periodo, canal, vendedor, producto) sin pisar a otra. */
@ExtendWith(MockitoExtension.class)
class AgregadoVentasRepositoryTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks AgregadoVentasRepository repositorio;

    @Test
    void esp15_ca01_sumarHaceUpsertDeUnidadesYMonto() {
        BigDecimal monto = new BigDecimal("129.90");
        repositorio.sumar("2026-10", CanalVenta.MARKETPLACE, ProyeccionVentasService.SIN_VENDEDOR, "PROD-101", 1,
                monto);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("2026-10"), eq("MARKETPLACE"), eq(ProyeccionVentasService.SIN_VENDEDOR),
                eq("PROD-101"), eq(1), eq(monto));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (periodo, canal, id_vendedor, id_producto) DO UPDATE")
                .contains("unidades = postventa.agregado_ventas.unidades + EXCLUDED.unidades")
                .contains("monto = postventa.agregado_ventas.monto + EXCLUDED.monto");
    }
}
