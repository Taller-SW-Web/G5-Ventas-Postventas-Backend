package postventa.devolucion;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import postventa.compartido.SolicitudInvalidaException;
import postventa.devolucion.dto.EvidenciaSubidaResponse;

/**
 * 2.1 Subir evidencia. Acepta jpeg, png, webp y pdf de hasta 5 MB. Además del Content-Type revisa los
 * primeros bytes del archivo, para no guardar algo que solo dice ser una imagen.
 * No escribe en la base: la evidencia se asocia al registrar la devolución (2.2).
 */
@Service
public class EvidenciaService {

    static final long TAMANO_MAXIMO = 5L * 1024 * 1024;

    private record Formato(String extension, TipoEvidencia tipo, byte[] firma, int desde) {}

    private static final Map<String, Formato> FORMATOS = Map.of(
            "image/jpeg", new Formato("jpg", TipoEvidencia.IMAGEN, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0),
            "image/png", new Formato("png", TipoEvidencia.IMAGEN, new byte[] {(byte) 0x89, 'P', 'N', 'G'}, 0),
            "image/webp", new Formato("webp", TipoEvidencia.IMAGEN, new byte[] {'W', 'E', 'B', 'P'}, 8),
            "application/pdf", new Formato("pdf", TipoEvidencia.DOCUMENTO, new byte[] {'%', 'P', 'D', 'F'}, 0));

    private final AlmacenEvidencias almacen;

    public EvidenciaService(AlmacenEvidencias almacen) {
        this.almacen = almacen;
    }

    public EvidenciaSubidaResponse subir(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new SolicitudInvalidaException("ARCHIVO_REQUERIDO", "Debe adjuntar un archivo en el campo file");
        }
        if (archivo.getSize() > TAMANO_MAXIMO) {
            throw new SolicitudInvalidaException("ARCHIVO_INVALIDO", "El archivo supera el tamaño máximo de 5 MB");
        }
        String tipoContenido = archivo.getContentType() == null ? "" : archivo.getContentType().toLowerCase();
        Formato formato = FORMATOS.get(tipoContenido);
        if (formato == null) {
            throw new SolicitudInvalidaException("ARCHIVO_INVALIDO",
                    "Formato no admitido; use JPEG, PNG, WEBP o PDF");
        }
        byte[] contenido = leer(archivo);
        if (!empiezaCon(contenido, formato)) {
            throw new SolicitudInvalidaException("ARCHIVO_INVALIDO",
                    "El contenido del archivo no corresponde a su formato");
        }
        String nombre = UUID.randomUUID() + "." + formato.extension();
        String url = almacen.guardar(nombre, tipoContenido, contenido);
        return new EvidenciaSubidaResponse(formato.tipo(), url, archivo.getOriginalFilename(), archivo.getSize(),
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    private static byte[] leer(MultipartFile archivo) {
        try {
            return archivo.getBytes();
        } catch (IOException e) {
            throw new SolicitudInvalidaException("ARCHIVO_INVALIDO", "No se pudo leer el archivo");
        }
    }

    private static boolean empiezaCon(byte[] contenido, Formato f) {
        int hasta = f.desde() + f.firma().length;
        return contenido.length >= hasta
                && Arrays.equals(Arrays.copyOfRange(contenido, f.desde(), hasta), f.firma());
    }
}
