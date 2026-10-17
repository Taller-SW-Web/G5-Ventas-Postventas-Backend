package pasarela.cobro;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Ejecuta el procesamiento y las notificaciones fuera de la petición HTTP.
 */
@Configuration(proxyBeanMethods = false)
public class CobroConfiguration {

    @Bean
    public ThreadPoolTaskScheduler cobroTaskScheduler() {
        ThreadPoolTaskScheduler planificador = new ThreadPoolTaskScheduler();

        planificador.setPoolSize(4);
        planificador.setThreadNamePrefix("cobro-");

        return planificador;
    }
}
