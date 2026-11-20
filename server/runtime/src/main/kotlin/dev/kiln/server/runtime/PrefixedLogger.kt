package dev.kiln.server.runtime

import java.util.logging.LogRecord
import java.util.logging.Logger

/** Logger that prefixes every message with `[Kiln] ` and hands it to [parent] (the host plugin's logger). */
class PrefixedLogger(parent: Logger) : Logger(parent.name, null) {
    init {
        setParent(parent)
        level = parent.level
    }

    override fun log(record: LogRecord) {
        record.message = "[Kiln] " + record.message
        super.log(record)
    }
}
