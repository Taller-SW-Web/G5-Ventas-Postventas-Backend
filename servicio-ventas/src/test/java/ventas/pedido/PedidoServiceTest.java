package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import ventas.compartido.SolicitudInvalidaException;
import ventas.pedido.dto.ListadoPedidosResponse;
import ventas.pedido.dto.MisPedidosResponse;

@ExtendWith(MockitoExtension.class)
class PedidoServiceTest {

    private static final UUID CLIENTE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock PedidoRepository pedidos;
    @InjectMocks PedidoService servicio;

    @Test
    void listaLosPedidosDelClienteConElFormatoDeH03() {
        Page<Pedido> pagina = new PageImpl<>(List.of(pedido()), Pageable.ofSize(10), 1);
        when(pedidos.findByIdCliente(eq(CLIENTE), any(Pageable.class))).thenReturn(pagina);

        MisPedidosResponse r = servicio.listarMios(CLIENTE, null, 1, 10);

        assertThat(r.total()).isEqualTo(1);
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.pageSize()).isEqualTo(10);
        assertThat(r.items()).singleElement().satisfies(p -> {
            assertThat(p.pedidoId()).isEqualTo("PED-2026-00981");
            assertThat(p.estado()).isEqualTo(EstadoPedido.EN_PREPARACION);
        });
    }

    @Test
    void pageEmpiezaEnUnoYSeConvierteAPaginaCeroDeSpring() {
        when(pedidos.findByIdCliente(eq(CLIENTE), any(Pageable.class))).thenReturn(Page.empty());
        servicio.listarMios(CLIENTE, null, 3, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pedidos).findByIdCliente(eq(CLIENTE), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void sinComprasDevuelveListaVacia() {
        when(pedidos.findByIdCliente(eq(CLIENTE), any(Pageable.class))).thenReturn(Page.empty());
        MisPedidosResponse r = servicio.listarMios(CLIENTE, null, 1, 10);
        assertThat(r.items()).isEmpty();
        assertThat(r.total()).isZero();
    }

    @Test
    void filtraPorEstado() {
        when(pedidos.findByIdClienteAndEstado(eq(CLIENTE), eq(EstadoPedido.PAGADO), any(Pageable.class)))
                .thenReturn(Page.empty());
        servicio.listarMios(CLIENTE, EstadoPedido.PAGADO, 1, 10);
        verify(pedidos).findByIdClienteAndEstado(eq(CLIENTE), eq(EstadoPedido.PAGADO), any(Pageable.class));
    }

    @Test
    void rechazaPageMenorAUnoYPageSizeMayorA50() {
        assertThatThrownBy(() -> servicio.listarMios(CLIENTE, null, 0, 10))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listarMios(CLIENTE, null, 1, 51))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
    }

    // ---------- 1.4 Listar pedidos (Gestor) ----------

    @Test
    void listarDevuelveElFormatoDelContrato() {
        when(pedidos.findAll(ArgumentMatchers.<Specification<Pedido>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pedido()), PageRequest.of(0, 10), 1));

        ListadoPedidosResponse r = servicio.listar(CLIENTE, null, null, null, 0, 10);

        assertThat(r.clienteId()).isEqualTo(CLIENTE);
        assertThat(r.pagina()).isZero();
        assertThat(r.totalPaginas()).isEqualTo(1);
        assertThat(r.totalElementos()).isEqualTo(1);
        assertThat(r.contenido()).singleElement().satisfies(p -> assertThat(p.pedidoId()).isEqualTo("PED-2026-00981"));
    }

    @Test
    void listarPaginaDesdeCeroYOrdenaPorFechaDescendente() {
        when(pedidos.findAll(ArgumentMatchers.<Specification<Pedido>>any(), any(Pageable.class))).thenReturn(Page.empty());
        servicio.listar(CLIENTE, EstadoPedido.PAGADO, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13), 2, 25);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pedidos).findAll(ArgumentMatchers.<Specification<Pedido>>any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(25);
        assertThat(captor.getValue().getSort().getOrderFor("creadoEn").isDescending()).isTrue();
    }

    @Test
    void listarRechazaParametrosInvalidos() {
        assertThatThrownBy(() -> servicio.listar(null, null, null, null, 0, 10))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PARAMETRO_FALTANTE");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, -1, 10))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, 0, 0))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, 0, 51))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), 0, 10))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        verifyNoInteractions(pedidos);
    }

    private static Pedido pedido() {
        Pedido p = new Pedido();
        p.setId(981L);
        p.setCodigo("PED-2026-00981");
        p.setCanal(CanalVenta.CHATBOT);
        p.setEstado(EstadoPedido.EN_PREPARACION);
        p.setIdCliente(CLIENTE);
        p.setMoneda("PEN");
        p.setTotal(new BigDecimal("129.90"));
        p.setCreadoEn(OffsetDateTime.of(2026, 9, 23, 0, 5, 0, 0, ZoneOffset.UTC));
        return p;
    }
}
