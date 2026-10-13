package postventa.devolucion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.ConflictoException;
import postventa.compartido.SecurityConfig;
import postventa.devolucion.dto.EvidenciaSubidaResponse;
import postventa.devolucion.dto.ListadoDevolucionesResponse;
import postventa.devolucion.dto.RegistrarDevolucionResponse;
import postventa.devolucion.dto.ResolucionResponse;

/** Sin base de datos: rutas, códigos HTTP, validación de los DTO y formato de error del contrato. */
@WebMvcTest(DevolucionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"seguridad.habilitada=false",
        "seguridad.cliente-prueba=11111111-1111-1111-1111-111111111111"})
class DevolucionControllerTest {

    static final UUID CLIENTE_PRUEBA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;

    @MockitoBean DevolucionService devolucionService;
    @MockitoBean EvidenciaService evidenciaService;

    @Test
    void registrarResponde201ConLocation() throws Exception {
        when(devolucionService.registrar(any(), any())).thenReturn(new RegistrarDevolucionResponse("DEV-2026-0042",
                "PED-2026-00981", TipoDevolucion.DEVOLUCION_DINERO, EstadoDevolucion.SOLICITADA,
                OffsetDateTime.parse("2026-09-23T15:51:00Z")));

        mvc.perform(post("/api/v2/devoluciones").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v2/devoluciones/DEV-2026-0042"))
                .andExpect(jsonPath("$.devolucionId").value("DEV-2026-0042"))
                .andExpect(jsonPath("$.estado").value("SOLICITADA"))
                .andExpect(jsonPath("$.fechaRegistro").value("2026-09-23T15:51:00Z"));
    }

    @Test
    void esp07_ca03_sinMotivoResponde400() throws Exception {
        mvc.perform(post("/api/v2/devoluciones").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("\"motivo\": \"PRODUCTO_DEFECTUOSO\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalles[0].campo").value("motivo"));
        verifyNoInteractions(devolucionService);
    }

    @Test
    void motivoFueraDelCatalogoResponde400() throws Exception {
        mvc.perform(post("/api/v2/devoluciones").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("PRODUCTO_DEFECTUOSO", "NO_ME_GUSTO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    void sinItemsResponde400() throws Exception {
        mvc.perform(post("/api/v2/devoluciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pedidoId\":\"PED-2026-00981\",\"tipo\":\"CAMBIO\",\"motivo\":\"OTRO\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    @Test
    void subirEvidenciaResponde201() throws Exception {
        when(evidenciaService.subir(any())).thenReturn(new EvidenciaSubidaResponse(TipoEvidencia.IMAGEN,
                "http://localhost:8082/uploads/evidencias/x.png", "foto.png", 4, OffsetDateTime.parse("2026-09-23T15:50:00Z")));

        mvc.perform(multipart("/api/v2/devoluciones/evidencias/upload")
                        .file(new MockMultipartFile("file", "foto.png", "image/png", new byte[] {1, 2, 3, 4})))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("IMAGEN"))
                .andExpect(jsonPath("$.nombreArchivoOriginal").value("foto.png"));
    }

    @Test
    void subirSinArchivoResponde400() throws Exception {
        mvc.perform(multipart("/api/v2/devoluciones/evidencias/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARAMETRO_FALTANTE"));
    }

    @Test
    void listadoAdministrativoExigeClienteId() throws Exception {
        mvc.perform(get("/api/v2/devoluciones"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARAMETRO_FALTANTE"));
    }

    @Test
    void misDevolucionesUsaElClienteDelTokenYNoDevuelveClienteId() throws Exception {
        when(devolucionService.listar(eq(CLIENTE_PRUEBA), eq(null), eq(null), eq(0), eq(10)))
                .thenReturn(new ListadoDevolucionesResponse(CLIENTE_PRUEBA, List.of(), 0, 0, 0));

        mvc.perform(get("/api/v2/devoluciones/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").doesNotExist())
                .andExpect(jsonPath("$.totalElementos").value(0));
        verify(devolucionService).listar(CLIENTE_PRUEBA, null, null, 0, 10);
    }

    @Test
    void resolverResponde200() throws Exception {
        when(devolucionService.resolver(eq("DEV-2026-0042"), any(), any())).thenReturn(new ResolucionResponse(
                "DEV-2026-0042", EstadoDevolucion.RECHAZADA, DecisionDevolucion.RECHAZADA, TipoResolucion.RECHAZO,
                "Manipulación interna", null, OffsetDateTime.parse("2026-09-23T16:00:00Z"), null));

        mvc.perform(patch("/api/v2/devoluciones/DEV-2026-0042/resolucion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"RECHAZADA\",\"fundamento\":\"Manipulación interna\",\"autorizadorId\":\"GESTOR-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"));
    }

    @Test
    void sinStockResponde409ConSuCodigo() throws Exception {
        when(devolucionService.resolver(eq("DEV-2026-0042"), any(), any()))
                .thenThrow(new ConflictoException("SIN_STOCK", "No existe disponibilidad"));

        mvc.perform(patch("/api/v2/devoluciones/DEV-2026-0042/resolucion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APROBADA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SIN_STOCK"));
    }

    @Test
    void resolverSinDecisionResponde400() throws Exception {
        mvc.perform(patch("/api/v2/devoluciones/DEV-2026-0042/resolucion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fundamento\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"));
    }

    static final String CUERPO = """
            {
              "pedidoId": "PED-2026-00981",
              "tipo": "DEVOLUCION_DINERO",
              "motivo": "PRODUCTO_DEFECTUOSO",
              "descripcion": "El auricular izquierdo no emite sonido",
              "items": [ { "productoId": "PROD-101", "cantidad": 1 } ],
              "evidencias": [ { "tipo": "IMAGEN", "url": "http://localhost:8082/uploads/evidencias/ev-1.jpg" } ]
            }
            """;
}
