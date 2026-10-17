package postventa.reclamo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.CanalVenta;
import postventa.compartido.RecursoNoEncontradoException;
import postventa.compartido.SecurityConfig;
import postventa.compartido.SolicitudInvalidaException;
import postventa.reclamo.dto.ListadoReclamosResponse;
import postventa.reclamo.dto.RegistrarReclamoResponse;
import postventa.reclamo.dto.RespuestaReclamoResponse;
import postventa.reclamo.dto.ResponderReclamoRequest;

/** Sin base de datos: rutas, códigos HTTP, validación de los DTO y nombres de campos del contrato. */
@WebMvcTest(ReclamoController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"seguridad.habilitada=false",
        "seguridad.cliente-prueba=11111111-1111-1111-1111-111111111111"})
class ReclamoControllerTest {

    static final UUID CLIENTE_PRUEBA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    static final String CUERPO = """
            {
              "pedidoId": "PED-2026-00981",
              "tipo": "RECLAMO",
              "canal": "CHATBOT",
              "motivo": "INCUMPLIMIENTO_PLAZO_ENTREGA",
              "detalle": "El pedido no llegó en la fecha programada",
              "consumidor": {
                "nombreCompleto": "Juan Pérez",
                "documento": "72458912",
                "email": "juan.perez@example.com",
                "telefono": "+51999888777"
              }
            }
            """;

    @Autowired MockMvc mvc;

    @MockitoBean ReclamoService reclamoService;

    @Test
    void esp13_ca01_registrarResponde201ConLosCamposDelContrato() throws Exception {
        when(reclamoService.registrar(any(), any())).thenReturn(new RegistrarReclamoResponse("REC-2026-0015",
                "REC-2026-0015", EstadoReclamo.REGISTRADO, MotivoReclamo.INCUMPLIMIENTO_PLAZO_ENTREGA, 15,
                OffsetDateTime.parse("2026-10-14T23:59:59Z"), null));

        mvc.perform(post("/api/v2/reclamos").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v2/reclamos/REC-2026-0015"))
                .andExpect(jsonPath("$.reclamoId").value("REC-2026-0015"))
                .andExpect(jsonPath("$.codigoSeguimiento").value("REC-2026-0015"))
                .andExpect(jsonPath("$.estado").value("REGISTRADO"))
                .andExpect(jsonPath("$.plazoDiasHabiles").value(15))
                .andExpect(jsonPath("$.fechaLimiteSLA").value("2026-10-14T23:59:59Z"))
                .andExpect(jsonPath("$.respuestaVisibleCliente").isEmpty());
    }

    @Test
    void esp13_ca04_motivoNoTipificadoResponde400SinRegistrar() throws Exception {
        mvc.perform(post("/api/v2/reclamos").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("INCUMPLIMIENTO_PLAZO_ENTREGA", "MOTIVO_NO_VALIDO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
        verifyNoInteractions(reclamoService);
    }

    @Test
    void esp13_ca04_consumidorIncompletoResponde400() throws Exception {
        mvc.perform(post("/api/v2/reclamos").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("\"juan.perez@example.com\"", "\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalles[0].campo").value("consumidor.email"));
        verifyNoInteractions(reclamoService);
    }

    @Test
    void esp13_ca02_quejaSinPedidoSeAcepta() throws Exception {
        when(reclamoService.registrar(any(), any())).thenReturn(new RegistrarReclamoResponse("REC-2026-0016",
                "REC-2026-0016", EstadoReclamo.REGISTRADO, MotivoReclamo.ATENCION_INADECUADA, 15,
                OffsetDateTime.parse("2026-10-14T23:59:59Z"), null));

        mvc.perform(post("/api/v2/reclamos").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("\"pedidoId\": \"PED-2026-00981\",", "")
                                .replace("\"RECLAMO\"", "\"QUEJA\"")))
                .andExpect(status().isCreated());
    }

    @Test
    void consultarInexistenteResponde404() throws Exception {
        when(reclamoService.consultar(eq("REC-2026-9999"), any()))
                .thenThrow(new RecursoNoEncontradoException("RECLAMO_NO_ENCONTRADO", "No existe"));

        mvc.perform(get("/api/v2/reclamos/REC-2026-9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_NO_ENCONTRADO"));
    }

    @Test
    void misReclamosUsaElClienteDePruebaSinSeguridad() throws Exception {
        when(reclamoService.listar(FiltroReclamos.de(null, CLIENTE_PRUEBA, null), 0, 10))
                .thenReturn(new ListadoReclamosResponse(List.of(new ListadoReclamosResponse.Resumen("REC-2026-0015",
                        "REC-2026-0015", TipoReclamo.RECLAMO, MotivoReclamo.INCUMPLIMIENTO_PLAZO_ENTREGA,
                        EstadoReclamo.EN_PROCESO, OffsetDateTime.parse("2026-09-23T00:10:00Z"),
                        OffsetDateTime.parse("2026-10-14T23:59:59Z"), "PED-2026-00981", CanalVenta.CHATBOT,
                        "Juan Pérez", EstadoSla.EN_PLAZO, 12)), 0, 1, 1));

        mvc.perform(get("/api/v2/reclamos/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].codigoSeguimiento").value("REC-2026-0015"))
                .andExpect(jsonPath("$.contenido[0].estadoSla").value("EN_PLAZO"))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void paginacionNoNumericaRespondePaginacionInvalida() throws Exception {
        mvc.perform(get("/api/v2/reclamos").param("pagina", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PAGINACION_INVALIDA"));
    }

    @Test
    void listadoAdministrativoPasaLosFiltros() throws Exception {
        when(reclamoService.listar(FiltroReclamos.de("72458912", null, EstadoReclamo.REGISTRADO), 0, 10))
                .thenReturn(new ListadoReclamosResponse(List.of(), 0, 0, 0));

        mvc.perform(get("/api/v2/reclamos").param("documento", "72458912").param("estado", "REGISTRADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").isEmpty());
    }

    @Test
    void filtrosDeLaBandejaDelMockup() throws Exception {
        FiltroReclamos esperado = new FiltroReclamos(null, null, null, "PED-2026-00981", EstadoSla.VENCIDO,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), FiltroReclamos.Orden.ANTIGUOS);
        when(reclamoService.listar(esperado, 0, 5)).thenReturn(new ListadoReclamosResponse(List.of(), 0, 0, 0));

        mvc.perform(get("/api/v2/reclamos").param("buscar", "PED-2026-00981").param("sla", "VENCIDO")
                        .param("desde", "2026-09-01").param("hasta", "2026-09-30").param("orden", "ANTIGUOS")
                        .param("tamano", "5"))
                .andExpect(status().isOk());
        verify(reclamoService).listar(esperado, 0, 5);
    }

    @Test
    void slaFueraDelCatalogoResponde400() throws Exception {
        mvc.perform(get("/api/v2/reclamos").param("sla", "PROXIMO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
        verifyNoInteractions(reclamoService);
    }

    @Test
    void responderDevuelveFechaRespuesta() throws Exception {
        when(reclamoService.responder(eq("REC-2026-0015"), any())).thenReturn(new RespuestaReclamoResponse(
                "REC-2026-0015", EstadoReclamo.ATENDIDO, OffsetDateTime.parse("2026-09-23T00:18:00Z"),
                "Estimado Juan, lamentamos la demora."));

        mvc.perform(patch("/api/v2/reclamos/REC-2026-0015/respuesta").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nuevoEstado": "ATENDIDO", "respuestaVisibleCliente": "Estimado Juan, lamentamos la demora.",
                                 "atendidoPor": "GESTOR-02"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ATENDIDO"))
                .andExpect(jsonPath("$.fechaRespuesta").value("2026-09-23T00:18:00Z"));
        verify(reclamoService).responder("REC-2026-0015",
                new ResponderReclamoRequest(EstadoReclamo.ATENDIDO, "Estimado Juan, lamentamos la demora.", "GESTOR-02"));
    }

    @Test
    void esp14_ca03_respuestaVaciaResponde400() throws Exception {
        when(reclamoService.responder(eq("REC-2026-0015"), any()))
                .thenThrow(new SolicitudInvalidaException("RESPUESTA_VACIA", "vacía"));

        mvc.perform(patch("/api/v2/reclamos/REC-2026-0015/respuesta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\": \"ATENDIDO\", \"respuestaVisibleCliente\": \"\", \"atendidoPor\": \"GESTOR-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("RESPUESTA_VACIA"));
    }

    @Test
    void responderSinAtendidoPorResponde400() throws Exception {
        mvc.perform(patch("/api/v2/reclamos/REC-2026-0015/respuesta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\": \"ATENDIDO\", \"respuestaVisibleCliente\": \"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
        verifyNoInteractions(reclamoService);
    }
}
