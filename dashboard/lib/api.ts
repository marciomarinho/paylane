import createClient from "openapi-fetch";
import type { paths as PaymentPaths } from "./generated/payment-api";
import type { paths as SettlementPaths } from "./generated/settlement";
import type { paths as LedgerPaths } from "./generated/ledger";

// Server-side base URLs (compose service names in Docker, localhost for local dev).
const PAYMENT_URL = process.env.PAYMENT_API_URL ?? "http://localhost:8081";
const SETTLEMENT_URL = process.env.SETTLEMENT_URL ?? "http://localhost:8083";
const LEDGER_URL = process.env.LEDGER_URL ?? "http://localhost:8082";

// Typed clients — request/response shapes come straight from each service's committed OpenAPI spec.
export const paymentApi = createClient<PaymentPaths>({ baseUrl: PAYMENT_URL });
export const settlementApi = createClient<SettlementPaths>({ baseUrl: SETTLEMENT_URL });
export const ledgerApi = createClient<LedgerPaths>({ baseUrl: LEDGER_URL });
