plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

group = "ru.ruznak.netscan"
version = "1.1.0"

repositories { mavenCentral() }

val ktorVersion = "3.1.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-network-tls-certificates:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("ch.qos.logback:logback-classic:1.5.16")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("ru.ruznak.netscan.MainKt")
    applicationName = "netscan"
    // Баннер, QR-код в консоли и русские сообщения печатаются в UTF-8 независимо
    // от локали машины: иначе на месте QR оператор увидит поле вопросительных знаков.
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        // Классическая cmd.exe стартует в кодовой странице 866; без переключения
        // на UTF-8 псевдографика QR-кода и русский текст выводятся мусором.
        windowsScript.writeText(
            windowsScript.readText().replaceFirst("@rem", "@chcp 65001>nul\r\n@rem"),
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// --------------------------------------------------------------- мобильный клиент

/*
 * Мобильный клиент — часть поставки агента: ПК раздаёт его сам, поэтому сборка
 * PWA встроена в сборку JVM-модуля. Отключается ключом -PskipWeb=true, когда
 * Node.js недоступен, а собранные ресурсы уже лежат на месте.
 */
val mobileDir = rootProject.layout.projectDirectory.dir("mobile")
val webResources = layout.projectDirectory.dir("src/main/resources/web")
val skipWeb = providers.gradleProperty("skipWeb").map { it.toBoolean() }.getOrElse(false)

fun npmCommand(vararg args: String): List<String> {
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"
    return listOf(executable) + args
}

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "mobile"
    description = "Устанавливает зависимости мобильного клиента"
    workingDir = mobileDir.asFile
    // npm ci требует lock-файл и даёт воспроизводимую установку.
    commandLine(npmCommand("ci", "--no-audit", "--no-fund"))
    inputs.file(mobileDir.file("package.json"))
    inputs.file(mobileDir.file("package-lock.json"))
    outputs.dir(mobileDir.dir("node_modules"))
    enabled = !skipWeb
}

val buildWebClient = tasks.register<Exec>("buildWebClient") {
    group = "mobile"
    description = "Собирает мобильный PWA-клиент в ресурсы агента"
    dependsOn(npmInstall)
    workingDir = mobileDir.asFile
    commandLine(npmCommand("run", "build"))
    inputs.dir(mobileDir.dir("src"))
    inputs.dir(mobileDir.dir("public"))
    inputs.file(mobileDir.file("index.html"))
    inputs.file(mobileDir.file("vite.config.ts"))
    inputs.file(mobileDir.file("package-lock.json"))
    outputs.dir(webResources)
    enabled = !skipWeb
}

tasks.named<ProcessResources>("processResources") {
    if (!skipWeb) dependsOn(buildWebClient)
}

tasks.named<Delete>("clean") {
    delete(webResources)
}
