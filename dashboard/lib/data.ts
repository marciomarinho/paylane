import { paymentApi, settlementApi, ledgerApi } from "./api";

// Render-facing shapes (kept explicit so the UI is stable regardless of spec naming quirks).
export type Payment = {
  id: string;
  merchantId: string;
  amountMinor: number;
  currency: string;
  status: string;
  createdAt: string;
};

export type Batch = {
  id: number;
  merchantId: string;
  grossMinor: number;
  feeMinor: number;
  payoutMinor: number;
  status: string;
  createdAt: string;
};

export type Balance = {
  code: string;
  name: string;
  type: string;
  currency: string;
  balanceMinor: number;
  postingCount: number;
};

const NO_STORE = { cache: "no-store" as const };

export async function getPayments(): Promise<Payment[]> {
  try {
    const { data } = await paymentApi.GET("/payments", {
      params: { query: { limit: 100 } },
      ...NO_STORE,
    });
    return (data as Payment[]) ?? [];
  } catch {
    return [];
  }
}

export async function getSettlements(): Promise<Batch[]> {
  try {
    const { data } = await settlementApi.GET("/settlements", NO_STORE);
    return (data as Batch[]) ?? [];
  } catch {
    return [];
  }
}

export async function getBalances(): Promise<Balance[]> {
  try {
    const { data } = await ledgerApi.GET("/accounts", NO_STORE);
    return (data as Balance[]) ?? [];
  } catch {
    return [];
  }
}

export function balanceOf(balances: Balance[], code: string): number {
  return balances.find((b) => b.code === code)?.balanceMinor ?? 0;
}
