# Control X Backend API & AI Proxy

This backend stores command history/app inventory in MySQL and proxies all **NVIDIA NIM (Nemotron)** AI calls so `NVIDIA_API_KEY` stays strictly on the server and is never packaged in the Android APK.

## Environment configuration

1. Copy `.env.example` to `.env`.
2. Set strong `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` values and your server-side `NVIDIA_API_KEY`.
3. Optionally adjust `NVIDIA_MODEL` (defaults to `nvidia/nemotron-3-ultra-550b-a55b`), `AI_DAILY_QUOTA_PER_DEVICE` (default `150`), and `AI_BURST_PER_MINUTE` (default `20`).
4. Start locally with Docker Compose:

   ```powershell
   docker compose up -d --build api mysql
   ```

   Or run via Gradle:

   ```powershell
   gradle -p server run
   ```

The server listens on `CONTROLX_SERVER_PORT` / `PORT` (default `8080`) and automatically creates missing MySQL tables on startup.

## Cloud deployment notes (Render / Railway / VPS)

- Provide HTTPS (via Render, Railway, Reverse Proxy / Caddy / Nginx) so Android accepts requests cleanly without cleartext exceptions.
- Set env vars `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, and `NVIDIA_API_KEY` in the cloud dashboard.
- Pass `-PcontrolxBackendUrl=https://your-service.example.com` (or repo variable `CONTROLX_BACKEND_URL`) when building the Android APK so devices prefill your server URL automatically.

## Endpoints

- `GET /health` — health and AI-readiness status (no secrets exposed)
- `POST /api/v1/devices/register` — issues a unique per-device bearer token stored hashed in MySQL
- `POST /api/v1/assistant/interpret` — per-device bearer auth required; maps natural voice/text commands to structured dynamic Android actions using the caller's installed app list
- `POST /api/v1/agent/plan` — per-device bearer auth required; proxies ReAct screen-control planning cleanly
- `POST /api/v1/commands` — per-device bearer auth required; logs command history
- `POST /api/v1/app-inventory` — per-device bearer auth required; stores latest app catalog snapshot
