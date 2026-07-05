"use client";

import { useEffect, useRef, useState } from "react";
import {
  SEED_ROWS, SEED_EVENTS, SEED_MONEY, SEED_BATCHES, SEED_ACCOUNTS, SEED_TRIAL,
  SEED_JOURNAL, BENCH, FOOTER, MERCHANT_POOL,
  type Row, type Ev, type NodeState, type Batch, type Account,
} from "@/lib/seed";

type Money = { inMinor: number; feeMinor: number; paidMinor: number };

export type StageData = {
  rows?: Row[];
  money?: Money;
  accounts?: Account[];
  trial?: string;
  batches?: Batch[];
  live?: boolean;
};

const two = (n: number) => (n < 10 ? "0" + n : "" + n);
const nowStr = () => {
  const d = new Date();
  return `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
};
const fmt = (minor: number) =>
  new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(minor / 100);
const hex = (n: number) => {
  let s = "";
  for (let i = 0; i < n; i++) s += "0123456789abcdef"[Math.floor(Math.random() * 16)];
  return s;
};
const nodesFor = (state: number): [NodeState, NodeState, NodeState, NodeState] =>
  state === 0 ? ["now", "empty", "empty", "empty"]
    : state === 1 ? ["done", "now", "empty", "empty"]
    : ["done", "done", "now", "empty"];

function Track({ nodes }: { nodes: [NodeState, NodeState, NodeState, NodeState] }) {
  return (
    <div className="track">
      {nodes.map((nd, i) => (
        <span key={i} style={{ display: "contents" }}>
          {i > 0 && (
            <span className={"ln" + (nodes[i - 1] === "done" && nd !== "empty" ? " done" : "")} />
          )}
          <span className={"nd" + (nd === "empty" ? "" : " " + nd)} />
        </span>
      ))}
    </div>
  );
}

export default function Stage({ data }: { data: StageData }) {
  const initialRows = data.rows && data.rows.length ? data.rows : SEED_ROWS;
  const initialMoney = data.money ?? SEED_MONEY;
  const accounts = data.accounts && data.accounts.length ? data.accounts : SEED_ACCOUNTS;
  const trial = data.trial ?? SEED_TRIAL;
  const batches = data.batches && data.batches.length ? data.batches : SEED_BATCHES;

  const [rows, setRows] = useState<Row[]>(initialRows);
  const [events, setEvents] = useState<(Ev & { key: number; isnew?: boolean })[]>(
    SEED_EVENTS.map((e, i) => ({ ...e, key: i })),
  );
  const [money, setMoney] = useState<Money>(initialMoney);
  const [clock, setClock] = useState("--:--:--");
  const [running, setRunning] = useState(true);
  const [stack, setStack] = useState<"vt" | "wf">("vt");

  const pending = useRef<string[]>([]);
  const simState = useRef<Map<string, number>>(new Map());
  const seq = useRef(9);
  const evKey = useRef(SEED_EVENTS.length);
  const runningRef = useRef(true);
  useEffect(() => { runningRef.current = running; }, [running]);

  const pushEv = (src: string, msg: string) => {
    setEvents((prev) => {
      const next = [...prev, { t: nowStr(), src, msg, key: evKey.current++, isnew: true }];
      return next.slice(-12);
    });
  };

  useEffect(() => {
    const id = setInterval(() => setClock(nowStr()), 1000);
    setClock(nowStr());
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    const tick = setInterval(() => {
      if (!runningRef.current) return;
      const roll = Math.random();
      if (roll < 0.4 || pending.current.length === 0) {
        // new payment
        const id = "pay_" + hex(8);
        const merchant = MERCHANT_POOL[Math.floor(Math.random() * MERCHANT_POOL.length)];
        const amountMinor = Math.round(15 + Math.random() * 220) * 100;
        const feeMinor = Math.round(amountMinor * 0.029 + 30);
        simState.current.set(id, 0);
        pending.current.push(id);
        setRows((prev) => [
          { id, merchant, amountMinor, feeMinor, nodes: nodesFor(0), when: "just now",
            pill: { cls: "created", text: "CREATED" } },
          ...prev,
        ].slice(0, 7));
        pushEv("api", `POST /payments · <b>${id.slice(0, 12)}</b> $${fmt(amountMinor)} · key ${hex(4)}… MISS → 201`);
      } else {
        const id = pending.current[0];
        const s = (simState.current.get(id) ?? 0) + 1;
        simState.current.set(id, s);
        setRows((prev) => prev.map((r) => {
          if (r.id !== id) return r;
          if (s === 1) return { ...r, nodes: nodesFor(1), pill: { cls: "auth", text: "AUTHORIZED" } };
          return { ...r, nodes: nodesFor(2), pill: { cls: "captured", text: "CAPTURED" } };
        }));
        const short = id.slice(0, 12);
        if (s === 1) {
          pushEv("api", `${short} → <b>AUTHORIZED</b> · scheme 200 in ${20 + Math.floor(Math.random() * 90)}ms`);
        } else {
          const row = rows.find((r) => r.id === id);
          const amt = row?.amountMinor ?? 0;
          const fee = row?.feeMinor ?? 0;
          setMoney((m) => ({ ...m, inMinor: m.inMinor + amt, feeMinor: m.feeMinor + (fee || 0) }));
          pushEv("outbox", `tx commit + event row · <b>${short}</b>`);
          setTimeout(() => { if (runningRef.current) pushEv("sns", `publish <b>payment.captured</b> ${short} · msg ${hex(4)}…`); }, 500);
          setTimeout(() => { if (runningRef.current) pushEv("worker", `dedupe MISS → post journal J-${two(++seq.current)} <b>✓ balanced</b>`); }, 1100);
          pending.current.shift();
        }
      }
    }, 2600);
    return () => clearInterval(tick);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const extras: [string, () => string][] = [
      ["idem", () => `replay key ${hex(4)}… → <b>cached 201</b> · no double charge`],
      ["sqs", () => `deliver → settlement-worker · attempt 1 · ${hex(4)}…`],
      ["ledger", () => `trial balance recheck · <b>Σ = 0.00 ✓</b>`],
    ];
    const amb = setInterval(() => {
      if (!runningRef.current) return;
      const e = extras[Math.floor(Math.random() * extras.length)];
      pushEv(e[0], e[1]());
    }, 6800);
    return () => clearInterval(amb);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const owedMinor = money.inMinor - money.feeMinor - money.paidMinor;

  return (
    <div className="wrap">
      <header>
        <div className="brand">paylane<small>MERCHANT OPS</small></div>
        <div className="chip">ENV <b>LOCAL · LocalStack</b></div>
        <div className="seg" title="Which API twin is serving">
          <span className={stack === "vt" ? "on" : ""} onClick={() => setStack("vt")}>virtual threads</span>
          <span className={stack === "wf" ? "on" : ""} onClick={() => setStack("wf")}>webflux</span>
        </div>
        <div className="spacer" />
        <button className={"traffic" + (running ? "" : " paused")} onClick={() => setRunning((r) => !r)}>
          {running ? "● demo traffic — on" : "○ demo traffic — paused"}
        </button>
        <div className="clock mono">{clock}</div>
      </header>

      <section className="rail">
        <div className="flow">
          <div className="stat">
            <div className="lbl">MONEY IN</div>
            <div className="val num"><span className="cur">$</span>{fmt(money.inMinor)}</div>
            <div className="sub">captured · scheme receivable</div>
          </div>
          <div className="pipe fee">
            <div className="duct"><i /><i /></div>
            <div className="tag">platform fees <b>−${fmt(money.feeMinor)}</b> · 2.9% + 30¢</div>
          </div>
          <div className="stat">
            <div className="lbl">PAID OUT</div>
            <div className="val num"><span className="cur">$</span>{fmt(money.paidMinor)}</div>
            <div className="sub">settled to merchant cash</div>
          </div>
          <div className="pipe">
            <div className="duct"><i /></div>
            <div className="tag">next batch cut <b>17:00</b> · T+1</div>
          </div>
          <div className="stat">
            <div className="lbl">OWED TO MERCHANTS</div>
            <div className="val num"><span className="cur">$</span>{fmt(owedMinor)}</div>
            <div className="sub">merchant payable · target $0.00</div>
          </div>
        </div>
        <div className="seal">
          <div className="ring">✓</div>
          <div>
            <h3>Books balance — <span className="num">Σ = $0.00</span></h3>
            <p>Trial balance recomputed from the append-only journal on every posting. <span className="mono" style={{ fontSize: "10px" }}>Σ debits = Σ credits · invariant enforced in DB + domain</span></p>
          </div>
        </div>
      </section>

      <section className="grid-mid">
        <div className="panel">
          <div className="panel-h">
            <span className="tick" />
            <h2>Payment lifecycle</h2>
            <span className="cap">CREATED → AUTHORIZED → CAPTURED → SETTLED</span>
            <span className="right">{rows.length} payments</span>
          </div>
          <table>
            <thead>
              <tr><th>PAYMENT</th><th>MERCHANT</th><th className="amt">AMOUNT</th><th>STATE MACHINE</th><th /><th className="when">WHEN</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="id">{r.id.slice(0, 12)}</td>
                  <td className="merch">{r.merchant}</td>
                  <td className="amt">${fmt(r.amountMinor)}<small>{r.feeMinor == null ? "—" : "fee $" + fmt(r.feeMinor)}</small></td>
                  <td><Track nodes={r.nodes} /></td>
                  <td>
                    {r.pill && <span className={"st " + r.pill.cls}>{r.pill.text}</span>}
                    {r.badge && <span className={"bdg" + (r.badge.red ? " red" : "")}>{r.badge.text}</span>}
                  </td>
                  <td className="when">{r.when}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="panel">
          <div className="panel-h">
            <span className="tick" style={{ background: "var(--teal)" }} />
            <h2>Event pipeline</h2>
            <span className="cap">outbox → SNS → SQS → worker</span>
            <span className="right">live</span>
          </div>
          <div className="feed">
            {events.map((e) => (
              <div key={e.key} className={"ev" + (e.isnew ? " isnew" : "")}>
                <span className="t">{e.t}</span>
                <span className={"src " + e.src}>[{e.src}]</span>
                <span className="msg" dangerouslySetInnerHTML={{ __html: e.msg }} />
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="grid-bot">
        <div className="panel">
          <div className="panel-h">
            <span className="tick" style={{ background: "var(--violet)" }} />
            <h2>Settlement &amp; reconciliation</h2>
            <span className="right">invariant: Σ payments − fees = payout</span>
          </div>
          {batches.map((b) => (
            <div className="batch" key={b.id}>
              <div className="batch-h">
                <span className="id">{b.id}</span>
                <span className="mer">{b.context}</span>
                <span className={"st " + b.status.cls}>{b.status.text}</span>
              </div>
              <div className="eq" dangerouslySetInnerHTML={{ __html: b.eq }} />
              {b.note && <p className="note" dangerouslySetInnerHTML={{ __html: b.note }} />}
            </div>
          ))}
        </div>

        <div className="panel">
          <div className="panel-h">
            <span className="tick" style={{ background: "var(--gold)" }} />
            <h2>Double-entry ledger</h2>
            <span className="right">append-only · SERIALIZABLE</span>
          </div>
          {accounts.map((a) => (
            <div className="acct" key={a.name}>
              <span className="nm">{a.name}</span><span className="side">{a.side}</span>
              <span className={"bal " + a.drcr}>{a.bal}</span>
            </div>
          ))}
          <div className="trial">
            <svg width="14" height="14" viewBox="0 0 14 14"><path d="M2 7.5L5.5 11L12 3.5" fill="none" stroke="#3DDC97" strokeWidth="2" strokeLinecap="round" /></svg>
            <span className="num">{trial}</span>
          </div>
          <div className="jtail">
            {SEED_JOURNAL.map((j, i) => <div className="jl" key={i} dangerouslySetInnerHTML={{ __html: j }} />)}
          </div>
        </div>

        <div className="panel">
          <div className="panel-h">
            <span className="tick" style={{ background: "var(--teal)" }} />
            <h2>Same API, two stacks</h2>
            <span className="right">k6 · p99 latency · lower is better</span>
          </div>
          <div className="bench">
            {BENCH.map((b, i) => (
              <div className="brow" key={i}>
                <div className="cap"><span>{b.cap}</span><span>{b.right}</span></div>
                <div className="bar vt"><span className="who">VT</span><div className="tr"><div className="fl" style={{ width: b.vt.pct + "%" }} /></div><span className="ms">{b.vt.ms}</span></div>
                <div className="bar wf"><span className="who">WF</span><div className="tr"><div className="fl" style={{ width: b.wf.pct + "%" }} /></div><span className="ms">{b.wf.ms}</span></div>
              </div>
            ))}
            <div className="verdict">
              <b>Verdict: keep MVC on virtual threads.</b> Parity for IO-bound request/response until ~1k concurrent; WebFlux only pulls ahead at extremes — not worth the debugging model. <span className="mono">full method → /bench/results.md</span>
            </div>
          </div>
        </div>
      </section>

      <footer>
        {FOOTER.map((c, i) => <span className="chip" key={i} dangerouslySetInnerHTML={{ __html: c }} />)}
      </footer>
    </div>
  );
}
