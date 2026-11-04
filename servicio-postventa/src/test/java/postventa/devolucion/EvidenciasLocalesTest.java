package postventa.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.SecurityConfig;

/**
 * Almacén local de evidencias (evidencias.almacen=local, desarrollo): la URL que devuelve 2.1 debe poder abrirse.
 * EvidenciaService y AlmacenEvidencias reales sobre una carpeta temporal; sin base de datos ni Supabase.
 */
@WebMvcTest(controllers = DevolucionController.class, properties = "seguridad.habilitada=false")
@Import({SecurityConfig.class, EvidenciaService.class, AlmacenEvidencias.class})
class EvidenciasLocalesTest {

    static final Path CARPETA = carpetaTemporal();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    @DynamicPropertySource
    static void carpeta(DynamicPropertyRegistry registro) {
        registro.add("evidencias.carpeta-local", CARPETA::toString);
        registro.add("evidencias.url-local", () -> "http://localhost:8082/uploads/evidencias");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean DevolucionService devolucionService;
    @MockitoBean RecepcionService recepcionService;

    @Test
    void laEvidenciaSubidaSePuedeAbrirEnSuUrl() throws Exception {
        String cuerpo = mvc.perform(multipart("/api/v2/devoluciones/evidencias/upload")
                        .file(new MockMultipartFile("file", "recepcion.png", "image/png", PNG)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode respuesta = json.readTree(cuerpo);
        URI url = URI.create(respuesta.get("url").asText());
        assertThat(url.getPath()).startsWith("/uploads/evidencias/").endsWith(".png");

        mvc.perform(get(url.getPath()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void unArchivoQueNoExisteResponde404() throws Exception {
        mvc.perform(get("/uploads/evidencias/no-existe.png")).andExpect(status().isNotFound());
    }

    @Test
    void noSeSaleDeLaCarpetaDeEvidencias() throws Exception {
        Files.writeString(CARPETA.getParent().resolve("fuera.txt"), "secreto");
        mvc.perform(get("/uploads/evidencias/..%2Ffuera.txt")).andExpect(status().is4xxClientError());
        mvc.perform(get("/uploads/evidencias/../fuera.txt")).andExpect(status().is4xxClientError());
    }

    private static Path carpetaTemporal() {
        try {
            return Files.createDirectories(Files.createTempDirectory("g5-evidencias").resolve("evidencias"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
