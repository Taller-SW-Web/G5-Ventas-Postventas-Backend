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
        // Límite el viernes y hoy sábado: no hay día hábil de por medio, pero ya venció
        return -Math.max(1, habilesEntre(limite, hoy));
    }

    /**
     * Rango de fecha_limite_respuesta que corresponde a cada opción del filtro SLA de la bandeja, para filtrar en
     * la base sin calcular días hábiles en SQL. Las fechas límite siempre caen en día hábil, así que
     * "quedan k días hábiles" equivale a "límite = sumarDiasHabiles(hoy, k)". POR_VENCER incluye a CRITICO
     * (el filtro del mockup no tiene una opción "Crítico"). Un extremo null significa sin límite.
     * Aplica a reclamos no ATENDIDO; el filtro ATENDIDO se resuelve por estado, sin rango.
     */
    public static RangoLimite rangoLimite(EstadoSla sla, LocalDate hoy) {
        return switch (sla) {
            case VENCIDO -> new RangoLimite(null, hoy.minusDays(1));
            case CRITICO -> new RangoLimite(hoy, sumarDiasHabiles(hoy, 2));
            case POR_VENCER -> new RangoLimite(hoy, sumarDiasHabiles(hoy, 5));
            case EN_PLAZO -> new RangoLimite(sumarDiasHabiles(hoy, 6), null);
            case ATENDIDO -> new RangoLimite(null, null);
        };
    }

    /** Extremos inclusivos; null = abierto. */
    public record RangoLimite(LocalDate desde, LocalDate hasta) {}

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
