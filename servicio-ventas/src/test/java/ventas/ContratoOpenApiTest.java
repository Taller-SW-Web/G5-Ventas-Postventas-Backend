package ventas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/** El diseño publicado en Swagger (ventas.yaml) tiene que coincidir con las rutas y filtros que el servicio expone. */
class ContratoOpenApiTest {

    private static final String ARCHIVO = "/static/openapi/ventas.yaml";

    @Test
    void elDisenoTieneLasMismasRutasQueLosControllers() throws Exception {
        assertThat(rutasDelDiseno().keySet()).isEqualTo(rutasDeLosControllers().keySet());
    }

    @Test
    void cadaRutaDocumentaLosMismosFiltrosQueSuController() throws Exception {
        assertThat(new TreeMap<>(rutasDelDiseno())).isEqualTo(new TreeMap<>(rutasDeLosControllers()));
    }

    @Test
    void cadaOperacionDiceQuienPuedeUsarlaYQueResponde() throws Exception {
        Map<String, Object> diseno = diseno();
        for (Map.Entry<String, Object> ruta : mapa(diseno.get("paths")).entrySet()) {
            for (Map.Entry<String, Object> operacion : mapa(ruta.getValue()).entrySet()) {
                Map<String, Object> o = mapa(operacion.getValue());
                String nombre = operacion.getKey() + " " + ruta.getKey();
                assertThat(o.get("security")).as("security de " + nombre).isNotNull();
                assertThat(o.get("description")).as("description de " + nombre).isNotNull();
                assertThat(mapa(o.get("responses"))).as("responses de " + nombre).isNotEmpty();
            }
        }
    }

    /** "GET /ruta" -> nombres de los parámetros de query. */
    private static Map<String, Set<String>> rutasDelDiseno() throws Exception {
        Map<String, Object> diseno = diseno();
        Map<String, Object> compartidos = mapa(mapa(diseno.get("components")).get("parameters"));
        Map<String, Set<String>> rutas = new HashMap<>();
        for (Map.Entry<String, Object> ruta : mapa(diseno.get("paths")).entrySet()) {
            for (Map.Entry<String, Object> operacion : mapa(ruta.getValue()).entrySet()) {
                Set<String> filtros = new TreeSet<>();
                Object parametros = mapa(operacion.getValue()).get("parameters");
                if (parametros != null) {
                    for (Object crudo : (List<?>) parametros) {
                        Map<String, Object> p = mapa(crudo);
                        if (p.containsKey("$ref")) {
                            String ref = (String) p.get("$ref");
                            p = mapa(compartidos.get(ref.substring(ref.lastIndexOf('/') + 1)));
                        }
                        if ("query".equals(p.get("in"))) {
                            filtros.add((String) p.get("name"));
                        }
                    }
                }
                rutas.put(operacion.getKey().toUpperCase() + " " + ruta.getKey(), filtros);
            }
        }
        return rutas;
    }

    private static Map<String, Set<String>> rutasDeLosControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(false);
        escaner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Set<String>> rutas = new HashMap<>();
        for (BeanDefinition definicion : escaner.findCandidateComponents("ventas")) {
            Class<?> controller = Class.forName(definicion.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefijo = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method metodo : controller.getDeclaredMethods()) {
                RequestMapping mapeo = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                if (mapeo == null) {
                    continue;
                }
                String sufijo = mapeo.path().length == 0 ? "" : mapeo.path()[0];
                Set<String> filtros = new TreeSet<>();
                for (Parameter parametro : metodo.getParameters()) {
                    RequestParam filtro = AnnotatedElementUtils.findMergedAnnotation(parametro, RequestParam.class);
                    if (filtro != null) {
                        filtros.add(filtro.name().isEmpty() ? parametro.getName() : filtro.name());
                    }
                }
                for (RequestMethod verbo : mapeo.method()) {
                    rutas.put(verbo.name() + " " + prefijo + sufijo, filtros);
                }
            }
        }
        return rutas;
    }

    private static Map<String, Object> diseno() throws Exception {
        try (InputStream archivo = ContratoOpenApiTest.class.getResourceAsStream(ARCHIVO)) {
            return new Yaml().load(archivo);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object valor) {
        return (Map<String, Object>) valor;
    }
}
