package ru.ruznak.netscan.server

data class ServerConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val adminToken: String,
    val onlineWindowSeconds: Long,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Не задана обязательная переменная окружения $name")

            return ServerConfig(
                host = env["NETSCAN_SERVER_HOST"] ?: "0.0.0.0",
                port = env["NETSCAN_SERVER_PORT"]?.toIntOrNull() ?: 8081,
                databaseUrl = required("NETSCAN_DB_URL"),
                databaseUser = required("NETSCAN_DB_USER"),
                databasePassword = required("NETSCAN_DB_PASSWORD"),
                adminToken = required("NETSCAN_ADMIN_TOKEN"),
                onlineWindowSeconds = env["NETSCAN_ONLINE_WINDOW_SECONDS"]?.toLongOrNull() ?: 90,
            ).also {
                require(it.port in 1..65535) { "NETSCAN_SERVER_PORT вне диапазона" }
                require(it.adminToken.length >= 32) { "NETSCAN_ADMIN_TOKEN должен содержать не менее 32 символов" }
                require(it.onlineWindowSeconds in 15..3600) { "Некорректное окно online/offline" }
            }
        }
    }
}
