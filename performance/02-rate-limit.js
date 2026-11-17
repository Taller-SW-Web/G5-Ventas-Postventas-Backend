// Prueba 2 · Rate limit del API Gateway.
// Sin token, el gateway permite 60 llamadas por minuto por IP (token bucket: la cubeta tiene 60 permisos
// y recarga 1 por segundo). Enviamos 150 llamadas en ráfaga con 10 usuarios: las primeras ~60 responden
// 401 (no hay token) y, al vaciarse la cubeta, el resto responde 429 (límite superado) con Retry-After.
//
//   k6 run performance/02-rate-limit.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY, resumen } from './comun.js';

const respuestas401 = new Counter('respuestas_401');
const respuestas429 = new Counter('respuestas_429');

export const options = {
  scenarios: {
    rafaga: { executor: 'shared-iterations', vus: 10, iterations: 150, maxDuration: '50s' },
  },
  thresholds: {
    respuestas_429: ['count>=30'],      // el límite tiene que activarse
    'checks{tipo:limite}': ['rate==1'], // todo 429 trae Retry-After
  },
};

export function setup() {
  http.get(`${GATEWAY}/actuator/health`, { timeout: '120s' });
}

export default function () {
  const r = http.get(`${GATEWAY}/api/v1/pedidos/me`, { tags: { name: 'pedidos_me_sin_token' } });
  if (r.status === 401) {
    respuestas401.add(1);
    check(r, { 'trae X-RateLimit-Remaining': (x) => x.headers['X-Ratelimit-Remaining'] !== undefined });
  } else if (r.status === 429) {
    respuestas429.add(1);
    check(r, { '429 trae Retry-After': (x) => x.headers['Retry-After'] !== undefined }, { tipo: 'limite' });
  }
  check(r, { 'solo 401 o 429 (nunca 200)': (x) => x.status === 401 || x.status === 429 });
}

export const handleSummary = resumen('02-rate-limit');
