package postventa.devolucion;

import java.util.UUID;

/**
 * Quién hace la petición, según el token (acuerdo A10): el cliente titular (rol CLIENTE), el Gestor
 * (rol ADMIN_VENTAS) o un canal con token de servicio. id es el sub del token de usuario; null para un servicio.
 * modulo es el sub del token de servicio (modulo-marketplace); null para un usuario.
 */
public record Solicitante(Rol rol, UUID id, String modulo) {

    public enum Rol { CLIENTE, GESTOR, SERVICIO }

    public static Solicitante cliente(UUID id) { return new Solicitante(Rol.CLIENTE, id, null); }

    public static Solicitante gestor(UUID id) { return new Solicitante(Rol.GESTOR, id, null); }

    public static Solicitante servicio() { return servicio(null); }

    public static Solicitante servicio(String modulo) { return new Solicitante(Rol.SERVICIO, null, modulo); }

    public boolean esCliente() { return rol == Rol.CLIENTE; }

    public boolean esGestor() { return rol == Rol.GESTOR; }

    /** Cómo queda en el historial: CLIENTE y GESTOR con su sub, el canal con su módulo. */
    public ActorDevolucion actor() {
        return switch (rol) {
            case CLIENTE -> ActorDevolucion.CLIENTE;
            case GESTOR -> ActorDevolucion.GESTOR;
            case SERVICIO -> ActorDevolucion.CANAL;
        };
    }

    public String idActor() {
        return id != null ? id.toString() : modulo;
    }
}
