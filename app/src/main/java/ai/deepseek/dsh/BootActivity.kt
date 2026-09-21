package ai.deepseek.dsh

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Мастер первого запуска: разрешения → скачивание → распаковка → старт.
 * Весь процесс пишется в install-*.log (InstallLog) — при сбое кнопка «Отправить лог».
 */
class BootActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var bar: ProgressBar
    private lateinit var retry: Button
    private lateinit var sendLog: Button

    // Источники артефактов первой версии (обновляются через payload.json/релизы).
    private val base = "https://github.com/Flawl3ssss/dsh-android/releases/download"
    private val files = listOf(
        Triple("$base/rootfs-bookworm-1/debian-rootfs.tar.xz", "debian-rootfs.tar.xz", "rootfs"),
        Triple("$base/rootfs-bookworm-1/node.tar.xz", "node.tar.xz", "node"),
        Triple("$base/rootfs-bookworm-1/proot", "proot", "proot"),
        Triple("$base/payload-1/dsh-payload.tar.xz", "dsh-payload.tar.xz", "payload")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Paths.isInstalled(this)) {
            startMain()
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        log = TextView(this).apply { textSize = 12f }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        retry = Button(this).apply { text = getString(R.string.action_retry); setOnClickListener { Thread { runInstall() }.start() } }
        sendLog = Button(this).apply { text = getString(R.string.action_send_log); setOnClickListener { InstallLog.share(this@BootActivity) } }
        val sv = ScrollView(this).apply { addView(log) }
        root.addView(bar)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(retry)
        root.addView(sendLog)
        retry.isEnabled = false
        setContentView(root)
        InstallLog.writeDeviceInfo(this)
        Thread { runInstall() }.start()
    }

    private fun ui(msg: String) {
        InstallLog.w(this, msg)
        runOnUiThread { log.append(msg + "\n") }
    }

    private fun prog(p: Int) = runOnUiThread { bar.progress = p }

    private fun runInstall() {
        runOnUiThread { retry.isEnabled = false }
        try {
            // 0. место: нужно ~1.2 ГБ
            val need = 1_200_000_000L
            if (filesDir.freeSpace < need) {
                throw IllegalStateException("Мало места: нужно ~1.2 ГБ, свободно ${filesDir.freeSpace / 1024 / 1024} МБ")
            }
            val dl = File(cacheDir, "dl").apply { mkdirs() }
            // 1. скачивание с докачкой
            files.forEachIndexed { i, (url, name, tag) ->
                val out = File(dl, name)
                ui("[$tag] download $url")
                downloadResume(url, out) { done, total ->
                    val base = i * 100 / files.size
                    prog(base + (if (total > 0) (done * 100 / total / files.size).toInt() else 0))
                }
                ui("[$tag] saved ${out.length()} bytes")
            }
            // 2. распаковка rootfs
            ui("[rootfs] extract...")
            shell(listOf("mkdir", "-p", Paths.debianDir(this).absolutePath))
            untar(File(dl, "debian-rootfs.tar.xz"), Paths.debianDir(this))
            // 3. node внутрь rootfs
            ui("[node] extract to debian/opt/node...")
            untar(File(dl, "node.tar.xz"), File(Paths.debianDir(this), "opt"))
            fixNodeDir()
            // 4. proot
            ui("[proot] install...")
            File(dl, "proot").copyTo(Paths.prootBin(this), overwrite = true)
            Paths.prootBin(this).setExecutable(true)
            // 5. payload
            ui("[payload] extract...")
            untar(File(dl, "dsh-payload.tar.xz"), filesDir)
            // 6. dsh-home каркас + zen-слой
            val home = Paths.dshHome(this).apply { mkdirs() }
            File(home, "cordis.patch.yml").writeText(
                "# Home patch layer: default model -> zen adapter route.\n" +
                    "- id: agent-default-model\n  config:\n    provider: zen\n    model: ${Prefs(this).model()}\n"
            )
            File(home, "settings.yaml").writeText(
                "llm-pi-ai:\n  providers:\n    zen:\n      displayName: Zen via local adapter\n" +
                    "      api: openai-responses\n      baseURL: http://127.0.0.1:8787/v1\n      apiKeyEnv: ZEN_API_KEY\n"
            )
            Paths.workspace(this).mkdirs()
            Paths.sharedWorkspace(this)
            // 7. дымовой тест proot
            ui("[proot] smoke test...")
            val p = ProcessBuilder(
                Paths.prootBin(this).absolutePath, "-r", Paths.debianDir(this).absolutePath,
                "/bin/echo", "proot-ok"
            ).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() != 0 || out != "proot-ok") throw IllegalStateException("proot smoke failed: $out")
            ui("INSTALL OK")
            prog(100)
            startService()
            runOnUiThread { startMain() }
        } catch (e: Exception) {
            ui("INSTALL FAILED: ${e.message}")
            runOnUiThread {
                retry.isEnabled = true
                val b = Button(this).apply {
                    text = getString(R.string.install_failed_title)
                    setOnClickListener { InstallLog.share(this@BootActivity) }
                }
                (findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as LinearLayout).addView(b)
            }
        }
    }

    private fun downloadResume(url: String, out: File, cb: (Long, Long) -> Unit) {
        var done = if (out.exists()) out.length() else 0L
        repeat(3) { attempt ->
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            try {
                if (c.responseCode == 416) return // уже докачан
                val total = if (c.responseCode == 206) {
                    done + (c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L)
                } else {
                    done = 0
                    c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                }
                c.inputStream.use { ins ->
                    (if (done > 0) java.io.FileOutputStream(out, true) else out.outputStream()).use { os ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            cb(done, total)
                        }
                    }
                }
                if (total < 0 || done >= total) return
            } catch (e: Exception) {
                ui("retry $attempt: ${e.message}")
                Thread.sleep(3000)
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("download failed after retries: $url")
    }

    private fun untar(archive: File, dest: File) {
        // Системного tar в Android нет — распаковка на Java (commons-compress: tar + xz).
        dest.mkdirs()
        var n = 0
        FileInputStream(archive).use { fis ->
            org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis).use { xz ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val out = File(dest, e.name)
                        if (!out.canonicalPath.startsWith(dest.canonicalPath)) continue
                        if (e.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        if (e.mode and 0b001001001 != 0) out.setExecutable(true, false)
                        n++
                        if (n % 2000 == 0) ui("extract... $n entries")
                    }
                }
            }
        }
        ui("extract done: $n entries")
    }

    private fun fixNodeDir() {
        // node-*.tar.xz содержит каталог node-vXX: переносим содержимое в opt/node
        val opt = File(Paths.debianDir(this), "opt")
        val inner = opt.listFiles { f -> f.isDirectory && f.name.startsWith("node-v") }?.firstOrNull()
        val target = File(opt, "node")
        if (inner != null && !target.exists()) inner.renameTo(target)
    }

    private fun shell(cmd: List<String>) {
        val p = ProcessBuilder(cmd).start()
        if (p.waitFor() != 0) throw IllegalStateException(cmd.joinToString(" "))
    }

    private fun startService() {
        val i = Intent(this, DshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Suppress("unused")
    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val b = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(b)
                if (n < 0) break
                d.update(b, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
