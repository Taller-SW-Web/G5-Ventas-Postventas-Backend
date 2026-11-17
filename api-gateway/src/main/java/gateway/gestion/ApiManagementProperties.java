package gateway.gestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Políticas de API Management del gateway (bloque {@code api-management} del application.yml).
 *
 * <p>Límites por minuto según el tipo de consumidor:
 * <ul>
 *   <li>{@code servicio}: token técnico de otro módulo (claim {@code tipo=servicio}), p. ej. Marketplace o Chatbot.</li>
 *   <li>{@code usuario}: token de una persona (cliente, asesor, administrador).</li>
 *   <li>{@code anonimo}: sin token verificado (solo ocurre con la seguridad deshabilitada); se limita por IP.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "api-management")
public record ApiManagementProperties(RateLimit rateLimit) {

    public ApiManagementProperties {
        if (rateLimit == null) {
            rateLimit = new RateLimit(true, 300, 120, 60, 10_000);
        }
    }

    /**
     * @param habilitado    si es false no se limita (útil en local)
     * @param servicio      solicitudes por minuto para tokens técnicos
     * @param usuario       solicitudes por minuto para tokens de usuario
     * @param anonimo       solicitudes por minuto por IP sin token
     * @param maxClientes   tope de consumidores en memoria; al superarlo se purgan los inactivos
     */
    public record RateLimit(boolean habilitado, int servicio, int usuario, int anonimo, int maxClientes) {

        public RateLimit {
            if (servicio <= 0) servicio = 300;
            if (usuario <= 0) usuario = 120;
            if (anonimo <= 0) anonimo = 60;
            if (maxClientes <= 0) maxClientes = 10_000;
        }
    }
}
