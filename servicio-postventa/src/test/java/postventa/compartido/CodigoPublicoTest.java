package postventa.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CodigoPublicoTest {

    @Test
    void generaCodigosConElFormatoDeLaGuia() {
        assertThat(CodigoPublico.pedido(981, 2026)).isEqualTo("PED-2026-00981");
        assertThat(CodigoPublico.anulacion(12)).isEqualTo("ANUL-0012");
        assertThat(CodigoPublico.devolucion(42, 2026)).isEqualTo("DEV-2026-0042");
        assertThat(CodigoPublico.reembolso(7)).isEqualTo("REEM-00007");
        assertThat(CodigoPublico.reclamo(5, 2026)).isEqualTo("REC-2026-0005");
    }

    @Test
    void obtieneElIdDelNumeroFinal() {
        assertThat(CodigoPublico.id("PED-2026-00981")).isEqualTo(981L);
        assertThat(CodigoPublico.id("ANUL-0012")).isEqualTo(12L);
        assertThat(CodigoPublico.id("CLI-8841")).isEqualTo(8841L);
    }

    @Test
    void codigoTemporalEntraEnVarchar30() {
        String codigo = CodigoPublico.temporal();
        assertThat(codigo).startsWith("TMP-").hasSizeLessThanOrEqualTo(30);
        assertThat(CodigoPublico.temporal()).isNotEqualTo(codigo);
    }

    @Test
    void rechazaCodigosMalFormados() {
        assertThatThrownBy(() -> CodigoPublico.id("PED-2026-"))
                .isInstanceOf(SolicitudInvalidaException.class)
                .extracting("codigo").isEqualTo("CODIGO_INVALIDO");
        assertThatThrownBy(() -> CodigoPublico.id("PED-ABC"))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> CodigoPublico.id(null))
                .isInstanceOf(SolicitudInvalidaException.class);
    }
}
