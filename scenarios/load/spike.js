// Traffic spike on order-service for scenario S13 (traffic spike -> HighLatency -> scale out).
//
// Run it with ./scenarios/inject.sh scenario S13 (or inject.sh spike), which uses the grafana/k6
// Docker image on the Compose network, so nothing has to be installed. Each virtual user places
// an order and then lists all orders, with no pause. The list grows with every order, so the
// service gets slower the longer the spike lasts, the way a real hot endpoint does.
//
// Settings (k6 -e NAME=value):
//   BASE_URL   Nginx entry point            (default http://nginx:8080)
//   PEAK_VUS   virtual users at the peak    (default 200)
//   HOLD       how long the peak lasts      (default 3m)
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:8080';
const PEAK_VUS = parseInt(__ENV.PEAK_VUS || '200', 10);
const HOLD = __ENV.HOLD || '3m';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: PEAK_VUS }, // ramp up
        { duration: HOLD, target: PEAK_VUS },  // hold the peak until HighLatency fires
        { duration: '10s', target: 0 },        // ramp down
      ],
      gracefulRampDown: '5s',
    },
  },
  // Report the same p95 the HighLatency alert uses, without failing the run on it.
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'max'],
};

const headers = { 'Content-Type': 'application/json' };

export default function () {
  const sku = `SKU-${1 + Math.floor(Math.random() * 5)}`;
  const created = http.post(`${BASE_URL}/orders`, JSON.stringify({ sku, quantity: 1 }), { headers });
  check(created, { 'order created': (r) => r.status === 201 });

  const listed = http.get(`${BASE_URL}/orders`);
  check(listed, { 'orders listed': (r) => r.status === 200 });
}
