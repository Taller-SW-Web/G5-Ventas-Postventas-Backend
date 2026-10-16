package ventas.anulacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ventas.integracion.PostventaClient;
import ventas.integracion.PostventaClient.Envio;
import ventas.integracion.PostventaClient.RespuestaReembolso;
import ventas.pedido.Pedido;
import ventas.pedido.PedidoRepository;

@ExtendWith(MockitoExtension.class)
class ReembolsoAnulacionServiceTest {

    @Mock SolicitudReembolsoRepository solicitudes;
    @Mock PedidoRepository pedidos;
    @Mock PostventaClient postventa;
    @InjectMocks ReembolsoAnulacionService servicio;

    @Test
    void registrarGuardaLaSolicitudPendiente() {
        servicio.registrar("ANUL-0012", 981L, new BigDecimal("129.90"), "PEN", "Entrega fallida definitiva (guía GR-00892)");

        ArgumentCaptor<SolicitudReembolso> captor = ArgumentCaptor.forClass(SolicitudReembolso.class);
        verify(solicitudes).save(captor.capture());
        SolicitudReembolso s = captor.getValue();
        assertThat(s.getIdAnulacion()).isEqualTo(12L);
        assertThat(s.getIdPedido()).isEqualTo(981L);
        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.PENDIENTE);
        assertThat(s.getIntentos()).isZero();
        assertThat(s.getCreadoEn()).isNotNull();
    }

    @Test
    void registrarDosVecesLaMismaAnulacionNoDuplica() {
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(solicitud(EstadoSolicitudReembolso.PENDIENTE)));
        servicio.registrar("ANUL-0012", 981L, BigDecimal.TEN, "PEN", "x");
        verify(solicitudes, never()).save(any());
    }

    @Test
    void registrarRecortaElMotivoA255() {
        servicio.registrar("ANUL-0012", 981L, BigDecimal.TEN, "PEN", "x".repeat(300));
        ArgumentCaptor<SolicitudReembolso> captor = ArgumentCaptor.forClass(SolicitudReembolso.class);
        verify(solicitudes).save(captor.capture());
        assertThat(captor.getValue().getMotivo()).hasSize(255);
    }

    @Test
    void enviarAceptadoQuedaEnviadaConElIdDeM2() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(s));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(eq("ANUL-0012"), eq("PED-2026-00981"), eq(new BigDecimal("129.90")), eq("PEN"), eq("motivo")))
                .thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-90815", "EXITOSO", null));

        RespuestaReembolso r = servicio.enviar("ANUL-0012");

        assertThat(r.reembolsoId()).isEqualTo("REEM-90815");
        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.ENVIADA);
        assertThat(s.getReembolsoId()).isEqualTo("REEM-90815");
        assertThat(s.getIntentos()).isEqualTo(1);
        verify(solicitudes).save(s);
    }

    @Test
    void siM2DejaElReembolsoPendienteLaSolicitudSigueParaReintentar() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(s));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-90815", "PENDIENTE", null));

        servicio.enviar("ANUL-0012");

        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.PENDIENTE);
        assertThat(s.getReembolsoId()).isEqualTo("REEM-90815");
        assertThat(s.getIntentos()).isEqualTo(1);
        assertThat(s.getUltimoError()).contains("PENDIENTE");
        verify(solicitudes).save(s);
    }

    @Test
    void elReintentoDeUnPendienteEnM2QuedaEnviadaCuandoM2Confirma() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        when(solicitudes.findTop20ByEstadoOrderByCreadoEnAsc(EstadoSolicitudReembolso.PENDIENTE)).thenReturn(List.of(s));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-90815", "PENDIENTE", null))
                .thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-90815", "EXITOSO", null));

        assertThat(servicio.reintentarPendientes()).isZero();     // sigue PENDIENTE en M2
        assertThat(servicio.reintentarPendientes()).isEqualTo(1); // M2 ya confirmó
        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.ENVIADA);
        assertThat(s.getIntentos()).isEqualTo(2);
        assertThat(s.getUltimoError()).isNull();
    }

    @Test
    void enviarConM2CaidoQuedaPendienteConElError() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(s));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.NO_DISPONIBLE, null, null, "Connection refused"));

        servicio.enviar("ANUL-0012");

        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.PENDIENTE);
        assertThat(s.getIntentos()).isEqualTo(1);
        assertThat(s.getUltimoError()).isEqualTo("Connection refused");
    }

    @Test
    void enviarRechazadoQuedaRechazada() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(s));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.RECHAZADO, null, null, "SALDO_INSUFICIENTE"));

        servicio.enviar("ANUL-0012");

        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.RECHAZADA);
    }

    @Test
    void enModoSimuladoNoTocaLaSolicitud() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(s));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.SIMULADO, null, null, null));

        servicio.enviar("ANUL-0012");

        assertThat(s.getEstado()).isEqualTo(EstadoSolicitudReembolso.PENDIENTE);
        assertThat(s.getIntentos()).isZero();
        verify(solicitudes, never()).save(any());
    }

    @Test
    void yaEnviadaNoLlamaOtraVezAM2() {
        SolicitudReembolso s = solicitud(EstadoSolicitudReembolso.ENVIADA);
        s.setReembolsoId("REEM-90815");
        when(solicitudes.findByIdAnulacion(12L)).thenReturn(Optional.of(s));

        RespuestaReembolso r = servicio.enviar("ANUL-0012");

        assertThat(r.envio()).isEqualTo(Envio.ACEPTADO);
        assertThat(r.reembolsoId()).isEqualTo("REEM-90815");
        verify(postventa, never()).solicitarReembolso(any(), any(), any(), any(), any());
    }

    @Test
    void sinSolicitudDevuelveNull() {
        assertThat(servicio.enviar("ANUL-0012")).isNull();
    }

    @Test
    void reintentoCortaCuandoM2NoEstaDisponible() {
        SolicitudReembolso a = solicitud(EstadoSolicitudReembolso.PENDIENTE), b = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        b.setIdAnulacion(13L);
        when(solicitudes.findTop20ByEstadoOrderByCreadoEnAsc(EstadoSolicitudReembolso.PENDIENTE)).thenReturn(List.of(a, b));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(eq("ANUL-0012"), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.NO_DISPONIBLE, null, null, "caído"));

        assertThat(servicio.reintentarPendientes()).isZero();
        verify(postventa, never()).solicitarReembolso(eq("ANUL-0013"), any(), any(), any(), any());
    }

    @Test
    void reintentoCuentaLosEnviados() {
        SolicitudReembolso a = solicitud(EstadoSolicitudReembolso.PENDIENTE), b = solicitud(EstadoSolicitudReembolso.PENDIENTE);
        b.setIdAnulacion(13L);
        when(solicitudes.findTop20ByEstadoOrderByCreadoEnAsc(EstadoSolicitudReembolso.PENDIENTE)).thenReturn(List.of(a, b));
        when(pedidos.findById(981L)).thenReturn(Optional.of(pedido()));
        when(postventa.solicitarReembolso(any(), any(), any(), any(), any()))
                .thenReturn(new RespuestaReembolso(Envio.ACEPTADO, "REEM-1", "EXITOSO", null));

        assertThat(servicio.reintentarPendientes()).isEqualTo(2);
    }

    private static SolicitudReembolso solicitud(EstadoSolicitudReembolso estado) {
        SolicitudReembolso s = new SolicitudReembolso();
        s.setId(1L);
        s.setIdAnulacion(12L);
        s.setIdPedido(981L);
        s.setMonto(new BigDecimal("129.90"));
        s.setMoneda("PEN");
        s.setMotivo("motivo");
        s.setEstado(estado);
        s.setCreadoEn(OffsetDateTime.now());
        s.setActualizadoEn(OffsetDateTime.now());
        return s;
    }

    private static Pedido pedido() {
        Pedido p = new Pedido();
        p.setId(981L);
        p.setCodigo("PED-2026-00981");
        return p;
    }

    @Test
    void unReemplazoSinCobroNoGeneraReembolso() {
        servicio.registrar("ANUL-0012", 981L, BigDecimal.ZERO, "PEN", "ENTREGA_FALLIDA_DEFINITIVA");
        verify(solicitudes, never()).save(any());
        verify(solicitudes, never()).findByIdAnulacion(any());
    }
}
