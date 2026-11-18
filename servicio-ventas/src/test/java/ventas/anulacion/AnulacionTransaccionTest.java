package ventas.anulacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * El controlador llama a solicitarAnulacion(..., clientIdCanal), que hace un bloqueo pesimista
 * (bloquearPorCodigo). Sin @Transactional en esa misma sobrecarga, cada anulación respondía 500
 * ("Query requires transaction be in progress").
 */
class AnulacionTransaccionTest {

    @Test
    void todasLasSobrecargasDeSolicitarAnulacionSonTransaccionales() {
        Method[] metodos = Arrays.stream(AnulacionService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("solicitarAnulacion"))
                .toArray(Method[]::new);
        assertThat(metodos).isNotEmpty();
        for (Method m : metodos) {
            assertThat(m.isAnnotationPresent(Transactional.class))
                    .as("solicitarAnulacion con %d parámetros debe ser @Transactional", m.getParameterCount())
                    .isTrue();
        }
    }
}
