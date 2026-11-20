# Control X MySQL Database

MySQL is used only through the backend API. The Android app must never store or use MySQL credentials.

Tables:

- `command_history`: command text, source, status, message, latency, timestamp, and anonymous/random device ID

Production requirements:

- Use TLS for MySQL
- Use a least-privilege application account
- Keep `MYSQL_ROOT_PASSWORD` and `CONTROLX_API_TOKEN` out of source
- Use managed secrets on the hosting platform
- Rotate the API token if exposed
- Back up the MySQL volume/database regularly
