package com.phonoteka.data

import android.annotation.SuppressLint
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val desktopWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * Атомарная замена файла. На Android/Linux rename(2) сам атомарно заменяет цель.
 * На Windows (JVM-тесты) File.renameTo не трогает существующий файл, поэтому там
 * используется Files.move(ATOMIC_MOVE). На устройстве эта ветка недостижима (API 24 без java.nio.file).
 */
@SuppressLint("NewApi")
internal fun replaceAtomically(source: File, target: File): Boolean {
    if (source.renameTo(target)) return true
    if (!desktopWindows) return false
    return try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: Exception) { false }
}
