package postventa.reembolso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import postventa.compartido.CanalVenta;
import postventa.integracion.PedidoResumen;
import postventa.integracion.VentasClient;

/** La lista no consulta M1; el detalle sÃ­ obtiene sus datos enriquecidos en una sola llamada. */
@ExtendWith(MockitoExtension.class)
class ConsultaReembolsosServiceTest {

    @Mock ReembolsoRepository repositorio;
    @Mock VentasClient ventas;

    @Test
    void laListaNoConsultaVentasPorCadaFila() {
        Reembolso r = reembolso();
        when(repositorio.findAll(org.mockito.ArgumentMatchers.<Specification<Reembolso>>any(),
                org.mockito.ArgumentMatchers.any(Pageable.class))).thenReturn(new PageImpl<>(List.of(r)));

        var respuesta = new ConsultaReembolsosService(repositorio, ventas)
                .listar(null, null, null, null, null, null, null, 0, 10);

        assertThat(respuesta.contenido()).singleElement().satisfies(fila -> {
            assertThat(fila.reembolsoId()).isEqualTo("REEM-00094");
            assertThat(fila.pedidoId()).isEqualTo("PED-2026-01053");
        });
        verify(ventas, never()).consultarPedido(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void elDetalleTraeClienteCanalYMetodoDePagoDesdeM1() {
        Reembolso r = reembolso();
        when(repositorio.findById(94L)).thenReturn(Optional.of(r));
        UUID cliente = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(ventas.consultarPedido("PED-2026-01053")).thenReturn(Optional.of(new PedidoResumen(
                "PED-2026-01053", "ANULADO", cliente, new BigDecimal("214.00"), "PEN", List.of(), null,
                true, CanalVenta.MARKETPLACE, "Ana LÃ³pez", "TARJETA")));

        var detalle = new ConsultaReembolsosService(repositorio, ventas).detalle("REEM-00094");

        assertThat(detalle.clienteNombre()).isEqualTo("Ana LÃ³pez");
        assertThat(detalle.canal()).isEqualTo(CanalVenta.MARKETPLACE);
        assertThat(detalle.metodoPago()).isEqualTo("TARJETA");
        assertThat(detalle.transaccionPasarelaId()).isEqualTo("OP-99123");
        assertThat(detalle.total()).isTrue();
    }

    private static Reembolso reembolso() {
        Reembolso r = new Reembolso();
        r.setId(94L);
        r.setIdPedido(1053L);
        r.setIdOrigen(12L);
        r.setTipoOrigen(OrigenReembolso.ANULACION);
        r.setMonto(new BigDecimal("214.00"));
        r.setMoneda("PEN");
        r.setEstado(EstadoReembolso.EXITOSO);
        r.setIdTransaccion("OP-99123");
        r.setIdempotencyKey("clave-original");
        r.setFecha(LocalDate.of(2026, 10, 10));
        return r;
    }
}
