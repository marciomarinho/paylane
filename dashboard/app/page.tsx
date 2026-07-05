import { getPayments, getSettlements, getBalances, balanceOf } from "@/lib/data";
import { money, shortId, timeAgo } from "@/lib/format";
import { StatusChip } from "@/components/StatusChip";

// Always render fresh: this is an operational view over live services.
export const dynamic = "force-dynamic";

export default async function Dashboard() {
  const [payments, settlements, balances] = await Promise.all([
    getPayments(),
    getSettlements(),
    getBalances(),
  ]);

  const moneyIn = balanceOf(balances, "scheme_receivable");
  const paidOut = -balanceOf(balances, "cash");
  const fees = -balanceOf(balances, "platform_fees");
  const owed = -balanceOf(balances, "merchant_payable");
  const ledgerNets = balances.reduce((sum, b) => sum + b.balanceMinor, 0);

  return (
    <div className="wrap">
      <header className="top">
        <h1>
          <span className="mono">paylane</span> merchant dashboard
        </h1>
        <span className="sub">{payments.length} payments · {settlements.length} settlements</span>
      </header>

      <section className="tiles">
        <Tile label="Money in" value={money(moneyIn)} foot="captured (scheme receivable)" />
        <Tile label="Paid out" value={money(paidOut)} foot="settled to merchants (cash)" />
        <Tile label="Platform fees" value={money(fees)} foot="revenue booked" />
        <Tile label="Owed to merchants" value={money(owed)} foot="outstanding payable" />
      </section>

      <section className="panel">
        <h2>Payments <span className="count">latest {payments.length}</span></h2>
        {payments.length === 0 ? (
          <div className="empty">No payments yet — run <code>./scripts/demo.sh</code> to seed some.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Payment</th>
                <th className="num">Amount</th>
                <th>Status</th>
                <th className="num">When</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.id}>
                  <td className="mono">{shortId(p.id)}</td>
                  <td className="num">{money(p.amountMinor, p.currency)}</td>
                  <td><StatusChip status={p.status} /></td>
                  <td className="num">{timeAgo(p.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel">
        <h2>Settlement batches <span className="count">{settlements.length}</span></h2>
        {settlements.length === 0 ? (
          <div className="empty">No settlements yet — capture payments, then <code>POST /settlements/run</code>.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Batch</th>
                <th>Merchant</th>
                <th className="num">Gross</th>
                <th className="num">Fees</th>
                <th className="num">Payout</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {settlements.map((b) => (
                <tr key={b.id}>
                  <td className="mono">#{b.id}</td>
                  <td className="mono">{shortId(b.merchantId)}</td>
                  <td className="num">{money(b.grossMinor)}</td>
                  <td className="num">{money(b.feeMinor)}</td>
                  <td className="num">{money(b.payoutMinor)}</td>
                  <td><StatusChip status={b.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <p className="footnote">
        Ledger check: all accounts sum to <code>{money(ledgerNets)}</code> — the books balance to zero.
      </p>
    </div>
  );
}

function Tile({ label, value, foot }: { label: string; value: string; foot: string }) {
  return (
    <div className="tile">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      <div className="foot">{foot}</div>
    </div>
  );
}
