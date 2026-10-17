package postventa.reclamo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Plazo de 15 días hábiles (ESP-13 CA-03) y semáforo del SLA (propuesta UX 01). */
class CalculadoraSlaTest {

    static final LocalDate LIMITE = LocalDate.parse("2026-10-14"); // miércoles

    @Test
    void esp13_ca03_quinceDiasHabilesComoElEjemploDelContrato() {
        // Registrado el miércoles 23/09/2026 -> fechaLimiteSLA 2026-10-14 (api-postventas.md 2.8)
        assertThat(CalculadoraSla.sumarDiasHabiles(LocalDate.parse("2026-09-23"), 15)).isEqualTo(LIMITE);
    }

    @Test
    void noCuentaSabadosNiDomingos() {
        assertThat(CalculadoraSla.sumarDiasHabiles(LocalDate.parse("2026-09-25"), 1)) // viernes
                .isEqualTo(LocalDate.parse("2026-09-28"));                             // lunes
        assertThat(CalculadoraSla.sumarDiasHabiles(LocalDate.parse("2026-09-26"), 1)) // sábado
                .isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(CalculadoraSla.sumarDiasHabiles(LocalDate.parse("2026-09-26"), 15))
                .isEqualTo(LocalDate.parse("2026-10-16"));
    }

    @Test
    void diasHabilesRestantes() {
        assertThat(CalculadoraSla.diasHabilesRestantes(LIMITE, LIMITE)).isZero();
        assertThat(CalculadoraSla.diasHabilesRestantes(LIMITE, LocalDate.parse("2026-10-13"))).isEqualTo(1);
        assertThat(CalculadoraSla.diasHabilesRestantes(LIMITE, LocalDate.parse("2026-10-10"))).isEqualTo(3); // sábado
        assertThat(CalculadoraSla.diasHabilesRestantes(LIMITE, LocalDate.parse("2026-10-15"))).isEqualTo(-1);
        assertThat(CalculadoraSla.diasHabilesRestantes(LIMITE, LocalDate.parse("2026-10-19"))).isEqualTo(-3);
    }

    @Test
    void semaforoSeparadoDelEstadoDeAtencion() {
        assertThat(CalculadoraSla.estado(EstadoReclamo.EN_PROCESO, LIMITE, LocalDate.parse("2026-09-23")))
                .isEqualTo(EstadoSla.EN_PLAZO);
        assertThat(CalculadoraSla.estado(EstadoReclamo.REGISTRADO, LIMITE, LocalDate.parse("2026-10-08")))
                .isEqualTo(EstadoSla.POR_VENCER); // 4 días hábiles
        assertThat(CalculadoraSla.estado(EstadoReclamo.EN_PROCESO, LIMITE, LIMITE))
                .isEqualTo(EstadoSla.CRITICO);    // vence hoy
        assertThat(CalculadoraSla.estado(EstadoReclamo.EN_PROCESO, LIMITE, LocalDate.parse("2026-10-15")))
                .isEqualTo(EstadoSla.VENCIDO);    // EN_PROCESO y vencido a la vez
        assertThat(CalculadoraSla.estado(EstadoReclamo.ATENDIDO, LIMITE, LocalDate.parse("2026-10-30")))
                .isEqualTo(EstadoSla.ATENDIDO);
    }

    @Test
    void sabadoDespuesDeUnLimiteDeViernesYaEstaVencido() {
        LocalDate viernes = LocalDate.parse("2026-10-16");
        assertThat(CalculadoraSla.diasHabilesRestantes(viernes, LocalDate.parse("2026-10-17"))).isEqualTo(-1);
        assertThat(CalculadoraSla.estado(EstadoReclamo.EN_PROCESO, viernes, LocalDate.parse("2026-10-17")))
                .isEqualTo(EstadoSla.VENCIDO);
    }

    @Test
    void rangosDelFiltroSlaCoincidenConElSemaforo() {
        LocalDate hoy = LocalDate.parse("2026-10-14"); // miércoles
        assertThat(CalculadoraSla.rangoLimite(EstadoSla.VENCIDO, hoy))
                .isEqualTo(new CalculadoraSla.RangoLimite(null, LocalDate.parse("2026-10-13")));
        assertThat(CalculadoraSla.rangoLimite(EstadoSla.POR_VENCER, hoy))
                .isEqualTo(new CalculadoraSla.RangoLimite(hoy, LocalDate.parse("2026-10-21")));
        assertThat(CalculadoraSla.rangoLimite(EstadoSla.EN_PLAZO, hoy))
                .isEqualTo(new CalculadoraSla.RangoLimite(LocalDate.parse("2026-10-22"), null));
        // Cada extremo del rango da el semáforo esperado
        assertThat(CalculadoraSla.estado(EstadoReclamo.REGISTRADO, LocalDate.parse("2026-10-21"), hoy))
                .isEqualTo(EstadoSla.POR_VENCER);
        assertThat(CalculadoraSla.estado(EstadoReclamo.REGISTRADO, LocalDate.parse("2026-10-22"), hoy))
                .isEqualTo(EstadoSla.EN_PLAZO);
        assertThat(CalculadoraSla.estado(EstadoReclamo.REGISTRADO, LocalDate.parse("2026-10-16"), hoy))
                .isEqualTo(EstadoSla.CRITICO);
    }
}
