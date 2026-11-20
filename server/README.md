# Control X Command API

This backend accepts command-history records and stores them in MySQL. Android connects to this API; Android never receives MySQL credentials.

## Local start

1. Copy `.env.example` to `.env`.
2. Set strong MySQL and API-token values.
3. Start MySQL:

   ```powershell
   docker compose up -d mysql
   ```

4. Start the full API and MySQL stack:

   ```powershell
   docker compose up -d --build api mysql
   ```

Or run the API locally:

   ```powershell
   gradle -p server run
   ```

The API listens on port `8080`.

## Protected endpoints

- `GET /health` — no authentication
- `POST /api/v1/commands` — bearer token required
