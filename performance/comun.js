// Configuración compartida de las pruebas de performance (Hito 5).
// Todas las URL se pueden cambiar con variables de entorno: k6 run -e GATEWAY_URL=... script.js

export const GATEWAY = __ENV.GATEWAY_URL || 'https://g5-api-gateway.onrender.com';
export const VENTAS = __ENV.VENTAS_URL || 'https://g5-servicio-ventas.onrender.com';
export const POSTVENTA = __ENV.POSTVENTA_URL || 'https://g5-ventas-postventas-backend.onrender.com';

// Criterio de aceptación común: 95 % de respuestas en menos de 800 ms y menos de 1 % de errores.
export const UMBRALES = {
  http_req_duration: ['p(95)<800'],
  checks: ['rate>0.99'],
};

// Resumen en texto y en JSON (para guardarlo como evidencia en GitHub Actions).
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export function resumen(nombre) {
  return (data) => ({
    stdout: textSummary(data, { indent: '  ', enableColors: true }),
    [`resultados/${nombre}.json`]: JSON.stringify(data, null, 2),
  });
}
