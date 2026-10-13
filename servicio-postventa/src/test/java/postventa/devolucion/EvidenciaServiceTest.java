package postventa.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import postventa.compartido.SolicitudInvalidaException;
import postventa.devolucion.dto.EvidenciaSubidaResponse;

/** 2.1 con el almacén local en una carpeta temporal. */
class EvidenciaServiceTest {

    static final String URL = "http://localhost:8082/uploads/evidencias";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    @TempDir Path carpeta;

    AlmacenEvidencias almacen;
    EvidenciaService servicio;

    @BeforeEach
    void preparar() {
        almacen = new AlmacenEvidencias("local", carpeta.toString(), URL + "/", "", "", "evidencias");
        servicio = new EvidenciaService(almacen);
    }

    @Test
    void guardaUnaImagenYDevuelveUnaUrlPropia() throws Exception {
        EvidenciaSubidaResponse r = servicio.subir(new MockMultipartFile("file", "foto.png", "image/png", PNG));

        assertThat(r.tipo()).isEqualTo(TipoEvidencia.IMAGEN);
        assertThat(r.url()).startsWith(URL + "/").endsWith(".png");
        assertThat(r.nombreArchivoOriginal()).isEqualTo("foto.png");
        assertThat(r.tamanioBytes()).isEqualTo(PNG.length);
        assertThat(almacen.esPropia(r.url())).isTrue();
        try (var archivos = Files.list(carpeta)) {
            assertThat(archivos).hasSize(1);
        }
    }

    @Test
    void unPdfEsUnDocumento() {
        assertThat(servicio.subir(new MockMultipartFile("file", "boleta.pdf", "application/pdf", PDF)).tipo())
                .isEqualTo(TipoEvidencia.DOCUMENTO);
    }

    @Test
    void rechazaFormatosNoAdmitidos() {
        assertThatThrownBy(() -> servicio.subir(new MockMultipartFile("file", "a.gif", "image/gif", new byte[] {'G', 'I', 'F'})))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ARCHIVO_INVALIDO");
    }

    @Test
    void rechazaUnArchivoQueSoloDiceSerImagen() {
        assertThatThrownBy(() -> servicio.subir(new MockMultipartFile("file", "a.png", "image/png", PDF)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ARCHIVO_INVALIDO");
    }

    @Test
    void rechazaArchivosDeMasDe5Mb() {
        byte[] grande = new byte[(int) EvidenciaService.TAMANO_MAXIMO + 1];
        System.arraycopy(PNG, 0, grande, 0, PNG.length);

        assertThatThrownBy(() -> servicio.subir(new MockMultipartFile("file", "a.png", "image/png", grande)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ARCHIVO_INVALIDO");
    }

    @Test
    void rechazaUnArchivoVacio() {
        assertThatThrownBy(() -> servicio.subir(new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("ARCHIVO_REQUERIDO");
    }

    @Test
    void soloSonPropiasLasUrlsDelAlmacen() {
        assertThat(almacen.esPropia(URL + "/abc.jpg")).isTrue();
        assertThat(almacen.esPropia("https://otro-sitio.com/abc.jpg")).isFalse();
        assertThat(almacen.esPropia(URL + "/../secreto.jpg")).isFalse();
        assertThat(almacen.esPropia(URL + "/")).isFalse();
        assertThat(almacen.esPropia(URL + "-falso/abc.jpg")).isFalse();
    }
}
