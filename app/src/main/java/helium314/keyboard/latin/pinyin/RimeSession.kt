package helium314.keyboard.latin.pinyin

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import helium314.keyboard.latin.rime.RimeJni
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/**
 * The only type that calls [RimeJni]. Every call hops to one thread and returns.
 * The suggest thread must not touch this object.
 */
class RimeSession(private val appContext: Context) : RimeClient {
    private val thread = HandlerThread("rime")
    private val handler: Handler
    private var session: Long = 0
    private var started = false
    private var userPath: String? = null
    override var failed: Boolean = false
        private set

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun hasSession(): Boolean = call { session != 0L }

    override fun ensure(incognito: Boolean, schemaId: String) {
        call {
            if (!RimeJni.load()) {
                failed = true
                throw IllegalStateException("librime")
            }
            val shared = RimeData.installShared(appContext)
            val user = RimeData.userDir(appContext, incognito)
            if (started && userPath != user.path) {
                if (session != 0L) {
                    RimeJni.nativeDestroySession(session)
                    session = 0
                }
                RimeJni.nativeShutdown()
                started = false
            }
            if (!started) {
                val prebuilt = shared.resolve("build")
                if (!RimeJni.nativeStartup(shared.path, user.path, prebuilt.path)) {
                    failed = true
                    throw IllegalStateException("startup")
                }
                started = true
                userPath = user.path
                failed = false
                if (!RimeData.hasPrebuiltTable(shared)) {
                    if (!RimeJni.nativeDeploy()) {
                        failed = true
                        throw IllegalStateException("deploy")
                    }
                }
            }
            if (session == 0L) {
                session = RimeJni.nativeCreateSession(schemaId)
                if (session == 0L) {
                    failed = true
                    throw IllegalStateException("session")
                }
                RimeJni.nativeSetOption(session, "zh_hans", true)
                RimeJni.nativeSetOption(session, "simplification", true)
            }
        }
    }

    override fun deleteIncognitoData() {
        RimeData.deleteIncognito(appContext)
    }

    override fun destroySession() = call {
        if (session != 0L && RimeJni.load()) {
            RimeJni.nativeDestroySession(session)
            session = 0
        }
    }

    override fun processKey(keysym: Int, mask: Int): Boolean = call {
        session != 0L && RimeJni.nativeProcessKey(session, keysym, mask)
    }

    override fun selectCandidate(index: Int): Boolean = call {
        session != 0L && RimeJni.nativeSelectCandidate(session, index)
    }

    override fun changePage(backward: Boolean): Boolean = call {
        session != 0L && RimeJni.nativeChangePage(session, backward)
    }

    override fun commitComposition(): Boolean = call {
        session != 0L && RimeJni.nativeCommitComposition(session)
    }

    override fun clearComposition() = call {
        if (session != 0L && RimeJni.load()) RimeJni.nativeClearComposition(session)
    }

    override fun setOption(option: String, value: Boolean) = call {
        if (session != 0L && RimeJni.load()) RimeJni.nativeSetOption(session, option, value)
    }

    override fun getOption(option: String): Boolean = call {
        session != 0L && RimeJni.load() && RimeJni.nativeGetOption(session, option)
    }

    override fun readSnapshot(): RimeSnapshot = call {
        if (session == 0L || !RimeJni.load()) return@call RimeSnapshot()
        parseSnapshot(RimeJni.nativeReadSnapshot(session))
    }

    override fun shutdown() = call {
        if (session != 0L && RimeJni.load()) {
            RimeJni.nativeDestroySession(session)
            session = 0
        }
        if (started && RimeJni.load()) RimeJni.nativeShutdown()
        started = false
        userPath = null
    }

    private fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var thrown: Throwable? = null
        handler.post {
            try {
                result = block()
            } catch (error: Throwable) {
                thrown = error
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun parseSnapshot(json: String): RimeSnapshot {
        val root = JSONObject(json)
        val candidates = ArrayList<RimeCandidate>()
        val array = root.optJSONArray("candidates")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                candidates.add(RimeCandidate(item.optString("text"), item.optString("comment")))
            }
        }
        return RimeSnapshot(
            commit = root.optString("commit"),
            preedit = root.optString("preedit"),
            cursor = root.optInt("cursor"),
            pageNo = root.optInt("pageNo"),
            pageSize = root.optInt("pageSize"),
            isLastPage = root.optBoolean("isLastPage", true),
            asciiMode = root.optBoolean("asciiMode"),
            composing = root.optBoolean("composing"),
            candidates = candidates,
        )
    }
}

object RimeSessionFactory {
    var created: Int = 0
        private set
    private var session: RimeSession? = null

    fun open(context: Context): RimeSession {
        session?.let { return it }
        created += 1
        val opened = RimeSession(context.applicationContext)
        session = opened
        return opened
    }
}
