// The design's exact opening state — used when no live backend data is available. Each row and
// event stages a specific engineering story (idempotent replay, a declined 4xx, a poison→DLQ,
// a reconciliation that parks a rounding-bug batch).

export type NodeState = "done" | "now" | "dead" | "empty";

export type Row = {
  id: string;
  merchant: string;
  amountMinor: number;
  feeMinor: number | null;
  nodes: [NodeState, NodeState, NodeState, NodeState];
  when: string;
  pill?: { cls: string; text: string };
  badge?: { text: string; red?: boolean };
};

export type Ev = { t: string; src: string; msg: string };

export type Batch = {
  id: string;
  context: string;
  status: { cls: string; text: string };
  eq: string; // HTML: uses <b>, <span class="ok|bad">
  note?: string; // HTML
};

export type Account = { name: string; side: string; bal: string; drcr: "dr" | "cr" };

export type BenchRow = {
  cap: string;
  right: string;
  vt: { pct: number; ms: string };
  wf: { pct: number; ms: string };
};

export const SEED_MONEY = { inMinor: 25000, feeMinor: 815, paidMinor: 24185 };

export const SEED_ROWS: Row[] = [
  { id: "pay_6d2d5a5e", merchant: "Northside Records", amountMinor: 12000, feeMinor: 378,
    nodes: ["done", "done", "done", "done"], when: "16m ago",
    pill: { cls: "settled", text: "SETTLED · batch #1" } },
  { id: "pay_d3a7e551", merchant: "Alpine Cellars", amountMinor: 8000, feeMinor: 262,
    nodes: ["done", "done", "done", "empty"], when: "16m ago",
    badge: { text: "replay ×2 → same 201" } },
  { id: "pay_01683edc", merchant: "Bloom & Co", amountMinor: 5000, feeMinor: 176,
    nodes: ["done", "done", "done", "empty"], when: "16m ago",
    pill: { cls: "captured", text: "CAPTURED" } },
  { id: "pay_8c1f02aa", merchant: "Harbour Kiosk", amountMinor: 6450, feeMinor: 217,
    nodes: ["done", "now", "empty", "empty"], when: "4m ago",
    pill: { cls: "auth", text: "AUTHORIZED · capture pending" } },
  { id: "pay_5e77b9c3", merchant: "Alpine Cellars", amountMinor: 21000, feeMinor: null,
    nodes: ["done", "dead", "empty", "empty"], when: "9m ago",
    badge: { text: "declined · no retry (4xx)", red: true } },
  { id: "pay_f0a3d971", merchant: "Bloom & Co", amountMinor: 3520, feeMinor: 132,
    nodes: ["now", "empty", "empty", "empty"], when: "just now",
    pill: { cls: "created", text: "CREATED" } },
];

export const SEED_EVENTS: Ev[] = [
  { t: "17:41:52", src: "outbox", msg: "scan → <b>1 pending</b> event row (tx-committed)" },
  { t: "17:41:52", src: "sns", msg: "publish <b>payment.captured</b> pay_01683edc · msg 7f2e…" },
  { t: "17:41:53", src: "sqs", msg: "deliver → settlement-worker · attempt 1" },
  { t: "17:41:53", src: "worker", msg: "dedupe <b>MISS</b> → process · post journal J-0006" },
  { t: "17:41:53", src: "ledger", msg: "Dr receivable 50.00 · Cr payable 48.24 · Cr fees 1.76 <b>✓ balanced</b>" },
  { t: "17:41:58", src: "sqs", msg: "redeliver msg 9d11… · attempt 4 of 4" },
  { t: "17:41:58", src: "dlq", msg: "<b>⚠ parked poison message</b> after 4 attempts → /dlq" },
  { t: "17:42:03", src: "idem", msg: "POST /payments · key af02… <b>REPLAY</b> → cached 201" },
  { t: "17:42:08", src: "api", msg: "otel span payment.capture · trace 3c9a… · 12ms" },
];

export const SEED_BATCHES: Batch[] = [
  { id: "batch #2", context: "cut 17:00 · 2 merchants", status: { cls: "auth", text: "SETTLING" },
    eq: '130.00 − 4.38 = <b>125.62</b> <span class="ok">✓ reconciles</span>' },
  { id: "batch #1", context: "759f75a8 · Northside", status: { cls: "settled", text: "SETTLED" },
    eq: '250.00 − 8.15 = <b>241.85</b> <span class="ok">✓ paid out</span>' },
  { id: "batch #0", context: "seed data · demo", status: { cls: "suspended", text: "SUSPENDED" },
    eq: '200.00 − 6.52 = 193.48 <span class="bad">≠ 193.47 payout</span>',
    note: "<em>Off by $0.01</em> — reconciliation parked the batch instead of paying out. The seeded rounding bug the invariant is designed to catch; inspect &amp; release from review." },
];

export const SEED_ACCOUNTS: Account[] = [
  { name: "scheme_receivable", side: "ASSET", bal: "Dr 250.00", drcr: "dr" },
  { name: "merchant_payable", side: "LIABILITY", bal: "Cr 0.00", drcr: "cr" },
  { name: "platform_fees", side: "REVENUE", bal: "Cr 8.15", drcr: "cr" },
  { name: "cash", side: "ASSET", bal: "Cr 241.85", drcr: "cr" },
];

export const SEED_TRIAL = "Σ Dr 491.85 = Σ Cr 491.85 — trial balance holds";

export const SEED_JOURNAL = [
  "<b>J-0007</b> capture pay_6d2d · Dr receivable 120.00 · Cr payable 116.22 · Cr fees 3.78",
  "<b>J-0008</b> payout batch#1 · Dr payable 241.85 · Cr cash 241.85",
  "<b>J-0009</b> reversal J-0004 · corrections are new entries, never updates",
];

export const BENCH: BenchRow[] = [
  { cap: "64 concurrent", right: "req/s ≈ 3.1k", vt: { pct: 9, ms: "18 ms" }, wf: { pct: 9.5, ms: "19 ms" } },
  { cap: "1,024 concurrent", right: "req/s ≈ 11.8k", vt: { pct: 20, ms: "41 ms" }, wf: { pct: 16, ms: "33 ms" } },
  { cap: "slow downstream · 200 ms stubbed card scheme", right: "the interesting one",
    vt: { pct: 99, ms: "212 ms" }, wf: { pct: 97, ms: "209 ms" } },
];

export const FOOTER = [
  "Java 21 · <b>virtual threads</b>",
  "Spring Boot 3.3",
  "outbox → <b>SNS/SQS</b> · LocalStack",
  "Postgres <b>SERIALIZABLE</b> + retry",
  "OpenTelemetry → Tempo",
  "k6 · Testcontainers · jqwik",
  "github.com/<b>marciomarinho/paylane</b>",
];

export const MERCHANT_POOL = ["Alpine Cellars", "Bloom & Co", "Northside Records", "Harbour Kiosk", "Fern St Bakery"];
