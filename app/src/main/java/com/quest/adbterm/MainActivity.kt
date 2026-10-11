package com.quest.adbterm

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Loader
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

  private val bg = 0xFF05070A.toInt()
  private val fg = 0xFF58F58B.toInt()
  private val fgDim = 0xFF2E9B5F.toInt()
  private val info = 0xFF7FD4FF.toInt()
  private val warn = 0xFFFFC857.toInt()
  private val error = 0xFFFF6B6B.toInt()
  private val panel = 0xFF0B1014.toInt()
  private val mono: Typeface = Typeface.MONOSPACE

  private class Chunk(val text: String, val color: Int?)
  private val queue = ArrayDeque<Chunk>()
  private val lock = Any()
  private val main = Handler(Looper.getMainLooper())
  private val out = SpannableStringBuilder()
  private var flushScheduled = false
  private var flushDelayMs = 40L

  private lateinit var scroll: ScrollView
  private lateinit var logView: TextView
  private lateinit var input: EditText

  private var native: NativeShell? = null
  private var readPfd: ParcelFileDescriptor? = null
  private var writePfd: ParcelFileDescriptor? = null
  private var readIn: FileInputStream? = null
  private var writeOut: FileOutputStream? = null
  private var readFd = -1
  private var writeFd = -1
  private var stopping = false
  private var autoScroll = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    installCrashCatcher()

    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.statusBarColor = bg
    window.navigationBarColor = bg

    setContentView(buildUi())

    emit("Quest ADB Terminal\non-device arm64 adb | 127.0.0.1 | standalone\n\n", info)
    reportLastCrash()

    boot()
  }

  override fun onDestroy() {
    if (isFinishing) stopShell()
    super.onDestroy()
  }

  // ---------------------------------------------------------------- crash log

  private fun crashFile(): File = File(filesDir, "last_crash.txt")

  private fun installCrashCatcher() {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      try {
        crashFile().writeText("${e.javaClass.name}: ${e.message}\n\n${e.stackTraceToString()}")
      } catch (_: Throwable) {}
      try {
        if (prev != null) prev.uncaughtException(t, e)
      } catch (_: Throwable) {}
      Process.killProcess(Process.myPid())
    }
  }

  private fun reportLastCrash() {
    val f = crashFile()
    if (f.isFile && f.length() > 0) {
      emit("!! previous run crashed:\n${f.readText()}\n\n", error)
      f.delete()
    }
  }

  // ---------------------------------------------------------------- ui

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun buildUi(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(bg)
      setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    root.addView(TextView(this).apply {
      text = "QUEST ADB TERMINAL"
      setTextColor(fg)
      typeface = mono
      textSize = 15f
      letterSpacing = 0.18f
    })

    root.addView(TextView(this).apply {
      text = "local adb server on 127.0.0.1:5037 | arm64 only"
      setTextColor(fgDim)
      typeface = mono
      textSize = 10f
    })

    // ROOT FUGU BUTTON - Added for Singularity-style root attempt
    root.addView(button("ROOT FUGU (Singularity Style)") { attemptFuguRoot() },
      LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        marginStart = dp(0)
        marginEnd = dp(0)
        marginTop = dp(8)
        marginBottom = dp(8)
      })

    scroll = ScrollView(this).apply {
      setBackgroundColor(Color.BLACK)
      isFillViewport = true
      setPadding(0, dp(8), 0, dp(8))
    }

    logView = TextView(this).apply {
      typeface = mono
      textSize = 12f
      setTextColor(fg)
      setPadding(dp(8), dp(8), dp(8), dp(8))
      setTextIsSelectable(true)
      setText("")
    }
    scroll.addView(logView, ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

    scroll.setOnScrollChangeListener { _, _, _, _, _ ->
      val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
      autoScroll = (child.bottom - scroll.height - scroll.scrollY) <= dp(24)
    }

    val inputRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding(0, dp(4), 0, dp(6))
    }
    input = EditText(this).apply {
      setBackgroundColor(panel)
      setTextColor(fg)
      setHintTextColor(fgDim)
      hint = "adb devices"
      typeface = mono
      textSize = 13f
      setPadding(dp(8), dp(8), dp(8), dp(8))
      setSingleLine(true)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      imeOptions = EditorInfo.IME_ACTION_SEND
      setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
          sendCurrent(); true
        } else false
      }
    }
    inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    inputRow.addView(button("SEND") { sendCurrent() },
      LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
    root.addView(inputRow)

    root.addView(buttonRow(
      "PAIR" to { prefill("adb pair localhost:") },
      "CONNECT" to { prefill("adb connect localhost:") },
      "DEVICES" to { exec("adb devices -l") }
    ))
    root.addView(buttonRow(
      "SHELL" to { prefill("adb shell ") },
      "DISCONNECT" to { exec("adb disconnect") },
      "CLEAR" to { clearLog() }
    ).apply { setPadding(0, dp(4), 0, 0) })

    return root
  }

  private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    typeface = mono
    textSize = 11f
    isAllCaps = false
    setTextColor(fg)
    setBackgroundColor(panel)
    setPadding(dp(4), dp(8), dp(4), dp(8))
    minimumWidth = 0
    setOnClickListener { onClick() }
  }

  private fun buttonRow(vararg pairs: Pair<String, () -> Unit>): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    pairs.forEach { (label, action) ->
      row.addView(button(label, action),
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
    }
    return row
  }

  // ---------------------------------------------------------------- fugu root

  private fun attemptFuguRoot() {
    emit("\n=== FUGU ROOT ATTEMPT (Singularity Style) ===\n", warn)
    emit("WARNING: This attempts known Fugu (Intel Android TV) exploits.\n", warn)
    emit("Your Fugu device must have USB debugging enabled.\n", warn)
    emit("This may NOT work on your specific Android version.\n\n", warn)

    // Check if any device is connected
    emit("Checking for connected devices...\n", info)
    exec("adb devices -l")

    emit("\n[INFO] Fugu Root Requirements:\n", fgDim)
    emit("1. Device must be a Fugu (Intel Android TV box)\n", fgDim)
    emit("2. USB debugging must be enabled on the Fugu\n", fgDim)
    emit("3. You need the specific exploit binary for your Android version\n", fgDim)
    emit("\n[INFO] Current Status:\n", fgDim)
    emit("Fugu devices were last rooted years ago using specific Intel Atom exploits.\n", fgDim)
    emit("Android version 5.0-5.1 had known vulnerabilities, newer versions are patched.\n", fgDim)
    emit("\n[INFO] Next Steps:\n", fgDim)
    emit("1. Check your Fugu's Android version: adb shell getprop ro.build.version.release\n", fgDim)
    emit("2. Search XDA Developers for 'Fugu root' + your Android version\n", fgDim)
    emit("3. Download the specific exploit binary for your device\n", fgDim)
    emit("4. Place the exploit in your app's assets folder\n", fgDim)
    emit("5. Modify this code to push and execute the specific exploit\n", fgDim)
    emit("\n[INFO] Without the actual exploit binary, root is not possible.\n", warn)
    emit("The button above can only automate existing exploits, not create new ones.\n", warn)
    emit("\n=== Root attempt info completed ===\n", fgDim)
  }

  // ---------------------------------------------------------------- logging

  private fun emit(text: String, color: Int? = null) {
    synchronized(lock) {
      queue.addLast(Chunk(text, color))
      if (flushScheduled) return
      flushScheduled = true
    }
    main.postDelayed({ flushQueue() }, flushDelayMs)
  }

  private fun flushQueue() {
    val items: List<Chunk>
    synchronized(lock) {
      if (queue.isEmpty()) { flushScheduled = false; return }
      items = queue.toList()
      queue.clear()
      flushScheduled = false
    }

    val t0 = SystemClock.uptimeMillis()
    for (chunk in items) {
      val start = out.length
      out.append(chunk.text)
      chunk.color?.let {
        out.setSpan(ForegroundColorSpan(it), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
    if (out.length > MAX_LOG) out.delete(0, out.length - MAX_LOG)

    logView.text = out
    if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    val spent = SystemClock.uptimeMillis() - t0
    flushDelayMs = if (spent > 25) (flushDelayMs * 2).coerceAtMost(500)
    else (flushDelayMs - 20).coerceAtLeast(40)
  }

  private fun clearLog() {
    synchronized(lock) { queue.clear() }
    out.clear()
    out.clearSpans()
    logView.text = ""
    emit("log cleared (shell state untouched)\n", fgDim)
  }

  // ---------------------------------------------------------------- input

  private fun sendCurrent() {
    val cmd = input.text.toString().trim()
    if (cmd.isEmpty()) return
    input.setText("")
    exec(cmd)
  }

  private fun prefill(prefix: String) {
    input.setText(prefix)
    input.setSelection(prefix.length)
    input.requestFocus()
  }

  fun exec(cmd: String) {
    val stream = writeOut
    if (stream == null || writeFd < 0) {
      emit("[!] shell not ready\n", warn)
      return
    }
    val echo = SpannableStringBuilder()
    val start = echo.length
    echo.append("\n> $cmd\n")
    echo.setSpan(ForegroundColorSpan(info), start, echo.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    emit(echo.toString(), info)

    try {
      stream.write((cmd.trimEnd('\n') + "\n").toByteArray(Charsets.UTF_8))
      stream.flush()
    } catch (e: IOException) {
      emit("[!] write failed: ${e.message}\n", error)
    }
  }

  // ---------------------------------------------------------------- boot

  private fun boot() {
    Thread {
      try {
        emit("[boot] preparing adb binary...\n", info)
        val adb = AdbBootstrap.ensureAdb(this) { msg -> emit("$msg\n", info) }
        startShell(adb.parentFile ?: throw IOException("adb has no parent dir"))
        printFirstRunHelp()
        exec("adb version")
        exec("adb devices")
      } catch (t: Throwable) {
        emit("\n[boot failed] ${t.javaClass.simpleName}: ${t.message}\n", error)
        emit("Needs internet on first launch (downloads adb from github.com).\n", error)
        emit("If it keeps failing, relaunch and read the log above.\n", error)
      }
    }.apply { isDaemon = true }.start()
  }

  private fun startShell(toolsDir: File) {
    val home = File(filesDir, "home").also { it.mkdirs() }
    val tmp = File(cacheDir, "tmp").also { it.mkdirs() }

    val ns = NativeShell()
    val fds = ns.start(toolsDir.absolutePath, home.absolutePath, tmp.absolutePath)
    if (fds.size < 3 || fds[0] < 0 || fds[1] < 0) {
      throw IOException("could not spawn /system/bin/sh")
    }

    readFd = fds[0]
    writeFd = fds[1]
    native = ns

    val rp = ParcelFileDescriptor.adoptFd(readFd)
    val wp = ParcelFileDescriptor.adoptFd(writeFd)
    readPfd = rp
    writePfd = wp
    readIn = FileInputStream(rp.fileDescriptor)
    writeOut = FileOutputStream(wp.fileDescriptor)

    emit("[boot] persistent /system/bin/sh pid=${fds[2]} PATH=${toolsDir.absolutePath}\n", info)
    startReader()
  }

  private fun startReader() {
    val stream = readIn ?: return
    stopping = false
    Thread {
      val buf = ByteArray(8192)
      try {
        while (true) {
          val n = try { stream.read(buf) } catch (e: IOException) { break }
          if (n <= 0) break
          val text = String(buf, 0, n, Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
          emit(text)
        }
      } catch (_: Throwable) {
      }
      if (!stopping) emit("\n[shell] pipe closed - adb/shell exited\n", warn)
    }.apply { isDaemon = true; name = "adb-stdout" }.start()
  }

  private fun stopShell() {
    stopping = true
    native?.stop()
    native = null
    runCatching { readPfd?.detachFd() }
    runCatching { writePfd?.detachFd() }
    readPfd = null
    writePfd = null
    readIn = null
    writeOut = null
    readFd = -1
    writeFd = -1
  }

  private fun printFirstRunHelp() {
    emit(
      """
      |+--------------------------------------------------------------+
      ||  PAIR THIS HEADSET WITH ITS OWN ADB SERVER                    |
      |+--------------------------------------------------------------+
      |  1. Settings > System > Developer > Wireless debugging -> ON
      |
      |  2. Tap "Pair device with pairing code". Note host:port AND
      |     the 6-digit code. Keep the dialog open, it expires fast.
      |
      |  3. Tap PAIR, finish:  adb pair localhost:PORT   then SEND.
      |     Success: "Successfully paired to localhost:PORT"
      |     Then type the 6-digit code and SEND again.
      |
      |  4. Back on Wireless debugging, note "IP address & Port"
      |     (a DIFFERENT port than pairing).
      |
      |  5. Tap CONNECT, finish: adb connect localhost:PORT  SEND.
      |     Success: "connected to localhost:PORT"
      |
      |  6. Tap DEVICES - your headset should appear as "device".
      |
      |  Pairing is only needed once. After a reboot, wireless
      |  debugging turns off: turn it on and repeat step 5.
      |--------------------------------------------------------------
      """.trimMargin() + "\n",
      fgDim
    )
  }

  private companion object {
    const val MAX_LOG = 40_000
  }
}

class NativeShell {
  external fun start(adbDir: String, homeDir: String, tmpDir: String): IntArray
  external fun write(bytes: ByteArray): Int
  external fun stop()
  external fun isAlive(): Boolean
  external fun chmod(path: String, mode: Int): Int

  companion object {
    init { System.loadLibrary("qadb") }
  }
}

object AdbBootstrap {

  const val ZIP_URL =
    "https://github.com/lzhiyong/android-sdk-tools/releases/download/" +
    "33.0.3/android-sdk-tools-static-aarch64.zip"

  private const val MODE_755 = 0x1ED
  private const val MIN_ZIP_BYTES = 512 * 1024L

  private val native = NativeShell()

  fun ensureAdb(ctx: Context, log: (String) -> Unit): File {
    val root = File(ctx.filesDir, "tools")
    if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create ${root.absolutePath}")

    findAdb(root)?.takeIf { it.length() > 0 }?.let {
      makeExecutable(it, log)
      log("[tools] adb already installed (${it.length()} bytes)")
      return it
    }

    val zip = File(root, "android-sdk-tools-static-aarch64.zip")
    log("[tools] downloading android-sdk-tools-static-aarch64.zip")
    download(ZIP_URL, zip) { got, total ->
      if (total > 0) log("[tools] ${got / 1048576} / ${total / 1048576} MB")
    }

    if (!looksLikeZip(zip)) {
      zip.delete()
      throw IOException("downloaded file is not a zip")
    }

    log("[tools] extracting ${zip.length() / 1024} KB")
    unzip(zip, root)
    zip.delete()

    val adb = findAdb(root) ?: throw IOException("no 'adb' binary found in archive")
    makeExecutable(adb, log)
    return adb
  }

  private fun download(url: String, out: File, progress: (Long, Long) -> Unit) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      instanceFollowRedirects = true
      connectTimeout = 20_000
      readTimeout = 60_000
      setRequestProperty("User-Agent", "QuestAdbTerminal/1.0 (Android)")
    }
    try {
      conn.connect()
      val code = conn.responseCode
      if (code !in 200..299) throw IOException("HTTP $code from github")

      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        FileOutputStream(out).use { fos ->
          val buf = ByteArray(64 * 1024)
          var read = 0L
          var last = 0L
          while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            fos.write(buf, 0, n)
            read += n
            if (read - last >= 4L * 1024 * 1024) { last = read; progress(read, total) }
          }
          fos.flush()
          progress(read, total)
          if (total > 0 && read < total) throw IOException("truncated download")
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun looksLikeZip(f: File): Boolean {
    if (!f.isFile || f.length() < MIN_ZIP_BYTES) return false
    return f.inputStream().use { ins ->
      val sig = ByteArray(4)
      if (ins.read(sig) < 4) return false
      sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte()
    }
  }

  private fun unzip(zip: File, dest: File) {
    val destPath = dest.canonicalPath
    ZipInputStream(zip.inputStream().buffered()).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        val target = File(dest, entry.name)
        if (!target.canonicalPath.startsWith(destPath + File.separator)) {
          zis.closeEntry(); continue
        }
        if (entry.isDirectory) {
          target.mkdirs()
        } else {
          target.parentFile?.mkdirs()
          FileOutputStream(target).use { fos -> zis.copyTo(fos, 64 * 1024) }
        }
        zis.closeEntry()
      }
    }
  }

  private fun findAdb(dir: File): File? {
    val kids = dir.listFiles() ?: return null
    for (f in kids) if (f.isFile && f.name == "adb") return f
    for (f in kids) if (f.isDirectory) findAdb(f)?.let { return it }
    return null
  }

  private fun makeExecutable(f: File, log: (String) -> Unit) {
    val rc = native.chmod(f.absolutePath, MODE_755)
    if (rc != 0 || !f.canExecute()) f.setExecutable(true, false)
    log("[tools] chmod 755 rc=$rc exec=${f.canExecute()}")
  }
}
