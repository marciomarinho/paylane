import { paymentApi, settlementApi, ledgerApi } from "./api";
import { timeAgo } from "./format";
import type { Row, Account, Batch, NodeState } from "./seed";
import type { StageData } from "@/components/Stage";

const NO_STORE = { cache: "no-store" as const };
const money = (minor: number) =>
  new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(minor / 100);
const shortId = (id: string) => id.replace(/-/g, "").slice(0, 8);
const feeFor = (amountMinor: number) => Math.round(amountMinor * 0.029) + 30;

const NODES: Record<string, [NodeState, NodeState, NodeState, NodeState]> = {
  CREATED: ["now", "empty", "empty", "empty"],
  AUTHORIZED: ["done", "now", "empty", "empty"],
  CAPTURED: ["done", "done", "done", "empty"],
  SETTLED: ["done", "done", "done", "done"],
  FAILED: ["done", "dead", "empty", "empty"],
};
const PILL: Record<string, { cls: string; text: string }> = {
  CREATED: { cls: "created", text: "CREATED" },
  AUTHORIZED: { cls: "auth", text: "AUTHORIZED · capture pending" },
  CAPTURED: { cls: "captured", text: "CAPTURED" },
  SETTLED: { cls: "settled", text: "SETTLED" },
};

/**
 * Pull the current state from the real services via the OpenAPI-typed clients and shape it for the
 * redesign. Returns {} on any failure or an empty backend, so the Stage falls back to the seeded
 * demo (which carries the designed engineering stories). The live event feed stays simulated —
 * swap it for an SSE `/events` stream in production.
 */
export async function getLive(): Promise<StageData> {
  try {
    const [payRes, merchRes, balRes, batchRes] = await Promise.all([
      paymentApi.GET("/payments", { params: { query: { limit: 7 } }, ...NO_STORE }),
      paymentApi.GET("/merchants", NO_STORE),
      ledgerApi.GET("/accounts", NO_STORE),
      settlementApi.GET("/settlements", NO_STORE),
    ]);

    const payments = (payRes.data as any[]) ?? [];
    if (payments.length === 0) return {}; // let the seed stories show

    const names = new Map<string, string>(
      ((merchRes.data as any[]) ?? []).map((m) => [m.id, m.name]),
    );

    const rows: Row[] = payments.map((p) => {
      const status = String(p.status);
      const amountMinor = Number(p.amountMinor);
      return {
        id: "pay_" + shortId(p.id),
        merchant: names.get(p.merchantId) ?? shortId(p.merchantId),
        amountMinor,
        feeMinor: status === "FAILED" ? null : feeFor(amountMinor),
        nodes: NODES[status] ?? NODES.CREATED,
        when: timeAgo(p.createdAt),
        pill: PILL[status] ?? PILL.CREATED,
      };
    });

    const balances = (balRes.data as any[]) ?? [];
    const bal = (code: string) => balances.find((b) => b.code === code)?.balanceMinor ?? 0;
    const money_ = {
      inMinor: bal("scheme_receivable"),
      feeMinor: -bal("platform_fees"),
      paidMinor: -bal("cash"),
    };

    const order = ["scheme_receivable", "merchant_payable", "platform_fees", "cash"];
    const normalCr = (type: string) => type !== "ASSET";
    let drSum = 0, crSum = 0;
    const accounts: Account[] = order
      .map((code) => balances.find((b) => b.code === code))
      .filter(Boolean)
      .map((b: any) => {
        const v = b.balanceMinor as number;
        const isCr = v < 0 || (v === 0 && normalCr(b.type));
        if (isCr) crSum += Math.abs(v); else drSum += v;
        return {
          name: b.code,
          side: b.type,
          bal: (isCr ? "Cr " : "Dr ") + money(Math.abs(v)),
          drcr: (isCr ? "cr" : "dr") as "cr" | "dr",
        };
      });
    const trial = `Σ Dr ${money(drSum)} = Σ Cr ${money(crSum)} — trial balance ${drSum === crSum ? "holds" : "BROKEN"}`;

    const batches: Batch[] = ((batchRes.data as any[]) ?? []).slice(0, 3).map((b) => {
      const settled = b.status === "SETTLED";
      const suspended = b.status === "SUSPENDED";
      return {
        id: "batch #" + b.id,
        context: shortId(b.merchantId) + (settled ? " · paid" : ""),
        status: { cls: suspended ? "suspended" : settled ? "settled" : "auth", text: b.status },
        eq: `${money(b.grossMinor)} − ${money(b.feeMinor)} = <b>${money(b.payoutMinor)}</b>` +
          (settled ? ' <span class="ok">✓ paid out</span>' : suspended ? ' <span class="bad">parked</span>' : ""),
      };
    });

    return { rows, money: money_, accounts, trial, batches: batches.length ? batches : undefined, live: true };
  } catch {
    return {};
  }
}
