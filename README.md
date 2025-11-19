# CSCC43 Portfolio CLI

Java 21 / Maven command-line application for the CSCC43 course project. The CLI connects to PostgreSQL to manage portfolios, friends, shared stock lists, reviews, price history, and analytics.

---

## 1. Capabilities

- **Authentication & Users** – Register users, list registered accounts, log in/out, track the active session.
- **Portfolios** – Create portfolios, deposit/withdraw cash, record stock trades, inspect holdings and summary valuations.
- **Friends** – Send/accept/reject/cancel friend requests and view the friend list with timing data.
- **Stock Lists** – Build named lists, control visibility (private/friends/public), share with friends, and manage constituent symbols.
- **Reviews** – Add, update, list, and delete reviews on accessible lists with automatic visibility enforcement.
- **Prices** – Import historic CSV data or insert ad-hoc daily prices; both feed into the `unified_prices` view.
- **Analytics** – Compute per-symbol metrics (average close, volatility, COV, beta), produce correlation matrices, retrieve history, and run simple linear forecasts.

All features are exposed through Picocli commands; use `help` inside the shell or `--help` on any subcommand for option details.

---

## 2. Environment Setup

### Required tooling

- Java 21 (JDK) – `java -version`
- Maven 3.8+ – `mvn -v`
- PostgreSQL 13+ reachable from the CLI host
- Docker Desktop (optional, for local development using the supplied compose file)

### Configuration

The CLI reads connection settings from environment variables (defaults shown):

```bash
export DB_URL="jdbc:postgresql://127.0.0.1:5433/cscc43"
export DB_USER="cscc43"
export DB_PASSWORD="cscc43"
export DB_POOL_SIZE=8
```

Override these values to point at any reachable PostgreSQL server.

---

## 3. Running Locally with Docker

1. **Start Postgres**

	```bash
	docker compose up -d
	```

	The container exposes port `5433` and seeds the schema via `postgres/init/01-schema.sql` the first time it starts.

2. **Build the CLI**

	```bash
	mvn clean package
	```

3. **Launch the shell**

	```bash
	mvn exec:java
	```

	Type commands at the prompt (see Section 5). Use `exit` to quit.

4. **Stop Postgres when done**

	```bash
	docker compose down
	```

---

## 4. Running Against an Existing Postgres Server (e.g., VM deployment)

1. Ensure the database has the project schema:

	```bash
	psql "$DB_URL" -f postgres/init/01-schema.sql
	```

	(Replace `$DB_URL` with a connection string that includes credentials/host/port.)

2. Export environment variables that target the remote server, e.g.:

	```bash
	export DB_URL="jdbc:postgresql://10.0.0.12:5432/cscc43"
	export DB_USER="app_user"
	export DB_PASSWORD="********"
	```

3. Build and run exactly as on a local machine:

	```bash
	mvn clean package
	mvn exec:java
	```

4. If the VM hosts the application long-term, wrap `mvn exec:java` in a service manager (systemd, tmux, or similar) so the CLI remains available.

---

## 5. CLI Walkthrough

Command syntax examples (runnable once the shell is open):

```text
users register --name "Alice" --email alice@example.com --password secret
auth login --email alice@example.com --password secret
portfolios create --name "Core"
portfolios deposit --portfolio-id 1 --amount 10000
portfolios buy --portfolio-id 1 --symbol AAPL --shares 10 --price 150
friends request --user-id 2
friends respond --request-id 5 --action accept
lists create --name "Tech Watch" --visibility public
lists add-item --list-id 3 --symbol MSFT --shares 15
reviews upsert --list-id 3 --body "Looks diversified"
prices import --file ./data/SP500History.csv
analytics portfolio --portfolio-id 1 --lookback-days 180 --market SPY
analytics predict --symbol MSFT --horizon-days 30
```

- `help` – show all root commands.
- `<command> --help` – show options for a specific command.
- `exit`/`quit` – leave the interactive shell.

---

## 6. Working With Price Data

- **Bulk import** historic data (CSV columns: `date,open,high,low,close,volume,symbol`):

  ```bash
  prices import --file ./data/SP500History.csv
  ```

- **Insert a daily price** (writes to `daily_price_new` and takes effect immediately in analytics):

  ```text
  prices add --symbol MSFT --day 2024-11-01 --open 330.12 --high 332.88 --low 328.44 --close 331.67 --volume 21543210
  ```

Both sources contribute to the `unified_prices` view used by portfolio/list analytics and price history queries.

---

## 7. Testing Checklist

1. `docker compose up -d` (if developing locally).
2. `mvn clean package` – compile and package the CLI.
3. Run representative Picocli flows (users, portfolios, friends, lists, reviews, prices, analytics) using either one-off `mvn exec:java -Dexec.args=...` commands or by interacting with the shell.
4. `docker compose down` when finished with local development.

---

## 8. Troubleshooting

- **Connection refused** – verify the Postgres server is running, the port is open, and `DB_URL` points to the correct host/port.
- **Authentication errors** – confirm `DB_USER`/`DB_PASSWORD` and any host-based access rules (pg_hba.conf) allow the connection.
- **Analytics preconditions** – price predictions require at least five historical observations within the last 365 days for the target symbol.

---

## 9. License

Academic coursework project; no commercial license provided.
