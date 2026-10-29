package helium314.keyboard.latin.pinyin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Librime on a device. Not the keyboard. */
@RunWith(AndroidJUnit4::class)
class RimeNihaoTest {
    @Test
    fun nihao_is_the_first_candidate_and_space_commits_it() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = File(context.filesDir, "rime_nihao/shared")
        val user = File(context.filesDir, "rime_nihao/user")
        shared.deleteRecursively()
        user.deleteRecursively()
        copy_asset_dir(context, "rime", shared)
        user.mkdirs()
        assertTrue(RimeNative.ensureLoaded())
        assertTrue(
            RimeNative.nativeStartup(
                shared.absolutePath,
                user.absolutePath,
                File(shared, "build").absolutePath,
            ),
        )
        val session = RimeNative.nativeCreateSession()
        assertTrue(session != 0L)
        try {
            assertTrue(RimeNative.nativeSelectSchema(session, "luna_pinyin"))
            RimeNative.nativeSetOption(session, "simplification", true)
            for (key in "nihao") {
                RimeNative.nativeProcessKey(session, key.code, 0)
            }
            val before = RimeNative.nativeReadSnapshot(session)
            val candidates = candidates_of(before)
            assertTrue("candidates=$candidates preedit=${before.getOrNull(1)}", candidates.isNotEmpty())
            assertEquals("你好", candidates[0])
            RimeNative.nativeProcessKey(session, ' '.code, 0)
            val after = RimeNative.nativeReadSnapshot(session)
            assertEquals("你好", after[0])
        } finally {
            RimeNative.nativeDestroySession(session)
            RimeNative.nativeFinalize()
        }
    }

    private fun candidates_of(snapshot: Array<String>): List<String> {
        val candidates = ArrayList<String>()
        var index = 6
        while (index < snapshot.size) {
            candidates.add(snapshot[index])
            index += 2
        }
        return candidates
    }

    private fun copy_asset_dir(context: Context, asset_path: String, dest: File) {
        val children = context.assets.list(asset_path) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset_path).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copy_asset_dir(context, "$asset_path/$child", File(dest, child))
        }
    }
}
