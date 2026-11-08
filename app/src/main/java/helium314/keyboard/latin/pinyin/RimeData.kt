package helium314.keyboard.latin.pinyin

import android.content.Context
import java.io.File

/** Copies shared schema data into filesDir when [VERSION] changes. */
object RimeData {
    const val SCHEMA_ID = "luna_pinyin"
    private const val ASSET_ROOT = "rime"

    fun installShared(context: Context): File {
        val dest = File(context.filesDir, "rime/shared")
        val assetVersion = context.assets.open("$ASSET_ROOT/VERSION").bufferedReader().use { it.readText() }
        val installed = File(dest, "VERSION").takeIf { it.isFile }?.readText()
        val table = File(dest, "build/luna_pinyin.table.bin")
        if (installed == assetVersion && table.isFile) return dest
        dest.deleteRecursively()
        copyAsset(context, ASSET_ROOT, dest)
        return dest
    }

    fun userDir(context: Context, incognito: Boolean): File {
        val dir = if (incognito) File(context.cacheDir, "rime-incognito")
        else File(context.filesDir, "rime/user")
        dir.mkdirs()
        return dir
    }

    fun deleteIncognito(context: Context) {
        File(context.cacheDir, "rime-incognito").deleteRecursively()
    }

    fun hasPrebuiltTable(shared: File): Boolean {
        return File(shared, "build/luna_pinyin.table.bin").isFile
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAsset(context, "$assetPath/$child", File(dest, child))
        }
    }
}
