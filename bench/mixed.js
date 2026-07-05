// Mixed read/write payment workload, run identically against both twins.
// Each iteration: create+authorize a payment (write), capture it (write), read it back (read).
// Env: BASE_URL, VUS, DURATION, SCHEME_DELAY (ms, optional), RUN_ID, OUT (summary path).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const DELAY = __ENV.SCHEME_DELAY || '';
const RUN = __ENV.RUN_ID || 'run';

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '15s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const res = http.post(`${BASE}/merchants`,
    JSON.stringify({ name: 'Bench Merchant', settlementAccount: 'BSB-000-benchmark' }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `bench-merchant-${RUN}` } });
  return { merchantId: res.json('id') };
}

function headers(key) {
  const h = { 'Content-Type': 'application/json', 'Idempotency-Key': key };
  if (DELAY) h['X-Scheme-Delay-Ms'] = DELAY;
  return h;
}

export default function (data) {
  const key = `${RUN}-${__VU}-${__ITER}`;

  const create = http.post(`${BASE}/payments`,
    JSON.stringify({ merchantId: data.merchantId, amountMinor: 1200, currency: 'AUD' }),
    { headers: headers(`c-${key}`) });
  check(create, { 'created (201)': (r) => r.status === 201 });
  const id = create.json('id');
  if (!id) return;

  const cap = http.post(`${BASE}/payments/${id}/capture`, null, { headers: headers(`x-${key}`) });
  check(cap, { 'captured (200)': (r) => r.status === 200 });

  const get = http.get(`${BASE}/payments/${id}`);
  check(get, { 'read (200)': (r) => r.status === 200 });
}

function fmt(v) { return v == null ? '?' : `${v.toFixed(1)}ms`; }

export function handleSummary(data) {
  const m = data.metrics;
  const g = (metric, field) =>
    (m[metric] && m[metric].values[field] != null) ? m[metric].values[field] : null;

  const out = {
    target: BASE,
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    schemeDelayMs: DELAY ? Number(DELAY) : 0,
    http_reqs: g('http_reqs', 'count'),
    rps: g('http_reqs', 'rate'),
    failed_rate: g('http_req_failed', 'rate'),
    lat_avg: g('http_req_duration', 'avg'),
    lat_p50: g('http_req_duration', 'med'),
    lat_p95: g('http_req_duration', 'p(95)'),
    lat_p99: g('http_req_duration', 'p(99)'),
    lat_max: g('http_req_duration', 'max'),
  };

  const file = __ENV.OUT || 'bench/out/summary.json';
  const res = {};
  res[file] = JSON.stringify(out, null, 2);
  res['stdout'] =
    `\n${BASE}  vus=${out.vus} delay=${out.schemeDelayMs}ms  ->  ` +
    `rps=${out.rps ? out.rps.toFixed(0) : '?'}  p50=${fmt(out.lat_p50)}  ` +
    `p99=${fmt(out.lat_p99)}  fail=${(out.failed_rate * 100).toFixed(2)}%\n`;
  return res;
}
