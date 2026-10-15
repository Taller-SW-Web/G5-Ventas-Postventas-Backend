package postventa.reclamo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Plazo de 15 días hábiles (ESP-13 CA-03) y semáforo del SLA (propuesta UX 01). */
class CalculadoraSlaTest {

    static final LocalDate LIMITE = LocalDate.parse("2026-10-14"); // miércoles

    @Test
    void ca03_quinceDiasHabilesComoElEjemploDelContrato() {
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
}
