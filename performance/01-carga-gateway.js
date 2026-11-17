// Prueba 1 · Carga y pico sobre el API Gateway (sin token).
// Mide cuánto tarda el gateway en responder y si aguanta varios usuarios a la vez.
// Usa /actuator/health porque es público y no tiene rate limit: así medimos al gateway, no al límite.
//
//   k6 run performance/01-carga-gateway.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { GATEWAY, VENTAS, POSTVENTA, UMBRALES, resumen } from './comun.js';

export const options = {
  setupTimeout: '5m',   // Render gratis tarda hasta ~1 minuto por servicio en despertar
  scenarios: {
    // Carga normal: 20 usuarios durante 1 minuto
    carga_normal: {
      executor: 'constant-vus', vus: 20, duration: '1m',
    },
    // Pico: sube de 0 a 100 usuarios, se mantiene y baja
    pico: {
      executor: 'ramping-vus', startTime: '1m10s', startVUs: 0,
      stages: [
        { duration: '20s', target: 100 },
        { duration: '30s', target: 100 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    ...UMBRALES,
    'http_req_duration{scenario:carga_normal}': ['p(95)<800'],
    'http_req_duration{scenario:pico}': ['p(95)<1500'],
  },
};

// Despierta los servicios de Render (plan gratis) antes de medir
export function setup() {
  for (const url of [GATEWAY, VENTAS, POSTVENTA]) {
    http.get(`${url}/actuator/health`, { timeout: '120s', tags: { name: 'despertar' } });
  }
}

export default function () {
  const r = http.get(`${GATEWAY}/actuator/health`, { tags: { name: 'gateway_health' } });
  check(r, {
    'responde 200': (x) => x.status === 200,
    'estado UP': (x) => x.body && x.body.includes('UP'),
  });
  sleep(1);
}

export const handleSummary = resumen('01-carga-gateway');
