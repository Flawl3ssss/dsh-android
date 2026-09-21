package ai.deepseek.dsh

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Современный мастер первого запуска: тёмная тема, шаги со статусами,
 * большой прогресс, живой лог. Кнопки «Повторить»/«Отправить лог» всегда
 * на экране (никаких динамических вью — причина прошлого вылета убрана).
 * Весь процесс пишется в install-*.log.
 */
class BootActivity : Activity() {
    private lateinit var stepViews: List<TextView>
    private lateinit var bar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var log: TextView
    private lateinit var retry: Button
    private lateinit var sendLog: Button

    private val steps = listOf("Загрузка Debian", "Загрузка Node", "Загрузка proot", "Загрузка DSH", "Распаковка", "Проверка", "Запуск")
    private val base = "https://github.com/Flawl3ssss/dsh-android/releases/download"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        if (Paths.isInstalled(this)) {
            startMain()
            return
        }
        val bg = 0xFF0B0E14.toInt()
        val card = 0xFF151B26.toInt()
        val accent = 0xFF4D6BFE.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(48, 64, 48, 32)
        }
        val title = TextView(this).apply {
            text = "DSH"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE8ECF3.toInt())
        }
        val sub = TextView(this).apply {
            text = getString(R.string.boot_subtitle)
            textSize = 15f
            setTextColor(0xFF9AA3B5.toInt())
        }
        root.addView(title)
        root.addView(sub)
        root.addView(Space(28))
        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(32, 28, 32, 28)
        }
        stepViews = steps.map { name ->
            TextView(this).apply {
                text = "○  $name"
                textSize = 15f
                setTextColor(0xFF9AA3B5.toInt())
                setPadding(0, 8, 0, 8)
            }.also { cardBox.addView(it) }
        }
        root.addView(cardBox)
        root.addView(Space(24))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        pct = TextView(this).apply {
            text = "0%"
            textSize = 13f
            setTextColor(0xFF9AA3B5.toInt())
            gravity = Gravity.END
        }
        root.addView(bar)
        root.addView(pct)
        root.addView(Space(16))
        log = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF9AA3B5.toInt())
        }
        val sv = ScrollView(this).apply { addView(log) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Space(16))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        retry = Button(this).apply {
            text = getString(R.string.action_retry)
            isEnabled = false
            setOnClickListener { Thread { runInstall() }.start() }
        }
        sendLog = Button(this).apply {
            text = getString(R.string.action_send_log)
            setOnClickListener { InstallLog.share(this@BootActivity) }
        }
        row.addView(retry, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Space(16, horizontal = true))
        row.addView(sendLog, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        setContentView(root)
        InstallLog.writeDeviceInfo(this)
        Thread { runInstall() }.start()
    }


    /** Ловец любых необработанных падений: след остаётся в логе даже если процесс убит. */
    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val msg = "CRASH [${t.name}]: ${e}\n${sw}"
                InstallLog.w(this, msg)
                InstallLog.exportToShared(this)
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
        // Если прошлый запуск упал — сразу предложить отправить лог.
        try {
            val marker = java.io.File(Paths.logsDir(this), "CRASH.pending")
            if (marker.exists()) {
                marker.delete()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.crash_title))
                    .setMessage(getString(R.string.crash_msg))
                    .setPositiveButton(getString(R.string.action_send_log)) { _, _ -> InstallLog.share(this) }
                    .setNeutralButton(getString(R.string.action_copy_log)) { _, _ -> InstallLog.exportToShared(this) }
                    .setNegativeButton(getString(R.string.key_later), null)
                    .show()
            }
        } catch (_: Exception) {
        }
    }

    private fun markCrashPending() {
        try {
            java.io.File(Paths.logsDir(this), "CRASH.pending").createNewFile()
        } catch (_: Exception) {
        }
    }

    private fun Space(px: Int, horizontal: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = if (horizontal) LinearLayout.LayoutParams(px, -2)
            else LinearLayout.LayoutParams(-1, px)
        }
    }

    private fun ui(msg: String) {
        InstallLog.w(this, msg)
        if (!isFinishing) runOnUiThread { log.append(msg + "\n") }
    }

    private fun step(i: Int, state: Int) {
        // 0 idle, 1 active, 2 ok, 3 fail
        if (isFinishing) return
        runOnUiThread {
            val v = stepViews[i]
            v.text = when (state) {
                1 -> "◌  ${steps[i]}…"
                2 -> "✓  ${steps[i]}"
                3 -> "✗  ${steps[i]}"
                else -> "○  ${steps[i]}"
            }
            v.setTextColor(
                when (state) {
                    2 -> 0xFF34D399.toInt()
                    3 -> 0xFFF87171.toInt()
                    1 -> 0xFFE8ECF3.toInt()
                    else -> 0xFF9AA3B5.toInt()
                }
            )
        }
    }

    private fun prog(p: Int) {
        if (isFinishing) return
        runOnUiThread {
            bar.progress = p
            pct.text = "$p%"
        }
    }

    private fun runInstall() {
        markCrashPending()
        if (!isFinishing) runOnUiThread { retry.isEnabled = false }
        steps.indices.forEach { step(it, 0) }
        try {
            if (filesDir.freeSpace < 1_200_000_000L) {
                throw IllegalStateException("Мало места: нужно ~1.2 ГБ, свободно ${filesDir.freeSpace / 1024 / 1024} МБ")
            }
            val dl = File(cacheDir, "dl").apply { mkdirs() }
            val files = listOf(
                Triple("$base/rootfs-bookworm-1/debian-rootfs.tar.xz", "debian-rootfs.tar.xz", 0),
                Triple("$base/rootfs-bookworm-1/node.tar.xz", "node.tar.xz", 1),
                Triple("$base/rootfs-bookworm-1/proot", "proot", 2),
                Triple("$base/payload-2/dsh-payload.tar.xz", "dsh-payload.tar.xz", 3)
            )
            files.forEach { (url, name, si) ->
                step(si, 1)
                val out = File(dl, name)
                ui("[$name] download…")
                downloadResume(url, out) { done, total ->
                    val baseP = si * 100 / 7
                    prog(baseP + (if (total > 0) (done * 60 / total / 7).toInt() else 0))
                }
                ui("[$name] ok ${out.length()} bytes")
                step(si, 2)
            }
            step(4, 1)
            ui("extract rootfs…")
            untar(File(dl, "debian-rootfs.tar.xz"), Paths.debianDir(this))
            prog(68)
            ui("extract node…")
            untar(File(dl, "node.tar.xz"), File(Paths.debianDir(this), "opt"))
            fixNodeDir()
            prog(76)
            installProot(File(dl, "proot"))
            ui("extract payload…")
            untar(File(dl, "dsh-payload.tar.xz"), filesDir)
            prog(88)
            step(4, 2)
            step(5, 1)
            // dsh-home каркас
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
            diagExec()
            probeProot()
            val p = try {
                ProcessBuilder(
                    Paths.prootBin(this).absolutePath, "-r", Paths.debianDir(this).absolutePath,
                    "/bin/echo", "proot-ok"
                ).start()
            } catch (e: Exception) {
                ui("direct exec failed (${e.message}), trying linker64")
                ProcessBuilder(
                    "/system/bin/linker64", Paths.prootBin(this).absolutePath,
                    "-r", Paths.debianDir(this).absolutePath,
                    "/bin/echo", "proot-ok"
                ).start()
            }
            // (stderr уже виден в probe выше)
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            ui("smoke exit=$code out=${out.take(200)}")
            if (code != 0 || out != "proot-ok") throw IllegalStateException("proot smoke failed (exit $code): $out")
            step(5, 2)
            step(6, 1)
            java.io.File(Paths.logsDir(this), "CRASH.pending").delete()
            ui("INSTALL OK")
            prog(100)
            step(6, 2)
            if (!isFinishing) runOnUiThread { gateKeyThenStart() }
        } catch (e: Exception) {
            java.io.File(Paths.logsDir(this), "CRASH.pending").delete()
            ui("INSTALL FAILED: ${e.message}")
            steps.indices.forEach { if (!isFinishing) step(it, 3) }
            if (!isFinishing) runOnUiThread { retry.isEnabled = true }
        }
    }

    private fun downloadResume(url: String, out: File, cb: (Long, Long) -> Unit) {
        var done = if (out.exists()) out.length() else 0L
        var lastErr = ""
        repeat(8) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "DSH-Android/1.0")
                    if (done > 0) setRequestProperty("Range", "bytes=$done-")
                    instanceFollowRedirects = true
                }
                when (conn.responseCode) {
                    416 -> return
                    206 -> { /* resume */ }
                    200 -> { done = 0 }
                    404 -> throw IllegalStateException("Файл не найден (404): $url")
                    else -> throw IllegalStateException("HTTP ${conn.responseCode}: $url")
                }
                val total = if (conn.responseCode == 206) {
                    done + (conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L)
                } else {
                    conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                }
                conn.inputStream.use { ins ->
                    (if (done > 0 && conn.responseCode == 206) FileOutputStream(out, true) else out.outputStream()).use { os ->
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
                lastErr = "неполное скачивание ($done/$total)"
            } catch (e: Exception) {
                lastErr = e.message ?: e.toString()
                ui("retry $attempt: $lastErr")
                Thread.sleep(3000L * (attempt + 1))
            } finally {
                conn?.disconnect()
            }
        }
        throw IllegalStateException("Скачивание не удалось после 8 попыток: $url ($lastErr)")
    }

    private fun untar(archive: File, dest: File) {
        dest.mkdirs()
        var n = 0
        var skipped = 0
        // Удаляем возможный мусор от прошлой неудачной распаковки.
        FileInputStream(archive).use { fis ->
            org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis).use { xz ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val e = try {
                            tar.nextEntry ?: break
                        } catch (ex: Exception) {
                            ui("tar entry read failed: ${ex.message}")
                            break
                        }
                        try {
                            val out = File(dest, e.name)
                            if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                                skipped++
                                continue
                            }
                            when {
                                e.isDirectory -> out.mkdirs()
                                e.isSymbolicLink -> {
                                    try {
                                        java.nio.file.Files.deleteIfExists(out.toPath())
                                    } catch (_: Exception) {
                                    }
                                    out.parentFile?.mkdirs()
                                    try {
                                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(e.linkName))
                                    } catch (ex: Exception) {
                                        ui("symlink skip ${e.name}: ${ex.message}")
                                        skipped++
                                    }
                                }
                                e.isLink -> {
                                    // hardlink: копируем содержимое цели, если она уже распакована
                                    val target = File(dest, e.linkName)
                                    out.parentFile?.mkdirs()
                                    if (target.isFile) target.copyTo(out, overwrite = true)
                                    else {
                                        ui("hardlink skip ${e.name}")
                                        skipped++
                                    }
                                }
                                else -> {
                                    // Если на пути файл вместо каталога (след прошлой битой распаковки) — чистим.
                                    var p = out.parentFile
                                    while (p != null && p.canonicalPath.startsWith(dest.canonicalPath)) {
                                        if (p.exists() && !p.isDirectory) {
                                            ui("cleanup stray file: ${p.name}")
                                            p.delete()
                                            break
                                        }
                                        p = p.parentFile
                                    }
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { tar.copyTo(it) }
                                }
                            }
                            if (!e.isSymbolicLink && e.mode and 0b001001001 != 0) {
                                try {
                                    out.setExecutable(true, false)
                                } catch (_: Exception) {
                                }
                            }
                        } catch (ex: Exception) {
                            ui("entry skip ${e.name}: ${ex.message}")
                            skipped++
                        }
                        n++
                        if (n % 1000 == 0) ui("extract… $n (skip $skipped)")
                    }
                }
            }
        }
        ui("extract done: $n entries, skipped $skipped")
    }

    private fun fixNodeDir() {
        val opt = File(Paths.debianDir(this), "opt")
        val inner = opt.listFiles { f -> f.isDirectory && f.name.startsWith("node-v") }?.firstOrNull()
        val target = File(opt, "node")
        if (inner != null && !target.exists()) inner.renameTo(target)
    }


    /** Ключ Zen обязателен (встроенного больше нет): ввод при первом запуске. */
    private fun gateKeyThenStart() {
        if (Prefs(this).zenKey().isNotBlank()) {
            startService()
            startMain()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "sk-..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.key_title))
            .setMessage(getString(R.string.key_msg))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotBlank()) {
                    Prefs(this).setZenKey(k)
                    InstallLog.w(this, "zen key saved")
                }
                startService()
                startMain()
            }
            .setNegativeButton(getString(R.string.key_later)) { _, _ ->
                startService()
                startMain()
            }
            .show()
    }


    /** Выставление +x через прямой syscall (setExecutable иногда молча не срабатывает). */

    /** proot едет внутри APK как native lib (правильный SELinux-контекст),
     * скачанный файл — только запасной вариант. */
    private fun installProot(downloaded: File) {
        val bundled = java.io.File(applicationInfo.nativeLibraryDir, "libproot.so")
        ui("bundled proot: exists=${bundled.exists()} size=${if (bundled.exists()) bundled.length() else 0}")
        val src = if (bundled.exists() && bundled.length() > 100000) bundled else {
            ui("bundled proot missing — fallback to downloaded")
            if (!downloaded.exists() || downloaded.length() < 100000) {
                throw IllegalStateException("proot missing (bundled + downloaded)")
            }
            downloaded
        }
        val dst = Paths.prootBin(this)
        if (src.absolutePath != dst.absolutePath) {
            src.copyTo(dst, overwrite = true)
        } else {
            ui("proot used in place, no copy")
        }
        makeExecutable(dst)
    }

    /** Диагностика exec: baseline системного echo + доступ к бинарю. */

    /** Ступенчатая проверка proot: версия (без rootfs) → true → echo, со stderr и кодом. */
    private fun probeProot() {
        val bin = Paths.prootBin(this).absolutePath
        val root = Paths.debianDir(this).absolutePath
        // rootfs на месте?
        try {
            val names = arrayOf("bin/echo", "bin/true", "bin/bash", "lib/ld-linux-aarch64.so.1")
            for (n in names) {
                ui("rootfs check $n: ${java.io.File(root, n).exists()}")
            }
        } catch (e: Exception) {
            ui("rootfs check failed: ${e.message}")
        }
        runProbe("version", listOf(bin, "--version"))
        runProbe("true", listOf(bin, "-r", root, "/bin/true"))
    }

    private fun runProbe(tag: String, cmd: List<String>) {
        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            ui("probe [$tag] exit=$code out=${out.take(400).replace("\n", "|")}")
        } catch (e: Exception) {
            ui("probe [$tag] START FAILED: ${e.message}")
        }
    }

    private fun diagExec() {
        try {
            val e = ProcessBuilder("/system/bin/echo", "sys-ok").start()
            val o = e.inputStream.bufferedReader().readText().trim()
            ui("baseline /system/bin/echo: $o (exit ${e.waitFor()})")
        } catch (ex: Exception) {
            ui("baseline echo FAILED: ${ex.message}")
        }
        try {
            val f = Paths.prootBin(this)
            val xok = android.system.Os.access(f.absolutePath, android.system.OsConstants.X_OK)
            ui("proot access X_OK=$xok path=${f.absolutePath}")
        } catch (ex: Exception) {
            ui("proot access check FAILED: ${ex.message}")
        }
    }

    private fun makeExecutable(f: java.io.File) {
        var mode = -1
        try {
            f.setExecutable(true, false)
        } catch (e: Exception) {
            ui("setExecutable failed: ${e.message}")
        }
        try {
            android.system.Os.chmod(f.absolutePath, 448) // 0700
        } catch (e: Exception) {
            ui("Os.chmod failed: ${e.message}")
        }
        // fallback: системный chmod, если есть
        if (!f.canExecute()) {
            try {
                val p = ProcessBuilder("/system/bin/chmod", "700", f.absolutePath).start()
                p.waitFor()
            } catch (e: Exception) {
                ui("chmod bin failed: ${e.message}")
            }
        }
        try {
            mode = android.system.Os.stat(f.absolutePath).st_mode and 511
        } catch (_: Exception) {
        }
        ui("proot mode=${mode.toString(8)} executable=${f.canExecute()} size=${f.length()}")
        if (!f.canExecute()) {
            throw IllegalStateException("proot not executable (mode ${mode.toString(8)}).")
        }
    }

    private fun startService() {
        val i = Intent(this, DshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
