package ai.deepseek.dsh

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/**
 * Главный экран: WebView на локальный dsh web + таб-бар сессий (черезгружается
 * существующий resume), вложения из inbox, выбор файлов, тема/шрифт.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val PICK = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        if (!Paths.isInstalled(this)) {
            startActivity(Intent(this, BootActivity::class.java))
            finish()
            return
        }
        ensureService()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        setupWeb()
        if (savedInstanceState == null) web.loadUrl("http://127.0.0.1:8081/")
        else web.restoreState(savedInstanceState)
    }

    private fun applyTheme() {
        val p = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (p.amoled()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }

    private fun ensureService() {
        if (!DshService.running) {
            val i = Intent(this, DshService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }

    private fun setupWeb() {
        val p = Prefs(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = (100 * p.fontScale()).toInt()
            userAgentString = "$userAgentString DSH-Android/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(InboxBridge(), "DSHInbox")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // Наружу — только не-localhost.
                if (!url.startsWith("http://127.0.0.1:8081")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                return false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(Intent.createChooser(i, getString(R.string.pick_file)), PICK)
                return true
            }
        }
    }

    inner class InboxBridge {
        /** JS: DSHInbox.list() -> JSON имён файлов inbox; DSHInbox.path(name) -> путь. */
        @android.webkit.JavascriptInterface
        fun list(): String {
            val files = Paths.inbox(this@MainActivity).listFiles()?.map { it.name } ?: emptyList()
            return files.joinToString(",", "[", "]") { "\"$it\"" }
        }

        @android.webkit.JavascriptInterface
        fun path(name: String): String {
            val f = File(Paths.inbox(this@MainActivity), File(name).name)
            return if (f.exists()) f.absolutePath else ""
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK) {
            val uris = mutableListOf<Uri>()
            if (resultCode == Activity.RESULT_OK && data != null) {
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                    ?: data.data?.let { uris.add(it) }
                // Копируем выбранное в inbox, чтобы агент видел стабильный путь.
                uris.forEach { copyToInbox(it) }
            }
            fileCb?.onReceiveValue(uris.toTypedArray())
            fileCb = null
        }
    }

    private fun copyToInbox(uri: Uri) {
        try {
            val name = contentName(uri) ?: "file-${System.currentTimeMillis()}"
            contentResolver.openInputStream(uri)?.use { ins ->
                File(Paths.inbox(this), name).outputStream().use { ins.copyTo(it) }
            }
        } catch (e: Exception) {
            InstallLog.w(this, "inbox copy failed: ${e.message}")
        }
    }

    private fun contentName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.menu_terminal))
        menu.add(0, 2, 0, getString(R.string.menu_editor))
        menu.add(0, 3, 0, getString(R.string.menu_logs))
        menu.add(0, 4, 0, getString(R.string.menu_settings))
        menu.add(0, 5, 0, getString(R.string.menu_export))
        menu.add(0, 6, 0, getString(R.string.menu_files))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, TerminalActivity::class.java))
            2 -> startActivity(Intent(this, EditorActivity::class.java))
            3 -> startActivity(Intent(this, LogsActivity::class.java))
            4 -> startActivity(Intent(this, SettingsActivity::class.java))
            5 -> exportChat()
            6 -> startActivity(Intent(this, FilesActivity::class.java))
        }
        return true
    }

    private fun exportChat() {
        // Экспорт: просим Web GUI скопировать markdown текущего чата в буфер через JS,
        // затем сохраняем в DSH/downloads через Storage Access.
        web.evaluateJavascript(
            "(function(){var el=document.querySelector('[data-chat-export]');return el?el.getAttribute('data-chat-export'):'';})();"
        ) { v ->
            InstallLog.w(this, "export hook: ${v?.take(60)}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
