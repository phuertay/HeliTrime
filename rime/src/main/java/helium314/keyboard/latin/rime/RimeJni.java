package helium314.keyboard.latin.rime;

/**
 * The only JNI entry points for librime. The library loads the first time a
 * Rime subtype asks for it. A missing library leaves English on the Latin path.
 */
public final class RimeJni {
    private static boolean loaded;
    private static UnsatisfiedLinkError loadError;

    private RimeJni() {}

    public static synchronized boolean load() {
        if (loaded) return true;
        if (loadError != null) return false;
        try {
            System.loadLibrary("rime_jni");
            loaded = true;
            return true;
        } catch (UnsatisfiedLinkError error) {
            loadError = error;
            android.util.Log.e("RimeJni", "librime failed to load", error);
            return false;
        }
    }

    public static UnsatisfiedLinkError loadError() {
        return loadError;
    }

    public static native boolean nativeStartup(String sharedDir, String userDir, String prebuiltDir);
    public static native void nativeShutdown();
    public static native boolean nativeDeploy();
    public static native long nativeCreateSession(String schemaId);
    public static native void nativeDestroySession(long session);
    public static native boolean nativeProcessKey(long session, int keycode, int mask);
    public static native boolean nativeSelectCandidate(long session, int index);
    public static native boolean nativeChangePage(long session, boolean backward);
    public static native boolean nativeCommitComposition(long session);
    public static native void nativeClearComposition(long session);
    public static native void nativeSetOption(long session, String option, boolean value);
    public static native boolean nativeGetOption(long session, String option);
    public static native String nativeReadSnapshot(long session);
}
