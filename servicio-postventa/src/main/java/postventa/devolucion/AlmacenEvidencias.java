package postventa.devolucion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import postventa.compartido.DependenciaNoDisponibleException;

/**
 * Dónde quedan los archivos de evidencia (guía 7.2).
 * - evidencias.almacen=supabase: Supabase Storage, bucket evidencias. La URL es la pública del objeto.
 * - evidencias.almacen=local (por defecto): una carpeta del disco y una URL de mentira con evidencias.url-local.
 *   No se sirve; solo sirve para probar el flujo sin credenciales de Storage.
 * Solo se aceptan en una devolución las URLs que empiezan con la base de este almacén (referencia verificable).
 */
@Slf4j
@Component
public class AlmacenEvidencias {

    private final boolean supabase;
    private final Path carpeta;
    private final String urlBase;
    private final String bucket;
    private final RestClient http;

    public AlmacenEvidencias(@Value("${evidencias.almacen:local}") String almacen,
                             @Value("${evidencias.carpeta-local:uploads/evidencias}") String carpeta,
                             @Value("${evidencias.url-local:http://localhost:8082/uploads/evidencias}") String urlLocal,
                             @Value("${evidencias.supabase-url:}") String supabaseUrl,
                             @Value("${evidencias.supabase-clave:}") String clave,
                             @Value("${evidencias.bucket:evidencias}") String bucket) {
        this.supabase = "supabase".equalsIgnoreCase(almacen.trim());
        this.carpeta = Path.of(carpeta);
        this.bucket = bucket;
        if (supabase) {
            if (supabaseUrl.isBlank() || clave.isBlank()) {
                throw new IllegalStateException("evidencias.almacen=supabase necesita SUPABASE_URL y SUPABASE_SERVICE_KEY");
            }
            String base = sinBarraFinal(supabaseUrl);
            this.urlBase = base + "/storage/v1/object/public/" + bucket;
            this.http = RestClient.builder().baseUrl(base)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + clave)
                    .defaultHeader("apikey", clave)
                    .build();
        } else {
            this.urlBase = sinBarraFinal(urlLocal);
            this.http = null;
        }
    }

    /** Guarda el archivo con ese nombre (único) y devuelve su URL. */
    public String guardar(String nombre, String tipoContenido, byte[] contenido) {
        if (supabase) {
            try {
                http.post().uri("/storage/v1/object/{bucket}/{nombre}", bucket, nombre)
                        .contentType(MediaType.parseMediaType(tipoContenido))
                        .body(contenido)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                throw new DependenciaNoDisponibleException("ALMACEN_NO_DISPONIBLE",
                        "No se pudo guardar la evidencia; intente de nuevo", e);
            }
        } else {
            try {
                Files.createDirectories(carpeta);
                Files.write(carpeta.resolve(nombre), contenido);
                log.info("[local] evidencia guardada en {}", carpeta.resolve(nombre).toAbsolutePath());
            } catch (IOException e) {
                throw new DependenciaNoDisponibleException("ALMACEN_NO_DISPONIBLE",
                        "No se pudo guardar la evidencia; intente de nuevo", e);
            }
        }
        return urlBase + "/" + nombre;
    }

    /** true si la URL la emitió este almacén (2.1), y no apunta a un sitio cualquiera. */
    public boolean esPropia(String url) {
        if (url == null || !url.startsWith(urlBase + "/")) {
            return false;
        }
        String nombre = url.substring(urlBase.length() + 1);
        return !nombre.isEmpty() && !nombre.contains("/") && !nombre.contains("..");
    }

    private static String sinBarraFinal(String url) {
        String limpia = url.trim();
        return limpia.endsWith("/") ? limpia.substring(0, limpia.length() - 1) : limpia;
    }
}
