// Prueba 3 · Pedidos con token (necesita tokens de G7).
// Se llama directo al servicio de Ventas (M1) para medir al servicio y no al rate limit del gateway.
//
// Escenarios (elige con -e ESCENARIO=...):
//   consulta  -> 5000 consultas al listado de pedidos con 50 usuarios a la vez (TOKEN_GESTOR, rol ADMIN_VENTAS)
//   creacion  -> crea CANTIDAD pedidos (por defecto 100) con el token del canal (TOKEN_CANAL, scope ventas:pedidos:crear)
//
//   k6 run -e ESCENARIO=consulta -e TOKEN_GESTOR=... performance/03-pedidos.js
//   k6 run -e ESCENARIO=creacion -e TOKEN_CANAL=... -e CLIENTE_ID=<sub en G7> performance/03-pedidos.js
//
// OJO: "creacion" escribe en la base real. Los pedidos quedan con el nombre "PRUEBA K6" para poder limpiarlos.
// Los tokens nunca se escriben en este archivo: van como variables (o secrets en GitHub Actions).

import http from 'k6/http';
import { check } from 'k6';
import { VENTAS, UMBRALES, resumen } from './comun.js';

const ESCENARIO = __ENV.ESCENARIO || 'consulta';
const CANTIDAD = parseInt(__ENV.CANTIDAD || '100', 10);
const CANAL = __ENV.CANAL || 'MARKETPLACE';   // debe coincidir con el client_id del token

const escenarios = {
  consulta: {
    executor: 'shared-iterations', vus: 50, iterations: 5000, maxDuration: '10m',
    exec: 'consultar',
  },
  creacion: {
    executor: 'shared-iterations', vus: 10, iterations: CANTIDAD, maxDuration: '10m',
    exec: 'crear',
  },
};

export const options = {
  scenarios: { [ESCENARIO]: escenarios[ESCENARIO] },
  thresholds: {
    ...UMBRALES,
    'http_req_duration{name:listar_pedidos}': ['p(95)<1000'],
    'http_req_duration{name:crear_pedido}': ['p(95)<1500'],
  },
};

function cabeceras(token, extra = {}) {
  if (!token) throw new Error('Falta el token: pasa -e TOKEN_GESTOR=... o -e TOKEN_CANAL=...');
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra };
}

export function setup() {
  http.get(`${VENTAS}/actuator/health`, { timeout: '120s' });
}

export function consultar() {
  const pagina = Math.floor(Math.random() * 5);
  const r = http.get(`${VENTAS}/api/v1/pedidos?pagina=${pagina}&tamano=10`, {
    headers: cabeceras(__ENV.TOKEN_GESTOR),
    tags: { name: 'listar_pedidos' },
  });
  check(r, { 'listado responde 200': (x) => x.status === 200 });
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const n = (Math.random() * 16) | 0;
    return (c === 'x' ? n : (n & 0x3) | 0x8).toString(16);
  });
}

export function crear() {
  const cuerpo = {
    canal: CANAL,
    contacto: {
      clienteId: __ENV.CLIENTE_ID || '11111111-1111-1111-1111-111111111111',
      nombreCompleto: 'PRUEBA K6',
      tipoDocumento: 'DNI',
      numeroDocumento: '72458912',
      telefono: '+51999888777',
      email: 'prueba.k6@example.com',
    },
    items: [{ productoId: 'PROD-101', sku: 'SKU-AUR-01', descripcion: 'Prueba de carga', cantidad: 1, precioUnitario: 129.9 }],
    envio: {
      modalidad: 'DELIVERY', costo: 0, destinatario: 'PRUEBA K6',
      departamento: 'Lima', provincia: 'Lima', distrito: 'San Miguel',
      direccion: 'Av. La Marina 1234', referencia: 'Prueba de carga',
    },
    pago: { metodoPago: 'TARJETA_CREDITO', moneda: 'PEN', subtotal: 129.9, descuentoCupon: 0, costoEnvio: 0, total: 129.9 },
  };
  const r = http.post(`${VENTAS}/api/v1/pedidos`, JSON.stringify(cuerpo), {
    headers: cabeceras(__ENV.TOKEN_CANAL, { 'Idempotency-Key': uuid() }),
    tags: { name: 'crear_pedido' },
  });
  check(r, { 'pedido creado (201)': (x) => x.status === 201 });
}

export const handleSummary = resumen(`03-pedidos-${ESCENARIO}`);
