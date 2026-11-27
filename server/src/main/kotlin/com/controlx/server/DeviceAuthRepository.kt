package com.controlx.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

data class RegisteredDeviceResult(
    val deviceId: String,
    val deviceToken: String,
    val dailyQuota: Int,
)

sealed interface AuthDecision {
    data class AuthorizedDevice(val deviceId: String, val isAdminToken: Boolean = false) : AuthDecision
    data class Unauthorized(val reason: String) : AuthDecision
}

sealed interface QuotaDecision {
    data object Allowed : QuotaDecision
    data class Exceeded(val reason: String) : QuotaDecision
}

class DeviceAuthRepository(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String,
    private val defaultDailyQuota: Int = 150,
    private val maxPerMinute: Int = 20,
) {
    private val random = SecureRandom()

    private fun connection(): Connection =
        DriverManager.getConnection(jdbcUrl, user, password)

    fun ensureSchema() {
        val ddlStatements = listOf(
            """
            CREATE TABLE IF NOT EXISTS command_history (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                device_id VARCHAR(128) NOT NULL,
                command_text TEXT NOT NULL,
                source VARCHAR(32) NOT NULL,
                result_status VARCHAR(32) NOT NULL,
                result_message TEXT,
                latency_ms INTEGER UNSIGNED,
                created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                PRIMARY KEY (id),
                INDEX idx_command_history_device_created (device_id, created_at),
                INDEX idx_command_history_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS device_app_inventory (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                device_id VARCHAR(128) NOT NULL,
                package_name VARCHAR(256) NOT NULL,
                app_label VARCHAR(256) NOT NULL,
                created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
                PRIMARY KEY (id),
                UNIQUE KEY uq_device_package (device_id, package_name),
                INDEX idx_device_app_inventory_device (device_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS devices (
                device_id VARCHAR(128) NOT NULL,
                token_hash CHAR(64) NOT NULL,
                app_version VARCHAR(64),
                device_model VARCHAR(128),
                daily_quota INT UNSIGNED NOT NULL DEFAULT 150,
                is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                last_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
                PRIMARY KEY (device_id),
                UNIQUE KEY uq_devices_token_hash (token_hash)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS ai_usage_logs (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                device_id VARCHAR(128) NOT NULL,
                endpoint VARCHAR(64) NOT NULL,
                model VARCHAR(128) NOT NULL,
                prompt_chars INT UNSIGNED NOT NULL DEFAULT 0,
                latency_ms INT UNSIGNED,
                status VARCHAR(32) NOT NULL,
                created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                PRIMARY KEY (id),
                INDEX idx_ai_usage_device_created (device_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
        )
        connection().use { conn ->
            conn.createStatement().use { stmt: Statement ->
                ddlStatements.forEach(stmt::execute)
            }
        }
    }

    /**
     * Registers a new device ID and returns a one-time opaque token whose SHA-256 hash is persisted.
     * Returns null if [deviceId] was already claimed so callers cannot hijack an existing device token.
     */
    fun registerNewDevice(
        deviceId: String,
        appVersion: String?,
        deviceModel: String?,
    ): RegisteredDeviceResult? {
        val cleanId = deviceId.trim().take(128)
        require(cleanId.isNotEmpty()) { "deviceId is required" }

        connection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM devices WHERE device_id = ?").use { check ->
                check.setString(1, cleanId)
                check.executeQuery().use { rs ->
                    if (rs.next()) return null
                }
            }
            val rawToken = generateToken()
            val tokenHash = sha256Hex(rawToken)
            conn.prepareStatement(
                """
                INSERT INTO devices (device_id, token_hash, app_version, device_model, daily_quota)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { insert ->
                insert.setString(1, cleanId)
                insert.setString(2, tokenHash)
                insert.setString(3, appVersion?.take(64))
                insert.setString(4, deviceModel?.take(128))
                insert.setInt(5, defaultDailyQuota)
                insert.executeUpdate()
            }
            return RegisteredDeviceResult(
                deviceId = cleanId,
                deviceToken = rawToken,
                dailyQuota = defaultDailyQuota,
            )
        }
    }

    fun authenticateBearer(
        bearerToken: String?,
        adminToken: String?,
        claimedDeviceId: String?,
    ): AuthDecision {
        val token = bearerToken?.trim().orEmpty()
        if (token.isEmpty()) return AuthDecision.Unauthorized("Missing bearer token.")

        if (!adminToken.isNullOrBlank() && constantTimeEquals(token, adminToken)) {
            val device = claimedDeviceId?.trim()?.takeIf { it.isNotEmpty() } ?: "admin"
            return AuthDecision.AuthorizedDevice(device, isAdminToken = true)
        }

        val tokenHash = sha256Hex(token)
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT device_id, is_blocked FROM devices WHERE token_hash = ?",
            ).use { stmt ->
                stmt.setString(1, tokenHash)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return AuthDecision.Unauthorized("Unknown device token.")
                    }
                    if (rs.getBoolean("is_blocked")) {
                        return AuthDecision.Unauthorized("Device access has been suspended.")
                    }
                    val registeredId = rs.getString("device_id")
                    if (!claimedDeviceId.isNullOrBlank() && claimedDeviceId.trim() != registeredId) {
                        return AuthDecision.Unauthorized("Device identity mismatch.")
                    }
                    touchDevice(conn, registeredId)
                    return AuthDecision.AuthorizedDevice(registeredId)
                }
            }
        }
    }

    fun checkAiQuota(deviceId: String): QuotaDecision {
        val cleanId = deviceId.take(128)
        connection().use { conn ->
            val dailyLimit = conn.prepareStatement(
                "SELECT daily_quota FROM devices WHERE device_id = ?",
            ).use { stmt ->
                stmt.setString(1, cleanId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("daily_quota") else defaultDailyQuota
                }
            }

            conn.prepareStatement(
                """
                SELECT
                    SUM(CASE WHEN created_at >= NOW(3) - INTERVAL 1 MINUTE THEN 1 ELSE 0 END) AS minute_count,
                    SUM(CASE WHEN created_at >= NOW(3) - INTERVAL 1 DAY THEN 1 ELSE 0 END) AS day_count
                FROM ai_usage_logs
                WHERE device_id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, cleanId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val minuteCount = rs.getInt("minute_count")
                        val dayCount = rs.getInt("day_count")
                        if (minuteCount >= maxPerMinute) {
                            return QuotaDecision.Exceeded("Rate limit reached. Wait a moment and retry.")
                        }
                        if (dayCount >= dailyLimit) {
                            return QuotaDecision.Exceeded("Daily AI request limit reached for this device.")
                        }
                    }
                }
            }
            return QuotaDecision.Allowed
        }
    }

    fun logAiUsage(
        deviceId: String,
        endpoint: String,
        model: String,
        promptChars: Int,
        latencyMs: Long?,
        status: String,
    ) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ai_usage_logs
                    (device_id, endpoint, model, prompt_chars, latency_ms, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, deviceId.take(128))
                stmt.setString(2, endpoint.take(64))
                stmt.setString(3, model.take(128))
                stmt.setInt(4, promptChars.coerceAtLeast(0))
                if (latencyMs == null) stmt.setNull(5, java.sql.Types.INTEGER)
                else stmt.setLong(5, latencyMs.coerceAtLeast(0))
                stmt.setString(6, status.take(32))
                stmt.executeUpdate()
            }
        }
    }

    private fun touchDevice(conn: Connection, deviceId: String) {
        conn.prepareStatement("UPDATE devices SET last_seen_at = NOW(3) WHERE device_id = ?")
            .use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeUpdate()
            }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun constantTimeEquals(left: String, right: String): Boolean =
            MessageDigest.isEqual(
                left.toByteArray(Charsets.UTF_8),
                right.toByteArray(Charsets.UTF_8),
            )
    }
}
