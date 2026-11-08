package helium314.keyboard.latin.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** librime on a device: n i h a o, then space, commits 你好. Not the keyboard. */
@RunWith(AndroidJUnit4.class)
public class NihaoTest {
    @Test
    public void nihaoSpaceCommitsNiHao() throws Exception {
        assertTrue(RimeJni.load());
        Context files = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context assets = InstrumentationRegistry.getInstrumentation().getContext();
        File shared = new File(files.getFilesDir(), "rime-nihao-shared");
        File user = new File(files.getFilesDir(), "rime-nihao-user");
        delete(shared);
        delete(user);
        copyAssets(assets.getAssets(), "", shared);
        user.mkdirs();
        assertTrue(new File(shared, "build/luna_pinyin.table.bin").isFile());
        assertTrue(RimeJni.nativeStartup(shared.getAbsolutePath(), user.getAbsolutePath(),
                new File(shared, "build").getAbsolutePath()));
        long session = RimeJni.nativeCreateSession("luna_pinyin");
        assertTrue(session != 0);
        RimeJni.nativeSetOption(session, "zh_hans", true);
        RimeJni.nativeSetOption(session, "simplification", true);
        RimeJni.nativeSetOption(session, "ascii_mode", false);
        for (char key : "nihao".toCharArray()) {
            RimeJni.nativeProcessKey(session, key, 0);
        }
        JSONObject composed = new JSONObject(RimeJni.nativeReadSnapshot(session));
        JSONArray candidates = composed.getJSONArray("candidates");
        assertTrue(composed.toString(), candidates.length() > 0);
        assertEquals("你好", candidates.getJSONObject(0).getString("text"));
        RimeJni.nativeProcessKey(session, 0x20, 0);
        JSONObject committed = new JSONObject(RimeJni.nativeReadSnapshot(session));
        assertEquals(committed.toString(), "你好", committed.getString("commit"));
        RimeJni.nativeDestroySession(session);
        RimeJni.nativeShutdown();
    }

    private static void copyAssets(AssetManager assets, String path, File dest) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            if (path.isEmpty()) return;
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return;
        }
        dest.mkdirs();
        for (String child : children) {
            String childPath = path.isEmpty() ? child : path + "/" + child;
            copyAssets(assets, childPath, new File(dest, child));
        }
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) delete(child);
            }
        }
        file.delete();
    }
}
