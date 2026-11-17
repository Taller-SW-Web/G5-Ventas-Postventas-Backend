package gateway.gestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import gateway.gestion.LimiteSolicitudesFilter.Cubeta;
import gateway.gestion.LimiteSolicitudesFilter.Resultado;

class CubetaTest {

    @Test
    void permite_hasta_la_capacidad_y_luego_rechaza_con_retry_after() {
        Cubeta cubeta = new Cubeta(3, 0);

        assertThat(cubeta.consumir(0)).isEqualTo(new Resultado(true, 2, 0));
        assertThat(cubeta.consumir(0)).isEqualTo(new Resultado(true, 1, 0));
        assertThat(cubeta.consumir(0)).isEqualTo(new Resultado(true, 0, 0));

        Resultado rechazo = cubeta.consumir(0);
        assertThat(rechazo.permitido()).isFalse();
        // 3 por minuto = 1 ficha cada 20 s
        assertThat(rechazo.segundosParaReintentar()).isEqualTo(20);
    }

    @Test
    void se_recarga_con_el_tiempo_sin_pasar_la_capacidad() {
        Cubeta cubeta = new Cubeta(60, 0);   // 1 ficha por segundo
        for (int i = 0; i < 60; i++) {
            cubeta.consumir(0);
        }
        assertThat(cubeta.consumir(0).permitido()).isFalse();

        assertThat(cubeta.consumir(1_000).permitido()).isTrue();     // pasó 1 s
        assertThat(cubeta.consumir(1_000).permitido()).isFalse();

        assertThat(cubeta.consumir(10 * 60_000).restantes()).isEqualTo(59);   // 10 min: llena, no 600
    }

    @Test
    void cubeta_llena_cuenta_como_inactiva_para_la_purga() {
        Cubeta cubeta = new Cubeta(60, 0);
        cubeta.consumir(0);
        assertThat(cubeta.inactiva(0)).isFalse();
        assertThat(cubeta.inactiva(60_000)).isTrue();
    }
}
