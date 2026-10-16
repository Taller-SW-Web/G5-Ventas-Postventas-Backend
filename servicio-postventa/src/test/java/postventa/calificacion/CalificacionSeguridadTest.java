package postventa.calificacion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import postventa.compartido.SecurityConfig;

/**
 * Prueba la autorización de F5 con seguridad activada.
 * Simula el decoder JWT y el servicio; no valida firmas reales de G7.
 */
@WebMvcTest(
        controllers = CalificacionController.class,
        properties = {
                "seguridad.habilitada=true",
                "seguridad.issuer=http://localhost/seguridad-prueba",
                "seguridad.jwks-url=http://localhost:1/no-se-utiliza",
                "seguridad.cliente-prueba=11111111-1111-1111-1111-111111111111"
        })
@Import(SecurityConfig.class)
class CalificacionSeguridadTest {

    private static final UUID CLIENTE =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String CUERPO = """
            {
              "pedidoId": "PED-2026-00981",
              "clienteId": "33333333-3333-3333-3333-333333333333",
              "canal": "MARKETPLACE",
              "puntuacion": 5,
              "comentario": "Buena experiencia"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CalificacionService servicio;

    @MockitoBean
    private JwtDecoder decoder;

    @Test
    void rnf02_sinTokenResponde401() throws Exception {
        mvc.perform(post("/api/v2/csat")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));

        verifyNoInteractions(servicio, decoder);
    }

    @Test
    void rnf02_clienteAutenticadoUsaElSubDelJwt() throws Exception {
        when(decoder.decode("token-cliente"))
                .thenReturn(usuario("token-cliente", "CLIENTE"));

        mvc.perform(post("/api/v2/csat")
                        .header("Authorization", "Bearer token-cliente")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isCreated());

        // CLIENTE es distinto del cliente de prueba configurado.
        verify(servicio).registrar(any(), eq(CLIENTE));
    }

    @Test
    void rnf02_gestorSinRolClienteNoPuedeRegistrarCsat() throws Exception {
        when(decoder.decode("token-gestor"))
                .thenReturn(usuario("token-gestor", "ADMIN_VENTAS"));

        mvc.perform(post("/api/v2/csat")
                        .header("Authorization", "Bearer token-gestor")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));

        verifyNoInteractions(servicio);
    }

    @Test
    void rnf02_scopeAntiguoDeCsatNoDaAcceso() throws Exception {
        Jwt tokenServicio = Jwt.withTokenValue("token-servicio")
                .header("alg", "RS256")
                .subject("modulo-marketplace")
                .claim("tipo", "servicio")
                .claim("aud", List.of("api-postventa"))
                .claim("scope", "postventa:csat:escribir")
                .build();

        when(decoder.decode("token-servicio"))
                .thenReturn(tokenServicio);

        mvc.perform(post("/api/v2/csat")
                        .header("Authorization", "Bearer token-servicio")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));

        verifyNoInteractions(servicio);
    }

    @Test
    void rnf02_tokenRechazadoPorDecoderResponde401() throws Exception {
        when(decoder.decode("token-invalido"))
                .thenThrow(new BadJwtException("Token inválido"));

        mvc.perform(post("/api/v2/csat")
                        .header("Authorization", "Bearer token-invalido")
                        .contentType("application/json")
                        .content(CUERPO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));

        verifyNoInteractions(servicio);
    }

    private Jwt usuario(String token, String rol) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(CLIENTE.toString())
                .claim("tipo", "acceso")
                .claim("roles", List.of(rol))
                .build();
    }
}
