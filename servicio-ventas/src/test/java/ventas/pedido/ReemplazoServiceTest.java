package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.ConflictoException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.compartido.SolicitudInvalidaException;
import ventas.integracion.MensajeInventario;
import ventas.integracion.ProductosClient;
import ventas.pedido.dto.CrearReemplazoRequest;

@ExtendWith(MockitoExtension.class)
class ReemplazoServiceTest {

    static final String ORIGEN = "PED-2026-00981";
    static final String DEVOLUCION = "DEV-2026-0042";

    @Mock PedidoRepository pedidos;
    @Mock MaquinaEstados maquina;
    @Mock ProductosClient productos;
    @Mock MensajesProductosService mensajesProductos;

    ReemplazoService servicio;
    Pedido origen;

    @BeforeEach
    void armar() {
        servicio = new ReemplazoService(pedidos, maquina, productos, mensajesProductos,
                TransactionOperations.withoutTransaction());
        origen = new Pedido();
        origen.setId(981L);
        origen.setCodigo(ORIGEN);
        origen.setCanal(CanalVenta.MARKETPLACE);
        origen.setEstado(EstadoPedido.ENTREGADO);
        origen.setIdCliente(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        origen.setNombreContacto("Juan Pérez Rodríguez");
        origen.setTelefono("+51999888777");
        origen.setDireccion("Av. La Marina 1234");
        origen.setDistrito("San Miguel");
        origen.setMoneda("PEN ");
        origen.setTotal(new BigDecimal("259.80"));
        DetallePedido d = new DetallePedido();
        d.setIdProducto("PROD-101");
        d.setSku("SKU-AUR-01");
        d.setDescripcion("Auriculares");
        d.setCantidad(2);
        d.setPrecioUnitario(new BigDecimal("129.90"));
        origen.setDetalles(new ArrayList<>(List.of(d)));
    }

    private static CrearReemplazoRequest pedido(String sku, int cantidad) {
        return new CrearReemplazoRequest(DEVOLUCION, ORIGEN, List.of(new CrearReemplazoRequest.Linea(sku, cantidad)));
    }

    private void origenExiste() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(origen));
    }

    private void guardarAsignaId() {
        when(pedidos.saveAndFlush(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.setId(1002L);
            return p;
        });
    }

    @Test
    void creaElReemplazoSinCobroVinculadoYReservaSuStock() {
        origenExiste();
        guardarAsignaId();
        MensajeInventario reserva = new MensajeInventario("msg-1", MensajeInventario.RESERVA_CREADA, "op", Map.of());
        when(productos.reservar(anyString(), eq("MARKETPLACE"), any())).thenReturn(Optional.of(reserva));

        ReemplazoService.Resultado r = servicio.crear(pedido("SKU-AUR-01", 1));

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidos).saveAndFlush(captor.capture());
        Pedido p = captor.getValue();
        assertThat(p.getCodigo()).isEqualTo("PED-" + java.time.Year.now(java.time.ZoneOffset.UTC).getValue() + "-01002");
        assertThat(p.esReemplazo()).isTrue();
        assertThat(p.getIdPedidoOrigen()).isEqualTo(981L);
        assertThat(p.getDevolucionId()).isEqualTo(DEVOLUCION);
        assertThat(p.getEstado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(p.getTotal()).isZero();
        assertThat(p.getMoneda()).isEqualTo("PEN");
        assertThat(p.getIdCliente()).isEqualTo(origen.getIdCliente());
        assertThat(p.getDireccion()).isEqualTo("Av. La Marina 1234");
        assertThat(p.getIdempotencyKey()).isNull();
        assertThat(p.getDetalles()).singleElement().satisfies(d -> {
            assertThat(d.getSku()).isEqualTo("SKU-AUR-01");
            assertThat(d.getCantidad()).isEqualTo(1);
            assertThat(d.getImporte()).isZero();
            assertThat(d.getIdProducto()).isEqualTo("PROD-101");
        });
        verify(maquina).registrarInicial(eq(p), eq(ActorHistorial.GESTOR), any(), any());
        verify(productos).reservar(p.getCodigo(), "MARKETPLACE", List.of(new ProductosClient.LineaReserva("SKU-AUR-01", 1)));
        verify(mensajesProductos).procesar(reserva);   // modo simulado: pasa a EN_PREPARACION
        assertThat(r.creado()).isTrue();
        assertThat(r.reemplazo().pedidoOrigenId()).isEqualTo(ORIGEN);
        assertThat(r.reemplazo().devolucionId()).isEqualTo(DEVOLUCION);
    }

    @Test
    void laMismaDevolucionDevuelveSuReemplazoVigente() {
        Pedido vigente = new Pedido();
        vigente.setCodigo("PED-2026-01002");
        vigente.setEstado(EstadoPedido.EN_PREPARACION);
        vigente.setIdPedidoOrigen(981L);
        vigente.setDevolucionId(DEVOLUCION);
        when(pedidos.findFirstByDevolucionIdAndEstadoNotOrderByIdDesc(DEVOLUCION, EstadoPedido.ANULADO))
                .thenReturn(Optional.of(vigente));
        origenExiste();

        ReemplazoService.Resultado r = servicio.crear(pedido("SKU-AUR-01", 1));

        assertThat(r.creado()).isFalse();
        assertThat(r.reemplazo().pedidoId()).isEqualTo("PED-2026-01002");
        verify(pedidos, never()).saveAndFlush(any());
        verifyNoInteractions(productos);
    }

    @Test
    void laMismaDevolucionConOtroPedidoOrigenResponde409() {
        Pedido vigente = new Pedido();
        vigente.setCodigo("PED-2026-01002");
        vigente.setIdPedidoOrigen(500L);
        when(pedidos.findFirstByDevolucionIdAndEstadoNotOrderByIdDesc(DEVOLUCION, EstadoPedido.ANULADO))
                .thenReturn(Optional.of(vigente));
        Pedido otro = new Pedido();
        otro.setCodigo("PED-2026-00500");
        when(pedidos.findById(500L)).thenReturn(Optional.of(otro));

        assertThatThrownBy(() -> servicio.crear(pedido("SKU-AUR-01", 1)))
                .isInstanceOf(ConflictoException.class).extracting("codigo").isEqualTo("REEMPLAZO_YA_EXISTE");
    }

    @Test
    void siOtraPeticionGanoLaCarreraSeDevuelveLaSuya() {
        Pedido vigente = new Pedido();
        vigente.setCodigo("PED-2026-01002");
        vigente.setIdPedidoOrigen(981L);
        when(pedidos.findFirstByDevolucionIdAndEstadoNotOrderByIdDesc(DEVOLUCION, EstadoPedido.ANULADO))
                .thenReturn(Optional.empty(), Optional.of(vigente));
        origenExiste();
        when(pedidos.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_pedido_reemplazo_activo"));

        ReemplazoService.Resultado r = servicio.crear(pedido("SKU-AUR-01", 1));

        assertThat(r.creado()).isFalse();
        assertThat(r.reemplazo().pedidoId()).isEqualTo("PED-2026-01002");
    }

    @Test
    void pedidoOrigenInexistenteResponde404() {
        assertThatThrownBy(() -> servicio.crear(pedido("SKU-AUR-01", 1))).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void soloSeReponeUnPedidoEntregadoYNoRetail() {
        origenExiste();
        origen.setEstado(EstadoPedido.DESPACHADO);
        assertThatThrownBy(() -> servicio.crear(pedido("SKU-AUR-01", 1)))
                .isInstanceOf(ConflictoException.class).extracting("codigo").isEqualTo("PEDIDO_NO_ENTREGADO");

        origen.setEstado(EstadoPedido.ENTREGADO);
        origen.setCanal(CanalVenta.RETAIL);
        assertThatThrownBy(() -> servicio.crear(pedido("SKU-AUR-01", 1)))
                .isInstanceOf(ConflictoException.class).extracting("codigo").isEqualTo("REEMPLAZO_NO_APLICA");
        verifyNoInteractions(productos);
    }

    @Test
    void elSkuDebeEstarEnElPedidoYSinPasarLoComprado() {
        origenExiste();
        assertThatThrownBy(() -> servicio.crear(pedido("SKU-OTRO", 1)))
                .isInstanceOf(SolicitudInvalidaException.class).extracting("codigo").isEqualTo("ITEM_NO_PERTENECE_AL_PEDIDO");

        // Dos líneas del mismo SKU se suman: 2 + 1 > 2 comprados
        CrearReemplazoRequest req = new CrearReemplazoRequest(DEVOLUCION, ORIGEN, List.of(
                new CrearReemplazoRequest.Linea("SKU-AUR-01", 2), new CrearReemplazoRequest.Linea("SKU-AUR-01", 1)));
        assertThatThrownBy(() -> servicio.crear(req))
                .isInstanceOf(SolicitudInvalidaException.class).extracting("codigo").isEqualTo("CANTIDAD_EXCEDE_COMPRADA");
        verify(pedidos, never()).saveAndFlush(any());
    }
}
