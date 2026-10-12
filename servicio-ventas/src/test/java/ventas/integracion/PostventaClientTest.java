package ventas.integracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PostventaClientTest {

    @Test
    void laClaveDeIdempotenciaEsSiempreLaMismaParaLaMismaAnulacion() {
        String clave = PostventaClient.claveIdempotencia("ANUL-0007");

        assertThat(clave).isEqualTo(PostventaClient.claveIdempotencia("ANUL-0007"));
        assertThat(clave).isNotEqualTo(PostventaClient.claveIdempotencia("ANUL-0008"));
        assertThat(UUID.fromString(clave)).isNotNull();
    }
}
