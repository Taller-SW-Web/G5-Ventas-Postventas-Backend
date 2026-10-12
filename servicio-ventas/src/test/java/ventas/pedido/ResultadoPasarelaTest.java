package ventas.pedido;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** pago.estado en Supabase solo acepta PENDIENTE, CONFIRMADO y RECHAZADO. */
class ResultadoPasarelaTest {

    @Test
    void aprobadoSeGuardaComoConfirmado() {
        assertThat(ResultadoPasarela.APROBADO.aEstadoPago()).isEqualTo(EstadoPago.CONFIRMADO);
    }

    @Test
    void rechazadoSeGuardaComoRechazado() {
        assertThat(ResultadoPasarela.RECHAZADO.aEstadoPago()).isEqualTo(EstadoPago.RECHAZADO);
    }
}
