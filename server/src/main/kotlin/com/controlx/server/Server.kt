package com.controlx.server

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.sql.Connection
import java.sql.DriverManager

data class CommandRecord(
    val deviceId: String,
    val commandText: String,
    val source: String,
    val resultStatus: String,
    val resultMessage: String?,
    val latencyMs: Long?,
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

fun main() {
    val host = System.getenv("MYSQL_HOST") ?: "127.0.0.1"
    val port = System.getenv("MYSQL_PORT") ?: "3306"
    val database = System.getenv("MYSQL_DATABASE") ?: "controlx"
    val user = System.getenv("MYSQL_USER") ?: "controlx_app"
    val password = System.getenv("MYSQL_PASSWORD")
        ?: error("MYSQL_PASSWORD must be configured")
    val apiToken = System.getenv("CONTROLX_API_TOKEN")
        ?: error("CONTROLX_API_TOKEN must be configured")
    val serverPort = System.getenv("CONTROLX_SERVER_PORT")?.toIntOrNull() ?: 8080

    val jdbcUrl = "jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    val repository = MysqlCommandRepository(jdbcUrl, user, password)
    val gson = Gson()
    val server = HttpServer.create(InetSocketAddress(serverPort), 0)

    server.createContext("/health") { exchange ->
        exchange.sendJson(200, mapOf("status" to "ok"))
    }
    server.createContext("/api/v1/commands") { exchange ->
        if (!exchange.authorized(apiToken)) {
            exchange.sendJson(401, mapOf("error" to "Unauthorized"))
            return@createContext
        }
        if (exchange.requestMethod != "POST") {
            exchange.sendJson(405, mapOf("error" to "Method not allowed"))
            return@createContext
        }
        try {
            val record = gson.fromJson(
                exchange.requestBody.reader(Charsets.UTF_8).readText(),
                CommandRecord::class.java,
            )
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

    server.executor = null
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
    })
}

private fun HttpExchange.authorized(expectedToken: String): Boolean =
    requestHeaders.getFirst("Authorization") == "Bearer $expectedToken"

private fun HttpExchange.sendJson(status: Int, body: Any) {
    val gson = Gson()
    val bytes = gson.toJson(body).toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    responseHeaders.add("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
