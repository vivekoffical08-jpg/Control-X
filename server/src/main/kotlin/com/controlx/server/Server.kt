package com.controlx.server

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors

data class CommandRecord(
    val deviceId: String,
    val commandText: String,
    val source: String,
    val resultStatus: String,
    val resultMessage: String?,
    val latencyMs: Long?,
)

data class AppInventoryRecord(
    val packageName: String,
    val label: String,
)

data class AppInventoryPayload(
    val deviceId: String,
    val apps: List<AppInventoryRecord>,
)

data class DeviceRegisterPayload(
    val deviceId: String,
    val appVersion: String? = null,
    val deviceModel: String? = null,
)

class MysqlCommandRepository(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String,
) {
    private fun connection(): Connection =
        DriverManager.getConnection(jdbcUrl, user, password)

    fun insert(record: CommandRecord) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO command_history
                    (device_id, command_text, source, result_status, result_message, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, record.deviceId.take(128))
                statement.setString(2, record.commandText)
                statement.setString(3, record.source.take(32))
                statement.setString(4, record.resultStatus.take(32))
                statement.setString(5, record.resultMessage)
                if (record.latencyMs == null) statement.setNull(6, java.sql.Types.INTEGER)
                else statement.setLong(6, record.latencyMs)
                statement.executeUpdate()
            }
        }
    }
}

class MysqlAppInventoryRepository(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String,
) {
    private fun connection(): Connection =
        DriverManager.getConnection(jdbcUrl, user, password)

    fun replace(deviceId: String, apps: List<AppInventoryRecord>) {
        connection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM device_app_inventory WHERE device_id = ?",
            ).use { statement ->
                statement.setString(1, deviceId.take(128))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO device_app_inventory
                    (device_id, package_name, app_label)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE app_label = VALUES(app_label)
                """.trimIndent(),
            ).use { statement ->
                for (app in apps) {
                    statement.setString(1, deviceId.take(128))
                    statement.setString(2, app.packageName.take(256))
                    statement.setString(3, app.label.take(256))
                    statement.addBatch()
                }
                if (apps.isNotEmpty()) statement.executeBatch()
            }
        }
    }
}

fun main() {
    val host = System.getenv("MYSQL_HOST") ?: System.getenv("MYSQLHOST") ?: "127.0.0.1"
    val port = System.getenv("MYSQL_PORT") ?: System.getenv("MYSQLPORT") ?: "3306"
    val database = System.getenv("MYSQL_DATABASE") ?: System.getenv("MYSQLDATABASE") ?: "controlx"
    val user = System.getenv("MYSQL_USER") ?: System.getenv("MYSQLUSER") ?: "controlx_app"
    val password = System.getenv("MYSQL_PASSWORD")
        ?: System.getenv("MYSQLPASSWORD")
        ?: error("MYSQL_PASSWORD must be configured")
    val adminToken = System.getenv("CONTROLX_API_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    val nvidiaApiKey = System.getenv("NVIDIA_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    val nvidiaModel = System.getenv("NVIDIA_MODEL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: NvidiaNimProxy.DEFAULT_MODEL
    val dailyQuota = System.getenv("AI_DAILY_QUOTA_PER_DEVICE")?.toIntOrNull() ?: 150
    val burstPerMinute = System.getenv("AI_BURST_PER_MINUTE")?.toIntOrNull() ?: 20
    val serverPort = (System.getenv("CONTROLX_SERVER_PORT") ?: System.getenv("PORT"))
        ?.toIntOrNull()
        ?: 8080
    val useSsl = System.getenv("MYSQL_USE_SSL")?.equals("true", ignoreCase = true) == true

    val jdbcUrl =
        "jdbc:mysql://$host:$port/$database?useSSL=$useSsl&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    val authRepository = DeviceAuthRepository(
        jdbcUrl = jdbcUrl,
        user = user,
        password = password,
        defaultDailyQuota = dailyQuota,
        maxPerMinute = burstPerMinute,
    ).apply { ensureSchema() }
    val repository = MysqlCommandRepository(jdbcUrl, user, password)
    val inventoryRepository = MysqlAppInventoryRepository(jdbcUrl, user, password)
    val nimProxy = NvidiaNimProxy(apiKey = nvidiaApiKey, model = nvidiaModel)
    val gson = Gson()
    val server = HttpServer.create(InetSocketAddress(serverPort), 0)

    server.createContext("/health") { exchange ->
        exchange.sendJson(
            200,
            mapOf(
                "status" to "ok",
                "aiConfigured" to nimProxy.isConfigured(),
                "model" to nimProxy.model,
            ),
        )
    }

    server.createContext("/api/v1/devices/register") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendJson(405, mapOf("error" to "Method not allowed"))
            return@createContext
        }
        try {
            val payload = gson.fromJson(
                exchange.requestBody.reader(Charsets.UTF_8).readText(),
                DeviceRegisterPayload::class.java,
            )
            require(payload.deviceId.isNotBlank()) { "deviceId is required" }
            val registered = authRepository.registerNewDevice(
                deviceId = payload.deviceId,
                appVersion = payload.appVersion,
                deviceModel = payload.deviceModel,
            )
            if (registered == null) {
                exchange.sendJson(
                    409,
                    mapOf("error" to "Device ID is already registered; generate a new device ID."),
                )
            } else {
                exchange.sendJson(
                    201,
                    mapOf(
                        "deviceId" to registered.deviceId,
                        "deviceToken" to registered.deviceToken,
                        "dailyQuota" to registered.dailyQuota,
                        "aiConfigured" to nimProxy.isConfigured(),
                    ),
                )
            }
        } catch (error: Exception) {
            exchange.sendJson(
                400,
                mapOf("error" to (error.message ?: "Invalid device registration payload")),
            )
        }
    }

    server.createContext("/api/v1/assistant/interpret") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendJson(405, mapOf("error" to "Method not allowed"))
            return@createContext
        }
        val started = System.nanoTime()
        try {
            val payload = gson.fromJson(
                exchange.requestBody.reader(Charsets.UTF_8).readText(),
                IntentInterpretRequest::class.java,
            )
            val auth = authRepository.authenticateBearer(
                bearerToken = exchange.bearerToken(),
                adminToken = adminToken,
                claimedDeviceId = payload.deviceId,
            )
            if (auth is AuthDecision.Unauthorized) {
                exchange.sendJson(401, mapOf("error" to auth.reason))
                return@createContext
            }
            val deviceId = (auth as AuthDecision.AuthorizedDevice).deviceId
            require(payload.command.isNotBlank()) { "command is required" }
            if (!nimProxy.isConfigured()) {
                exchange.sendJson(503, mapOf("error" to "Backend AI service is not configured."))
                return@createContext
            }
            val quota = authRepository.checkAiQuota(deviceId)
            if (quota is QuotaDecision.Exceeded) {
                exchange.sendJson(429, mapOf("error" to quota.reason))
                return@createContext
            }
            val interpreted = nimProxy.interpretIntent(payload)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            authRepository.logAiUsage(
                deviceId = deviceId,
                endpoint = "interpret",
                model = nimProxy.model,
                promptChars = payload.command.length,
                latencyMs = elapsedMs,
                status = interpreted.intent,
            )
            exchange.sendJson(200, interpreted)
        } catch (error: Exception) {
            exchange.sendJson(
                400,
                mapOf("error" to (error.message ?: "Failed to interpret command")),
            )
        }
    }

    server.createContext("/api/v1/agent/plan") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendJson(405, mapOf("error" to "Method not allowed"))
            return@createContext
        }
        val started = System.nanoTime()
        try {
            val payload = gson.fromJson(
                exchange.requestBody.reader(Charsets.UTF_8).readText(),
                AgentPlanRequest::class.java,
            )
            val auth = authRepository.authenticateBearer(
                bearerToken = exchange.bearerToken(),
                adminToken = adminToken,
                claimedDeviceId = payload.deviceId,
            )
            if (auth is AuthDecision.Unauthorized) {
                exchange.sendJson(401, mapOf("error" to auth.reason))
                return@createContext
            }
            val deviceId = (auth as AuthDecision.AuthorizedDevice).deviceId
            require(payload.goal.isNotBlank()) { "goal is required" }
            if (!nimProxy.isConfigured()) {
                exchange.sendJson(503, mapOf("error" to "Backend AI service is not configured."))
                return@createContext
            }
            val quota = authRepository.checkAiQuota(deviceId)
            if (quota is QuotaDecision.Exceeded) {
                exchange.sendJson(429, mapOf("error" to quota.reason))
                return@createContext
            }
            val plan = nimProxy.planReActStep(payload)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            authRepository.logAiUsage(
                deviceId = deviceId,
                endpoint = "agent_plan",
                model = nimProxy.model,
                promptChars = payload.goal.length + payload.snapshot.nodes.size * 32,
                latencyMs = elapsedMs,
                status = plan.action,
            )
            exchange.sendJson(200, plan)
        } catch (error: Exception) {
            exchange.sendJson(
                400,
                mapOf("error" to (error.message ?: "Failed to plan next action")),
            )
        }
    }

    server.createContext("/api/v1/commands") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendJson(405, mapOf("error" to "Method not allowed"))
            return@createContext
        }
        try {
            val record = gson.fromJson(
                exchange.requestBody.reader(Charsets.UTF_8).readText(),
                CommandRecord::class.java,
            )
            val auth = authRepository.authenticateBearer(
                bearerToken = exchange.bearerToken(),
                adminToken = adminToken,
                claimedDeviceId = record.deviceId,
            )
            if (auth is AuthDecision.Unauthorized) {
                exchange.sendJson(401, mapOf("error" to auth.reason))
                return@createContext
            }
            require(record.deviceId.isNotBlank()) { "deviceId is required" }
            require(record.commandText.isNotBlank()) { "commandText is required" }
            repository.insert(record)
            exchange.sendJson(201, mapOf("status" to "stored"))
        } catch (error: Exception) {
            exchange.sendJson(
                400,
                mapOf("error" to (error.message ?: "Invalid command record")),
            )
        }
    }

    server.createContext("/api/v1/app-inventory") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendJson(405, mapOf("error" to "Method not allowed"))
            return@createContext
        }
        try {
            val payload = gson.fromJson(
                exchange.requestBody.reader(Charsets.UTF_8).readText(),
                AppInventoryPayload::class.java,
            )
            val auth = authRepository.authenticateBearer(
                bearerToken = exchange.bearerToken(),
                adminToken = adminToken,
                claimedDeviceId = payload.deviceId,
            )
            if (auth is AuthDecision.Unauthorized) {
                exchange.sendJson(401, mapOf("error" to auth.reason))
                return@createContext
            }
            require(payload.deviceId.isNotBlank()) { "deviceId is required" }
            inventoryRepository.replace(payload.deviceId, payload.apps)
            exchange.sendJson(
                201,
                mapOf(
                    "status" to "stored",
                    "count" to payload.apps.size,
                ),
            )
        } catch (error: Exception) {
            exchange.sendJson(
                400,
                mapOf("error" to (error.message ?: "Invalid inventory payload")),
            )
        }
    }

    server.executor = Executors.newFixedThreadPool(16)
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
    })
}

private fun HttpExchange.bearerToken(): String? {
    val header = requestHeaders.getFirst("Authorization") ?: return null
    return if (header.startsWith("Bearer ", ignoreCase = true)) {
        header.substring(7).trim()
    } else {
        null
    }
}

private fun HttpExchange.sendJson(status: Int, body: Any) {
    val gson = Gson()
    val bytes = gson.toJson(body).toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    responseHeaders.add("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
