package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import ventas.compartido.ConflictoException;
import ventas.compartido.DependenciaNoDisponibleException;
import ventas.compartido.NoProcesableException;
import ventas.compartido.RecursoNoEncontradoException;
import ventas.integracion.DespachoClient;
import ventas.integracion.DespachoClient.Respuesta;
import ventas.integracion.DespachoClient.Resultado;
import ventas.integracion.ProductosClient;
import ventas.integracion.ProductosClient.ResultadoConfirmacion;
import ventas.pedido.dto.DespachoPedidoResponse;

@ExtendWith(MockitoExtension.class)
class DespachoPedidoServiceTest {

    private static final String CODIGO = "PED-2026-00981";

    @Mock PedidoRepository pedidos;
    @Mock MaquinaEstados maquina;
    @Mock DespachoClient despacho;
    @Mock ProductosClient productos;
    @Mock MensajesProductosService mensajesProductos;

    DespachoPedidoService servicio;
    Pedido pedido;

    @BeforeEach
    void armar() {
        servicio = new DespachoPedidoService(pedidos, maquina, despacho, productos, mensajesProductos,
                TransactionOperations.withoutTransaction());
        pedido = new Pedido();
        pedido.setId(981L);
        pedido.setCodigo(CODIGO);
        pedido.setCanal(CanalVenta.MARKETPLACE);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedido.setNombreContacto("Juan Pérez Rodríguez");
        pedido.setTelefono("+51999888777");
        pedido.setDestinatario("Juan Pérez");
        pedido.setDireccion("Av. La Marina 1234");
        pedido.setReferenciaEnvio("Frente al centro comercial");
        pedido.setDepartamento("Lima");
        pedido.setProvincia("Lima");
        pedido.setDistrito("San Miguel");
        DetallePedido d = new DetallePedido();
        d.setId(1L);
        d.setSku("SKU-AUR-01");
        d.setCantidad(2);
        pedido.setDetalles(new java.util.ArrayList<>(List.of(d)));
    }

    // ---------- Iniciar preparación ----------

    @Test
    void iniciarPreparacionPasaDePagadoAEnPreparacion() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));

        DespachoPedidoResponse r = servicio.iniciarPreparacion(CODIGO);

        verify(maquina).cambiar(pedido, EstadoPedido.EN_PREPARACION, ActorHistorial.SISTEMA_LOGISTICA,
                DespachoPedidoService.MOTIVO_PREPARACION);
        assertThat(r.estado()).isEqualTo(EstadoPedido.EN_PREPARACION);
    }

    @Test
    void iniciarPreparacionDosVecesNoRepiteElCambio() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        assertThat(servicio.iniciarPreparacion(CODIGO).estado()).isEqualTo(EstadoPedido.EN_PREPARACION);
        verifyNoInteractions(maquina);
    }

    @Test
    void iniciarPreparacionEnOtroEstadoORetailResponde409() {
        pedido.setEstado(EstadoPedido.CREADO);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        assertThatThrownBy(() -> servicio.iniciarPreparacion(CODIGO))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "TRANSICION_NO_PERMITIDA");

        pedido.setEstado(EstadoPedido.PAGADO);
        pedido.setCanal(CanalVenta.RETAIL);
        assertThatThrownBy(() -> servicio.iniciarPreparacion(CODIGO))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "DESPACHO_NO_APLICA");
        verifyNoInteractions(maquina);
    }

    @Test
    void pedidoInexistenteResponde404() {
        when(pedidos.findById(981L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.iniciarPreparacion(CODIGO)).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    // ---------- Paquete sellado ----------

    @Test
    void paqueteSelladoArmaLaSolicitudCon71ConLosDatosDelPedido() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.CREADO, "DESP-00412", null, null));

        DespachoPedidoResponse r = servicio.paqueteSellado(CODIGO);

        ArgumentCaptor<DespachoClient.Solicitud> captor = ArgumentCaptor.forClass(DespachoClient.Solicitud.class);
        verify(despacho).crear(captor.capture());
        DespachoClient.Solicitud s = captor.getValue();
        assertThat(s.idPedido()).isEqualTo(CODIGO);
        assertThat(s.destinatario()).isEqualTo(new DespachoClient.Destinatario("Juan Pérez", "+51999888777"));
        assertThat(s.destino()).isEqualTo(new DespachoClient.Destino("Av. La Marina 1234", "Frente al centro comercial",
                "Lima", "Lima", "San Miguel"));
        assertThat(s.lineas()).containsExactly(new DespachoClient.Linea("SKU-AUR-01", 2));
        assertThat(r.idDespacho()).isEqualTo("DESP-00412");
        assertThat(r.estado()).isEqualTo(EstadoPedido.EN_PREPARACION);   // no cambia: DESPACHADO lo avisa Despacho
        verifyNoInteractions(maquina);
    }

    @Test
    void sinDestinatarioUsaElNombreDelContacto() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        pedido.setDestinatario(null);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.SIMULADO, "DESP-SIM-" + CODIGO, null, null));

        servicio.paqueteSellado(CODIGO);

        ArgumentCaptor<DespachoClient.Solicitud> captor = ArgumentCaptor.forClass(DespachoClient.Solicitud.class);
        verify(despacho).crear(captor.capture());
        assertThat(captor.getValue().destinatario().nombre()).isEqualTo("Juan Pérez Rodríguez");
    }

    @Test
    void siYaTeniaDespachoResponde200() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.YA_EXISTIA, "DESP-00412", null, null));
        assertThat(servicio.paqueteSellado(CODIGO).mensaje()).contains("ya tenía");
    }

    @Test
    void erroresDeDespachoSeTraducenAlContrato() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));

        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.SIN_COBERTURA, null, "DESP_ERROR_SIN_COBERTURA", "422"));
        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(NoProcesableException.class).hasFieldOrPropertyWithValue("codigo", "DESP_ERROR_SIN_COBERTURA");

        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.NO_DISPONIBLE, null, null, "503"));
        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(DependenciaNoDisponibleException.class).hasFieldOrPropertyWithValue("codigo", "DESPACHO_NO_DISPONIBLE");

        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.RECHAZADO, null, "DESP_OTRO", "409 DESP_OTRO"));
        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "DESPACHO_RECHAZADO");
        verifyNoInteractions(maquina);   // el pedido se queda EN_PREPARACION
    }

    @Test
    void paqueteSelladoSoloConElPedidoEnPreparacionYConDireccion() {
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));   // PAGADO
        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "ESTADO_NO_PERMITE_DESPACHO");

        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        pedido.setDireccion(null);
        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "DATOS_ENVIO_INCOMPLETOS");
        verifyNoInteractions(despacho);
    }

    @Test
    void sinDistritoNoSePideElDespacho() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        pedido.setDistrito(" ");
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));

        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(ConflictoException.class).hasFieldOrPropertyWithValue("codigo", "DATOS_ENVIO_INCOMPLETOS");
        verifyNoInteractions(despacho);
    }

    @Test
    void losOpcionalesVaciosDelDestinoSeOmiten() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        pedido.setReferenciaEnvio("");
        pedido.setDepartamento(null);
        pedido.setProvincia("  ");
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.CREADO, "DESP-00412", null, null));

        servicio.paqueteSellado(CODIGO);

        ArgumentCaptor<DespachoClient.Solicitud> captor = ArgumentCaptor.forClass(DespachoClient.Solicitud.class);
        verify(despacho).crear(captor.capture());
        assertThat(captor.getValue().destino())
                .isEqualTo(new DespachoClient.Destino("Av. La Marina 1234", null, null, null, "San Miguel"));
    }

    // ---------- Orden de reemplazo (F3) ----------

    private void reemplazoEnPreparacion() {
        pedido.setTipo(TipoPedido.REEMPLAZO);
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        pedido.setReservaId("RES-778");
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
    }

    @Test
    void alSellarElReemplazoSeConsumeSuReservaYLuegoSeDespacha() {
        reemplazoEnPreparacion();
        when(productos.confirmar(CODIGO, "RES-778")).thenReturn(ResultadoConfirmacion.ACEPTADA);
        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.CREADO, "DESP-00412", null, null));

        DespachoPedidoResponse r = servicio.paqueteSellado(CODIGO);

        org.mockito.InOrder orden = org.mockito.Mockito.inOrder(productos, despacho);
        orden.verify(productos).confirmar(CODIGO, "RES-778");
        orden.verify(despacho).crear(any());
        assertThat(r.idDespacho()).isEqualTo("DESP-00412");
    }

    @Test
    void siLaReservaDelReemplazoVencioSeAnulaYNoSeDespacha() {
        reemplazoEnPreparacion();
        when(productos.confirmar(CODIGO, "RES-778")).thenReturn(ResultadoConfirmacion.RESERVA_EXPIRADA);

        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO))
                .isInstanceOf(ConflictoException.class).extracting("codigo").isEqualTo("RESERVA_EXPIRADA");

        verify(mensajesProductos).reservaExpiradaReemplazo(CODIGO);
        verifyNoInteractions(despacho);
    }

    @Test
    void siProductosNoRespondeElReemplazoSigueEnPreparacion() {
        reemplazoEnPreparacion();
        when(productos.confirmar(CODIGO, "RES-778")).thenReturn(ResultadoConfirmacion.NO_ENVIADA);

        assertThatThrownBy(() -> servicio.paqueteSellado(CODIGO)).isInstanceOf(DependenciaNoDisponibleException.class);
        verifyNoInteractions(despacho, mensajesProductos);
    }

    @Test
    void unaVentaNoConsumeLaReservaAlSellar() {
        pedido.setEstado(EstadoPedido.EN_PREPARACION);
        pedido.setReservaId("RES-778");
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido));
        when(despacho.crear(any())).thenReturn(new Respuesta(Resultado.CREADO, "DESP-00412", null, null));

        servicio.paqueteSellado(CODIGO);

        verifyNoInteractions(productos);
    }
}
