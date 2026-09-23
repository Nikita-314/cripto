-- Evidence helpers for blacklist candidates (human review).
-- 1) Worst tickers by average EOD outcome
SELECT
  s.ticker,
  COUNT(*) AS signals_total,
  ROUND(AVG(o.outcome_eod_pct), 4) AS avg_eod_pct,
  ROUND(MIN(o.outcome_eod_pct), 4) AS worst_eod_pct,
  ROUND(AVG(o.mae), 4) AS avg_mae
FROM signals s
JOIN signal_outcomes o ON o.signal_id = s.signal_id
WHERE s.side IN ('BUY', 'SELL')
GROUP BY s.ticker
HAVING COUNT(*) >= 5
ORDER BY avg_eod_pct ASC
LIMIT 50;

-- 2) Losing closed paper trades
SELECT
  ticker,
  COUNT(*) AS closes,
  SUM(CASE WHEN CAST(json_extract(details_json, '$.net_pnl') AS REAL) < 0 THEN 1 ELSE 0 END) AS losses,
  ROUND(AVG(CAST(json_extract(details_json, '$.net_pnl') AS REAL)), 4) AS avg_net_pnl,
  ROUND(SUM(CAST(json_extract(details_json, '$.net_pnl') AS REAL)), 4) AS sum_net_pnl
FROM decision_logs
WHERE decision_type = 'TRADE_CLOSE'
  AND json_extract(details_json, '$.net_pnl') IS NOT NULL
GROUP BY ticker
HAVING COUNT(*) >= 5
ORDER BY avg_net_pnl ASC
LIMIT 50;
