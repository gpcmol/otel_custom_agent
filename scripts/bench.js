/**
 * k6 load script for the OTel custom agent overhead benchmark.
 *
 * Sends POST /cars requests with random car + passenger JSON bodies to the car-app.
 * Iterations and VUs are controlled via __ENV (ITERATIONS, VUS) by bench.sh.
 * The official k6 summary is printed to stdout by default (k6's built-in output);
 * bench.sh captures it and includes it in the final markdown report.
 *
 * @summary k6 load generator for POST /cars benchmark
 */

import http from 'k6/http';
import { check } from 'k6';

/** Target URL for POST /cars; overridable via BASE_URL env var (set by bench.sh). */
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8081/cars';

/** Random brands matching the agent's dynamic Car.brand config from run-agent.sh. */
const BRANDS = ['bmw', 'audi', 'vw', 'mercedes', 'toyota', 'honda', 'ford', 'renault'];

/** Random models so Car.model variation is realistic. */
const MODELS = ['m3', 'a4', 'golf', 'c220', 'corolla', 'civic', 'focus', 'clio'];

/** Random colours for Car.color. */
const COLORS = ['red', 'blue', 'black', 'white', 'silver', 'green', 'yellow', 'grey'];

/** Fuel types for Car.fuelType. */
const FUELS = ['petrol', 'diesel', 'hybrid', 'electric'];

/** Transmissions for Car.transmission. */
const TRANSMISSIONS = ['manual', 'automatic'];

/** Random passenger names so Car.passengers[1].name variation is realistic. */
const NAMES = [
  'Jan', 'Piet', 'Kees', 'Maria', 'Anna', 'Tom', 'Eva', 'Liu', 'Hiro', 'Sofia',
  'Noah', 'Emma', 'Liam', 'Olivia', 'Noa', 'Eve', 'Mia', 'Lucas', 'Chloe', 'Jasper',
];

/**
 * k6 options: shared-iterations executor with configurable iterations/VUs.
 * summaryTrendStats extends the default trend stats to include p(99) for the final report.
 * Thresholds fail the run if >1% of requests error or <99% of checks pass.
 */
export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    load: {
      executor: 'shared-iterations',
      iterations: parseInt(__ENV.ITERATIONS || '1000', 10),
      vus: parseInt(__ENV.VUS || '50', 10),
      maxDuration: '60m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

/**
 * Pick a random element from an array.
 * @param {Array<*>} arr
 * @returns {*}
 */
function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/**
 * Build a random car JSON body covering all 10 Car properties so every dynamic rule
 * in the config (brand, model, year, color, licensePlate, vin, mileage, fuelType,
 * transmission, passengers) has realistic data to resolve. Always 2–3 passengers
 * so index [1] is valid.
 * @returns {string} JSON string for POST /cars.
 */
function randomCarJson() {
  const passengers = 2 + Math.floor(Math.random() * 2);
  const list = [];
  for (let i = 0; i < passengers; i++) {
    list.push({ name: pick(NAMES) });
  }
  return JSON.stringify({
    brand: pick(BRANDS),
    model: pick(MODELS),
    year: 1990 + Math.floor(Math.random() * 35),
    color: pick(COLORS),
    licensePlate: 'NL-' + Math.random().toString(36).slice(2, 8).toUpperCase(),
    vin: 'WBA' + Math.floor(Math.random() * 1e12).toString().padStart(12, '0'),
    mileage: Math.floor(Math.random() * 250000),
    fuelType: pick(FUELS),
    transmission: pick(TRANSMISSIONS),
    passengers: list,
  });
}

/**
 * Default k6 function — one POST /cars request per iteration.
 * Asserts a 201 response via k6 check (counts as a failure if non-201).
 */
export default function () {
  const res = http.post(BASE_URL, randomCarJson(), {
    headers: { 'Content-Type': 'application/json' },
    timeout: '30s',
  });
  check(res, { 'status 201': (r) => r.status === 201 });
}