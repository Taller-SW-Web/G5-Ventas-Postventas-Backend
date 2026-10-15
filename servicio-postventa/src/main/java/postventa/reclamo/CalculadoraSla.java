package postventa.reclamo;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Plazo de 15 días hábiles del Libro de Reclamaciones (guía 10.2). Cuenta de lunes a viernes, sin feriados
 * (se puede agregar una lista fija de feriados de Perú más adelante).
 */
public final class CalculadoraSla {

    public static final int PLAZO_DIAS_HABILES = 15;

    private CalculadoraSla() {}

    /** Avanza día por día desde la fecha de registro (sin contarla) hasta sumar los días hábiles pedidos. */
    public static LocalDate sumarDiasHabiles(LocalDate desde, int dias) {
        LocalDate fecha = desde;
        int contados = 0;
        while (contados < dias) {
            fecha = fecha.plusDays(1);
            if (esHabil(fecha)) {
                contados++;
            }
        }
        return fecha;
    }

    /**
     * Días hábiles que faltan para la fecha límite: 0 si vence hoy, negativo si ya venció
     * (por ejemplo -3 = vencido hace 3 días hábiles).
     */
    public static int diasHabilesRestantes(LocalDate limite, LocalDate hoy) {
        if (!hoy.isAfter(limite)) {
            return habilesEntre(hoy, limite);
        }
        return -habilesEntre(limite, hoy);
    }

    /** Semáforo del plazo. Un reclamo ATENDIDO ya no corre plazo. */
    public static EstadoSla estado(EstadoReclamo estado, LocalDate limite, LocalDate hoy) {
        if (estado == EstadoReclamo.ATENDIDO) {
            return EstadoSla.ATENDIDO;
        }
        int restantes = diasHabilesRestantes(limite, hoy);
        if (restantes < 0) {
            return EstadoSla.VENCIDO;
        }
        if (restantes <= 2) {
            return EstadoSla.CRITICO;
        }
        return restantes <= 5 ? EstadoSla.POR_VENCER : EstadoSla.EN_PLAZO;
    }

    /** Días hábiles en (desde, hasta]. */
    private static int habilesEntre(LocalDate desde, LocalDate hasta) {
        int dias = 0;
        for (LocalDate f = desde.plusDays(1); !f.isAfter(hasta); f = f.plusDays(1)) {
            if (esHabil(f)) {
                dias++;
            }
        }
        return dias;
    }

    private static boolean esHabil(LocalDate fecha) {
        DayOfWeek dia = fecha.getDayOfWeek();
        return dia != DayOfWeek.SATURDAY && dia != DayOfWeek.SUNDAY;
    }
}
