# criptobot

Kotlin/JVM trading bot for **Binance Spot** with Telegram control, paper (test) USDT balance, analytics, adaptive rules, and the same signal engine approach as `autotorgbot`.

## Runtime

- Kotlin/JVM
- Java 21
- Gradle wrapper in `kotlin/`
- Market data: Binance public REST API (klines / tickers)
- Execution (current): virtual paper balance in USDT

## Quick Start

```bash
cp .env.example .env
# insert TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID into .env
./start.sh
```

## Telegram

Set in `.env`:

```text
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
```

Useful commands:

- `/status`
- `/strategy`
- `/pnl`
- `/balance`
- `/positions`
- `/symbols`
- `/add BTCUSDT`
- `/remove ETHUSDT`
- `/close_all_positions`
- `/balance_reset [сумма]`

## Universe

By default the bot loads **all** Binance `USDT` spot pairs (except leveraged tokens) and prioritizes `TV_SYMBOLS`:

```text
UNIVERSE_SOURCE=binance
TV_SYMBOLS=BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT
BINANCE_UNIVERSE_QUOTE=USDT
BINANCE_UNIVERSE_LIMIT=0
BINANCE_MIN_QUOTE_VOLUME=0
```

Risky pairs are **not** removed automatically. The bot accumulates analytics and proposes blacklist candidates; you approve or reject them in Telegram (`⛔ Чёрный список`, `/bl_approve`, `/bl_reject`).

For a fixed hand-picked list only:

```text
UNIVERSE_SOURCE=seed
```

## Paper balance

```text
TRADING_MODE=paper
PAPER_INITIAL_BALANCE_USDT=10000
POSITION_SIZE_USDT=100
```

Orders are simulated against live Binance prices. Commission defaults to 0.1% (spot-like).

## Analytics

```text
ANALYTICS_ENABLED=true
ANALYTICS_LOG_HOLDS=true
ANALYTICS_OUTCOME_EVAL_ENABLED=true
ADAPTIVE_MODE=paper
ADAPTIVE_USE_DB_VOLUME_LOOP=true
```

Decisions and outcomes go to SQLite (`analytics.db`), same pattern as autotorgbot.

## Build / test

```bash
cd kotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test --no-daemon
```
