package helium314.keyboard.latin.pinyin

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** The only app type that calls [RimeNative]. Every call runs on one handler thread. */
class RimeSession(private val context: Context) : RimeClient {
    private val thread = HandlerThread("rime")
    private val handler: Handler
    private var startedKey: String? = null

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun dataReady(): Boolean {
        val shared = sharedDir()
        val installed = File(shared, "VERSION")
        if (!installed.isFile || !File(shared, "build").isDirectory) return false
        val wanted = context.assets.open("rime/VERSION").bufferedReader().use { it.readText() }
        return installed.readText() == wanted
    }

    fun pathsFor(incognito: Boolean): Triple<String, String, String> {
        val shared = sharedDir()
        val user = userDirectory(incognito)
        user.mkdirs()
        return Triple(shared.absolutePath, user.absolutePath, File(shared, "build").absolutePath)
    }

    /** Copy shared data if needed, then start the engine off the UI thread. */
    fun prepareAsync(incognito: Boolean, onDone: PrepareCallback) {
        val mainHandler = Handler(Looper.getMainLooper())
        handler.post {
            val paths = try {
                if (!ensureSharedCopied()) null
                else {
                    val triple = pathsFor(incognito)
                    if (startup(triple.first, triple.second, triple.third)) triple else null
                }
            } catch (error: UnsatisfiedLinkError) {
                null
            }
            mainHandler.post { onDone.onDone(paths) }
        }
    }

    fun close() {
        try {
            call {
                if (startedKey != null && RimeNative.ensureLoaded()) RimeNative.nativeFinalize()
                startedKey = null
            }
        } catch (error: UnsatisfiedLinkError) {
            startedKey = null
        }
        thread.quitSafely()
    }

    override fun startup(sharedDir: String, userDir: String, prebuiltDir: String): Boolean = call {
        try {
            if (!RimeNative.ensureLoaded()) return@call false
            val key = "$sharedDir|$userDir|$prebuiltDir"
            if (startedKey == key) return@call true
            if (startedKey != null) RimeNative.nativeFinalize()
            val started = RimeNative.nativeStartup(sharedDir, userDir, prebuiltDir)
            startedKey = if (started) key else null
            started
        } catch (error: UnsatisfiedLinkError) {
            startedKey = null
            false
        }
    }

    override fun releaseIncognitoDir() {
        call {
            val usingCache = startedKey?.contains(INCOGNITO_DIR) == true
            if (usingCache && RimeNative.ensureLoaded()) {
                RimeNative.nativeFinalize()
                startedKey = null
            }
            File(context.cacheDir, INCOGNITO_DIR).deleteRecursively()
        }
    }

    override fun createSession(): Long = call { RimeNative.nativeCreateSession() }
    override fun destroySession(session: Long) = call { RimeNative.nativeDestroySession(session) }
    override fun selectSchema(session: Long, schemaId: String): Boolean = call { RimeNative.nativeSelectSchema(session, schemaId) }
    override fun setOption(session: Long, option: String, value: Boolean) = call { RimeNative.nativeSetOption(session, option, value) }
    override fun processKey(session: Long, keycode: Int, mask: Int): Boolean = call { RimeNative.nativeProcessKey(session, keycode, mask) }
    override fun selectCandidate(session: Long, index: Int): Boolean = call { RimeNative.nativeSelectCandidate(session, index) }
    override fun commitComposition(session: Long): Boolean = call { RimeNative.nativeCommitComposition(session) }
    override fun clearComposition(session: Long) = call { RimeNative.nativeClearComposition(session) }
    override fun readSnapshot(session: Long): RimeSnapshot = call { RimeSnapshot.fromNative(RimeNative.nativeReadSnapshot(session)) }

    private fun sharedDir() = File(File(context.filesDir, "rime"), "shared")

    private fun userDirectory(incognito: Boolean): File {
        return if (incognito) File(context.cacheDir, INCOGNITO_DIR)
        else File(File(context.filesDir, "rime"), "user")
    }

    private fun ensureSharedCopied(): Boolean {
        val shared = sharedDir()
        val installed = File(shared, "VERSION")
        val wanted = context.assets.open("rime/VERSION").bufferedReader().use { it.readText() }
        if (installed.isFile && installed.readText() == wanted && File(shared, "build").isDirectory) return true
        shared.deleteRecursively()
        copyAssetDir("rime", shared)
        return File(shared, "VERSION").isFile
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(dest, child))
    }

    private fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val box = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        handler.post {
            try {
                box.set(block())
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error.get()?.let { throw it }
        return box.get()
    }

    private companion object {
        const val INCOGNITO_DIR = "rime-incognito"
    }
}

fun interface PrepareCallback {
    fun onDone(paths: Triple<String, String, String>?)
}
