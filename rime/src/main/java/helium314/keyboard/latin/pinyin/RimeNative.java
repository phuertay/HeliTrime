package helium314.keyboard.latin.pinyin;

/**
 * JNI entry for librime. RimeSession is the only app type that calls this.
 * The library loads on first use so a missing .so does not crash English.
 */
public final class RimeNative {
    private static volatile boolean loaded;
    private static volatile String loadError;

    private RimeNative() {}

    public static boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("rime_jni");
            loaded = true;
            loadError = null;
            return true;
        } catch (UnsatisfiedLinkError error) {
            loadError = error.getMessage();
            return false;
        }
    }

    public static String getLoadError() {
        return loadError;
    }

    public static native boolean nativeStartup(String sharedDir, String userDir, String prebuiltDir);
    public static native void nativeFinalize();
    public static native long nativeCreateSession();
    public static native void nativeDestroySession(long session);
    public static native boolean nativeSelectSchema(long session, String schemaId);
    public static native void nativeSetOption(long session, String option, boolean value);
    public static native boolean nativeGetOption(long session, String option);
    public static native boolean nativeProcessKey(long session, int keycode, int mask);
    public static native boolean nativeSelectCandidate(long session, int index);
    public static native boolean nativeCommitComposition(long session);
    public static native void nativeClearComposition(long session);
    public static native String[] nativeReadSnapshot(long session);
}
