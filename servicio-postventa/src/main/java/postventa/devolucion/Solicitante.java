package postventa.devolucion;

import java.util.UUID;

/**
 * Quién hace la petición, según el token (acuerdo A10): el cliente titular (rol CLIENTE), el Gestor
 * (rol ADMIN_VENTAS) o un canal con token de servicio. id es el sub del token de usuario; null para un servicio.
 */
public record Solicitante(Rol rol, UUID id) {

    public enum Rol { CLIENTE, GESTOR, SERVICIO }

    public static Solicitante cliente(UUID id) { return new Solicitante(Rol.CLIENTE, id); }

    public static Solicitante gestor(UUID id) { return new Solicitante(Rol.GESTOR, id); }

    public static Solicitante servicio() { return new Solicitante(Rol.SERVICIO, null); }

    public boolean esCliente() { return rol == Rol.CLIENTE; }

    public boolean esGestor() { return rol == Rol.GESTOR; }
}
