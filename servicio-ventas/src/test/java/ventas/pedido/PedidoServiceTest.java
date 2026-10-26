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
import ventas.pedido.dto.KpisVentasResponse;
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

        ListadoPedidosResponse r = servicio.listar(CLIENTE, null, null, null, null, null, null, 0, 10);

        assertThat(r.clienteId()).isEqualTo(CLIENTE);
        assertThat(r.pagina()).isZero();
        assertThat(r.totalPaginas()).isEqualTo(1);
        assertThat(r.totalElementos()).isEqualTo(1);
        assertThat(r.contenido()).singleElement().satisfies(p -> assertThat(p.pedidoId()).isEqualTo("PED-2026-00981"));
    }

    @Test
    void listarPaginaDesdeCeroYOrdenaPorFechaDescendente() {
        when(pedidos.findAll(ArgumentMatchers.<Specification<Pedido>>any(), any(Pageable.class))).thenReturn(Page.empty());
        servicio.listar(CLIENTE, EstadoPedido.PAGADO, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13), null, 2, 25);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pedidos).findAll(ArgumentMatchers.<Specification<Pedido>>any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(25);
        assertThat(captor.getValue().getSort().getOrderFor("creadoEn").isDescending()).isTrue();
    }

    @Test
    void listarSinClienteYOrdenAntiguos() {
        when(pedidos.findAll(ArgumentMatchers.<Specification<Pedido>>any(), any(Pageable.class))).thenReturn(Page.empty());

        ListadoPedidosResponse r = servicio.listar(null, null, CanalVenta.RETAIL, "PED-2026-00012", null, null,
                PedidosFiltro.Orden.ANTIGUOS, 0, 10);

        assertThat(r.clienteId()).isNull();
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pedidos).findAll(ArgumentMatchers.<Specification<Pedido>>any(), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("creadoEn").isAscending()).isTrue();
    }

    @Test
    void listarRechazaParametrosInvalidos() {
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, null, null, null, -1, 10))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, null, null, null, 0, 0))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, null, null, null, 0, 51))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PAGINACION_INVALIDA");
        assertThatThrownBy(() -> servicio.listar(CLIENTE, null, null, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null, 0, 10))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        verifyNoInteractions(pedidos);
    }

    // ---------- 1.14 KPIs de ventas (Dashboard) ----------

    private static final LocalDate DESDE = LocalDate.of(2026, 10, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 10, 3);

    @Test
    void kpisSumanSoloLosPedidosVendidosYCuentanLaCohorte() {
        when(pedidos.findByTipoAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(eq(TipoPedido.VENTA), any(), any()))
                .thenReturn(List.of(
                        fila(1, CanalVenta.CHATBOT, EstadoPedido.ENTREGADO, "100.00"),
                        fila(1, CanalVenta.MARKETPLACE, EstadoPedido.PAGADO, "50.00"),
                        fila(3, CanalVenta.RETAIL, EstadoPedido.EN_PREPARACION, "30.00"),
                        fila(2, CanalVenta.CHATBOT, EstadoPedido.ANULADO, "999.00"),
                        fila(2, CanalVenta.CHATBOT, EstadoPedido.CREADO, "888.00")));

        KpisVentasResponse r = servicio.kpis(DESDE, HASTA, null);

        assertThat(r.montoVendido()).isEqualByComparingTo("180.00");
        assertThat(r.pedidosVendidos()).isEqualTo(3);
        assertThat(r.ticketPromedio()).isEqualByComparingTo("60.00");
        assertThat(r.pedidosTotal()).isEqualTo(4);          // el CREADO no cuenta
        assertThat(r.pedidosEntregados()).isEqualTo(1);
        assertThat(r.pedidosAnulados()).isEqualTo(1);
        assertThat(r.ventasPorCanal().get(CanalVenta.CHATBOT)).isEqualByComparingTo("100.00");
        assertThat(r.ventasPorCanal().get(CanalVenta.MARKETPLACE)).isEqualByComparingTo("50.00");
        assertThat(r.ventasPorCanal().get(CanalVenta.RETAIL)).isEqualByComparingTo("30.00");
    }

    @Test
    void kpisDevuelvenUnDiaPorCadaFechaDelRangoAunqueNoHayaVentas() {
        when(pedidos.findByTipoAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(eq(TipoPedido.VENTA), any(), any()))
                .thenReturn(List.of(fila(1, CanalVenta.CHATBOT, EstadoPedido.PAGADO, "40.00"),
                        fila(1, CanalVenta.RETAIL, EstadoPedido.ENTREGADO, "10.00")));

        KpisVentasResponse r = servicio.kpis(DESDE, HASTA, null);

        assertThat(r.ventasPorDia()).extracting(KpisVentasResponse.VentaDia::fecha)
                .containsExactly(DESDE, DESDE.plusDays(1), HASTA);
        assertThat(r.ventasPorDia().get(0).monto()).isEqualByComparingTo("50.00");
        assertThat(r.ventasPorDia().get(1).monto()).isEqualByComparingTo("0");
    }

    @Test
    void kpisSinVentasTienenTicketNulo() {
        when(pedidos.findByTipoAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(eq(TipoPedido.VENTA), any(), any()))
                .thenReturn(List.of());

        KpisVentasResponse r = servicio.kpis(DESDE, HASTA, null);

        assertThat(r.montoVendido()).isEqualByComparingTo("0");
        assertThat(r.ticketPromedio()).isNull();
        assertThat(r.ventasPorCanal()).containsOnlyKeys(CanalVenta.values());
    }

    @Test
    void kpisFiltranPorCanalYUsanElRangoUtcCompleto() {
        when(pedidos.findByTipoAndCanalAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(
                eq(TipoPedido.VENTA), eq(CanalVenta.RETAIL), any(), any())).thenReturn(List.of());

        servicio.kpis(DESDE, HASTA, CanalVenta.RETAIL);

        ArgumentCaptor<OffsetDateTime> inicio = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> fin = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(pedidos).findByTipoAndCanalAndCreadoEnGreaterThanEqualAndCreadoEnLessThan(
                eq(TipoPedido.VENTA), eq(CanalVenta.RETAIL), inicio.capture(), fin.capture());
        assertThat(inicio.getValue()).isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(fin.getValue()).isEqualTo(OffsetDateTime.of(2026, 10, 4, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void kpisRechazanRangosInvalidos() {
        assertThatThrownBy(() -> servicio.kpis(null, HASTA, null))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.kpis(HASTA, DESDE, null))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.kpis(DESDE, DESDE.plusDays(366), null))
                .isInstanceOf(SolicitudInvalidaException.class).hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        verifyNoInteractions(pedidos);
    }

    /** Pedido del día 'dia' de octubre de 2026, a las 15:00 UTC. */
    private static FilaKpi fila(int dia, CanalVenta canal, EstadoPedido estado, String total) {
        OffsetDateTime creado = OffsetDateTime.of(2026, 10, dia, 15, 0, 0, 0, ZoneOffset.UTC);
        BigDecimal monto = new BigDecimal(total);
        return new FilaKpi() {
            public OffsetDateTime getCreadoEn() { return creado; }
            public CanalVenta getCanal() { return canal; }
            public EstadoPedido getEstado() { return estado; }
            public BigDecimal getTotal() { return monto; }
        };
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
