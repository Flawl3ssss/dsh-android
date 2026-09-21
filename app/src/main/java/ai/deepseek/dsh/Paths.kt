package ai.deepseek.dsh

import android.content.Context
import java.io.File

/** Единая схема файловой системы (см. план rev.3 §4). */
object Paths {
    fun debianDir(c: Context) = File(c.filesDir, "debian")
    fun payloadDir(c: Context) = File(c.filesDir, "payload")
    fun dshHome(c: Context) = File(c.filesDir, "dsh-home")
    fun workspace(c: Context) = File(c.filesDir, "workspace")
    fun prootBin(c: Context) = File(c.filesDir, "proot")
    fun nodeBin(): String = "/opt/node/bin/node"

    /** Общая зона на внешней памяти. */
    fun sharedRoot(c: Context): File {
        val f = File(c.getExternalFilesDir(null), "DSH")
        f.mkdirs()
        return f
    }
    fun inbox(c: Context) = File(sharedRoot(c), "inbox").apply { mkdirs() }
    fun downloads(c: Context) = File(sharedRoot(c), "downloads").apply { mkdirs() }
    fun backups(c: Context) = File(sharedRoot(c), "backups").apply { mkdirs() }
    fun sharedWorkspace(c: Context) = File(sharedRoot(c), "workspace").apply { mkdirs() }

    fun logsDir(c: Context) = File(dshHome(c), "logs").apply { mkdirs() }
    fun sessionsDir(c: Context) = File(dshHome(c), "sessions")

    fun isInstalled(c: Context): Boolean =
        File(debianDir(c), "etc/debian_version").exists() &&
            File(payloadDir(c), "payload.json").exists() &&
            prootBin(c).exists()
}
