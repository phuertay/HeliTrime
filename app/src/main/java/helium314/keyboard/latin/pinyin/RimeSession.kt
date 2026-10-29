package helium314.keyboard.latin.pinyin

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** The only app type that calls [RimeNative]. Every call runs on one handler thread. */
class RimeSession(private val context: Context) : RimeClient {
    private val thread = HandlerThread("rime")
    private val handler: Handler

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun prepareData(): Triple<String, String, String>? {
        if (!RimeNative.ensureLoaded()) return null
        val root = File(context.filesDir, "rime")
        val shared = File(root, "shared")
        val user = File(root, "user")
        val installed = File(shared, "VERSION")
        val wanted = context.assets.open("rime/VERSION").bufferedReader().use { it.readText() }
        if (!installed.isFile || installed.readText() != wanted || !File(shared, "build").isDirectory) {
            shared.deleteRecursively()
            copyAssetDir("rime", shared)
        }
        user.mkdirs()
        return Triple(shared.absolutePath, user.absolutePath, File(shared, "build").absolutePath)
    }

    override fun startup(sharedDir: String, userDir: String, prebuiltDir: String): Boolean = call {
        RimeNative.ensureLoaded() && RimeNative.nativeStartup(sharedDir, userDir, prebuiltDir)
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
        if (android.os.Looper.myLooper() == thread.looper) return block()
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
}
