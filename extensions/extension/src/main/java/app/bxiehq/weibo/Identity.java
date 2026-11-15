package app.bxiehq.weibo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Properties;

/** Per-process identity adapter. The Android package's real signing key is unchanged. */
@SuppressWarnings({"unused", "deprecation"})
public final class Identity {
    private static final String TAG = "WeiboIdentity";
    private static final String PACKAGE = "com.sina.weibo";
    private static final String ORIGINAL = "weibo-original-8150.apk";
    private static Signature originalSignature;
    private static boolean installed;
    private static boolean nativeReady;

    private Identity() {}

    private static native boolean configure(String originalPath, String installedPath);
    private static native void setActive(boolean enabled);

    public static void active(boolean enabled) {
        if (nativeReady) setActive(enabled);
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        try {
            Properties metadata = new Properties();
            try (InputStream input = context.getAssets().open("weibo-repair.properties")) {
                metadata.load(input);
            }
            try (InputStream input = context.getAssets().open("weibo-repair-cert.der")) {
                originalSignature = new Signature(readFully(input));
            }
            File original = prepareOriginalApk(context, metadata);
            // dlopen in the JNI helper loads the app's ShadowHook without invoking its Java JNI_OnLoad.
            System.loadLibrary("weiboidentity");
            nativeReady = configure(original.getCanonicalPath(), context.getApplicationInfo().sourceDir);
            if (!nativeReady) throw new IllegalStateException("Native identity hooks could not be installed");
            installParcelCreator();
            installPackageManagerProxy(context);
            // Android may have cached these PackageInfo objects before the proxy was installed.
            fix(context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES));
            fix(context.getPackageManager().getPackageInfo(PACKAGE, 0x08000000));
            PackageInfo verify = context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES);
            if (verify.signatures == null || verify.signatures.length == 0 ||
                    !originalSignature.equals(verify.signatures[0])) {
                throw new IllegalStateException("Original certificate is not visible to this process");
            }
            installed = true;
            Log.i(TAG, "Original client identity initialized");
        } catch (Exception | LinkageError failure) {
            Log.e(TAG, "Unable to initialize Weibo identity repair", failure);
        }
    }

    private static File prepareOriginalApk(Context context, Properties metadata) throws Exception {
        String expectedHash = metadata.getProperty("sha256");
        long expectedLength = Long.parseLong(metadata.getProperty("length"));
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}") || expectedLength <= 0) {
            throw new IllegalStateException("Original APK metadata is invalid");
        }
        File directory = context.getFilesDir();
        File destination = new File(directory, ORIGINAL);
        File marker = new File(directory, ORIGINAL + ".sha256");
        // The main and remote processes share this file. Never expose a partial copy to either.
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ORIGINAL + ".lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            if (destination.isFile() && destination.length() == expectedLength && marker.isFile()) {
                try (InputStream input = new FileInputStream(marker)) {
                    if (expectedHash.equals(new String(readFully(input), StandardCharsets.US_ASCII))) return destination;
                }
            }
            File temporary = new File(directory, ORIGINAL + ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = context.getAssets().open("weibo-repair-original.apk");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > expectedLength) throw new IllegalStateException("Original APK length differs");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            } catch (Exception failure) {
                temporary.delete();
                throw failure;
            }
            if (total != expectedLength || !expectedHash.equals(hex(digest.digest()))) {
                temporary.delete();
                throw new IllegalStateException("Original APK checksum differs");
            }
            if (!temporary.renameTo(destination)) throw new IllegalStateException("Unable to publish original APK");
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.write(expectedHash.getBytes(StandardCharsets.US_ASCII));
            }
            return destination;
        }
    }

    private static Object fix(Object value) {
        if (!(value instanceof PackageInfo)) return value;
        PackageInfo info = (PackageInfo) value;
        if (!PACKAGE.equals(info.packageName)) return value;
        if (info.signatures != null && info.signatures.length > 0) {
            info.signatures = new Signature[]{originalSignature};
        }
        if (info.signingInfo != null) {
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures != null && signatures.length > 0) signatures[0] = originalSignature;
        }
        return value;
    }

    private static void installParcelCreator() {
        try {
            Field field = PackageInfo.class.getField("CREATOR");
            field.setAccessible(true);
            Parcelable.Creator<?> delegate = (Parcelable.Creator<?>) field.get(null);
            field.set(null, new Parcelable.Creator<PackageInfo>() {
                @Override public PackageInfo createFromParcel(Parcel source) {
                    return (PackageInfo) fix(delegate.createFromParcel(source));
                }
                @Override public PackageInfo[] newArray(int size) { return new PackageInfo[size]; }
            });
            for (String name : new String[]{"mCreators", "sPairedCreators"}) {
                try {
                    Field cache = Parcel.class.getDeclaredField(name);
                    cache.setAccessible(true);
                    Map<?, ?> outer = (Map<?, ?>) cache.get(null);
                    synchronized (outer) {
                        for (Object inner : outer.values()) {
                            if (inner instanceof Map) ((Map<?, ?>) inner).remove(PackageInfo.class.getName());
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Cache fields differ between Android versions; the proxy below is also installed.
                }
            }
        } catch (ReflectiveOperationException failure) {
            Log.i(TAG, "PackageInfo creator unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static void installPackageManagerProxy(Context context) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Field field = activityThread.getDeclaredField("sPackageManager");
            field.setAccessible(true);
            Object delegate = field.get(null);
            Class<?> contract = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                    (instance, method, arguments) -> {
                        try { return fix(method.invoke(delegate, arguments)); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            field.set(null, proxy);
            Object manager = context.getPackageManager();
            Field local = manager.getClass().getDeclaredField("mPM");
            local.setAccessible(true);
            local.set(manager, proxy);
        } catch (ReflectiveOperationException failure) {
            Log.i(TAG, "Package manager proxy unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static byte[] readFully(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
