package ru.ruznak.netscan

import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.config.ConfigStore
import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.output.OutputSink
import ru.ruznak.netscan.output.ScanContext
import ru.ruznak.netscan.output.SinkManager
import ru.ruznak.netscan.scan.FormattedScan
import ru.ruznak.netscan.scan.ScanHistory
import ru.ruznak.netscan.security.DeviceRegistry
import ru.ruznak.netscan.security.PairingService
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** Приёмник-заглушка: запоминает всё, что агент попытался ввести на ПК. */
class RecordingSink(
    override val kind: SinkKind = SinkKind.KEYBOARD,
    private val failure: (() -> Unit)? = null,
) : OutputSink {

    val emitted = mutableListOf<Pair<FormattedScan, ScanContext>>()

    override fun emit(scan: FormattedScan, context: ScanContext) {
        failure?.invoke()
        emitted += scan to context
    }

    val texts: List<String> get() = emitted.map { it.first.text }
}

/** Управляемое время: тесты подавления повторов не должны зависеть от реальных пауз. */
class TestClock(start: Long = 1_700_000_000_000) {
    private val now = AtomicLong(start)
    fun millis(): Long = now.get()
    fun advance(millis: Long) = now.addAndGet(millis)
}

/** Собирает состояние агента поверх временного каталога и подменного приёмника. */
fun testState(
    home: Path,
    config: AgentConfig = AgentConfig(output = OutputConfig(sinks = listOf(SinkKind.KEYBOARD))),
    sink: RecordingSink = RecordingSink(),
    clock: TestClock = TestClock(),
): Pair<AgentState, RecordingSink> {
    val store = ConfigStore(home.resolve("config.json"), config)
    val sinks = SinkManager(
        outputConfig = { store.config.output },
        httpClient = { error("вебхук в тестах не используется") },
        keyboardFactory = { sink },
        clipboardFactory = { null },
    )
    val state = AgentState(
        configStore = store,
        devices = DeviceRegistry(home.resolve("devices.json"), clock::millis),
        pairing = PairingService(home.resolve("pairing.txt"), { store.config.security.pairingTokenTtlMinutes }, clock::millis),
        history = ScanHistory { store.config.scan.historySize },
        sinks = sinks,
        clock = clock::millis,
    )
    return state to sink
}
