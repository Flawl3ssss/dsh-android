package ai.deepseek.dsh

import android.content.Context
import java.io.File

/** Построение proot-команд и управление процессами внутри Debian. */
object Proot {
    private fun prefix(c: Context, workdir: String = "/root"): List<String> {
        val proot = Paths.prootBin(c).absolutePath
        val root = Paths.debianDir(c).absolutePath
        val shared = Paths.sharedRoot(c).absolutePath
        val home = Paths.dshHome(c).absolutePath
        val ws = Paths.workspace(c).absolutePath
        val payload = Paths.payloadDir(c).absolutePath
        return listOf(
            proot, "-r", root,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "$shared:$shared",
            "-b", "${Paths.sharedWorkspace(c).absolutePath}:/root/phone",
            "-b", "$home:/root/dsh-home",
            "-b", "$ws:/root/workspace",
            "-b", "$payload:/opt/dsh",
            "-w", workdir
        )
    }

    private fun baseEnv(): List<String> = listOf(
        "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
        "PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "DSH_HOME=/root/dsh-home"
    )

    /** Полная команда: proot ... /usr/bin/env -i <env> <cmd>. */
    fun full(c: Context, prefs: Prefs, cmd: List<String>, workdir: String = "/root"): List<String> {
        val extra = listOf(
            "ZEN_API_KEY=${prefs.zenKey()}",
            "ZEN_ADAPTER_PORT=8787",
            "NODE_OPTIONS=--max-old-space-size=${prefs.nodeRamMb()}"
        )
        return prefix(c, workdir) + listOf("/usr/bin/env", "-i") + baseEnv() + extra + cmd
    }

    fun startDaemon(c: Context, tag: String, inner: List<String>, log: File): Process {
        log.parentFile?.mkdirs()
        val pb = ProcessBuilder(inner)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log))
        InstallLog.w(c, "start $tag: ${inner.joinToString(" ")}")
        return pb.start()
    }

    fun waitPort(port: Int, tries: Int = 15): Boolean {
        repeat(tries) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(1000)
            }
        }
        return false
    }
}
