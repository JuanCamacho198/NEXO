package com.nexo.debug

import java.io.File

interface LogWriter {
    fun write(
        level: String,
        tag: String,
        message: String,
        timestamp: Long,
    )

    fun snapshot(): List<String>

    fun copySnapshotTo(target: File)
}
