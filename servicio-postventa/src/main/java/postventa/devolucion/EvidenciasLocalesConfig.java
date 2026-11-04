package postventa.devolucion;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Con evidencias.almacen=local (por defecto, desarrollo) sirve en solo lectura la carpeta donde AlmacenEvidencias
 * guarda los archivos, en la misma ruta de evidencias.url-local (/uploads/evidencias/{nombre}). Así la URL que
 * devuelve 2.1 se puede abrir, como la URL pública del bucket en modo supabase. Con supabase no se registra.
 * El manejador de recursos de Spring no sale de la carpeta (rechaza ".." y rutas absolutas).
 */
@Configuration
@ConditionalOnProperty(name = "evidencias.almacen", havingValue = "local", matchIfMissing = true)
public class EvidenciasLocalesConfig implements WebMvcConfigurer {

    static final String RUTA = "/uploads/evidencias/";

    private final Path carpeta;

    public EvidenciasLocalesConfig(@Value("${evidencias.carpeta-local:uploads/evidencias}") String carpeta) {
        this.carpeta = Path.of(carpeta).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registro) {
        String ubicacion = carpeta.toUri().toString();
        registro.addResourceHandler(RUTA + "*")
                .addResourceLocations(ubicacion.endsWith("/") ? ubicacion : ubicacion + "/");
    }
}
