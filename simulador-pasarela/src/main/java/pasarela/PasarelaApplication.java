package pasarela;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Simulador de la pasarela de pagos: cobros (avisa a M1) y extornos (los pide M2). */
@SpringBootApplication
public class PasarelaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PasarelaApplication.class, args);
    }
}
