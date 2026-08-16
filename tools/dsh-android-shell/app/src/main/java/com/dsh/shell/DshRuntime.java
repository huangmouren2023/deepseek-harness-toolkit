package com.dsh.shell;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 内置 Node/dsh 运行时的安装、启动和日志管理。 */
public final class DshRuntime {

    public interface ProgressListener {
        void onProgress(String message, int percent);
    }

    private static final String ASSET_NAME = "dsh-runtime.zip";
    private static final String INSTALL_DIR_NAME = "dsh-runtime";
    private static final String VERSION_FILE_NAME = "runtime.version";
    private static final String SOURCE_FILE_NAME = "runtime.source";
    private static final String SOURCE_BUNDLED = "bundled";
    private static final String SOURCE_NETWORK = "network";
    private static final String BUNDLED_NATIVE_NODE = "libdshnode.so";
    private static final String RUNTIME_VERSION = "dsh-0.1.0-rc.5-node24-arm64-22-android-tools5";

    private static Process process;
    private static int processPort = -1;

    private DshRuntime() {
    }

    public static String bundledVersion() {
        return RUNTIME_VERSION;
    }

    public static synchronized String installedVersion(Context context) {
        return readVersion(new File(installDir(context), VERSION_FILE_NAME));
    }

    public static File installDir(Context context) {
        return new File(context.getFilesDir(), INSTALL_DIR_NAME);
    }

    /** 用户数据根；runtime 更新永远不触碰这里。 */
    public static File userRoot(Context context) {
        return new File(context.getFilesDir(), "dsh-user");
    }

    /** dsh 自己识别的配置/会话根，插件 profile 也在其下独立维护。 */
    public static File homeDir(Context context) {
        return new File(userRoot(context), "config");
    }

    public static File projectsDir(Context context) {
        return new File(userRoot(context), "projects");
    }

    public static File pluginsDir(Context context) {
        return new File(homeDir(context), "profiles");
    }

    public static File logFile(Context context) {
        return new File(new File(userRoot(context), "logs"), "dsh.log");
    }

    public static synchronized boolean isInstalled(Context context) {
        File root = installDir(context);
        File node = new File(root, "node/bin/node");
        File entry = new File(root, "dsh/lib/bin.js");
        File version = new File(root, VERSION_FILE_NAME);
        return node.isFile() && entry.isFile() && version.isFile()
                && !readVersion(version).isEmpty();
    }

    public static void ensureInstalled(Context context, ProgressListener listener) throws IOException {
        if (isInstalled(context)) {
            String installed = installedVersion(context);
            String source = readVersion(new File(installDir(context), SOURCE_FILE_NAME));
            // APK 更新时替换旧的 APK runtime；网络安装的 runtime 属于用户
            // 选择的版本，不能被随后一次 APK 启动无声覆盖。
            if (RUNTIME_VERSION.equals(installed) || SOURCE_NETWORK.equals(source)) {
                listener.onProgress("内置运行时已就绪", 100);
                return;
            }
        }

        File temp = new File(context.getFilesDir(), INSTALL_DIR_NAME + ".tmp");
        deleteTree(temp);
        if (!temp.mkdirs() && !temp.isDirectory()) {
            throw new IOException("无法创建运行时临时目录");
        }

        listener.onProgress("正在解压内置 Node.js 和 dsh…", 5);
        try (InputStream raw = context.getAssets().open(ASSET_NAME);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                File target = safeChild(temp, entry.getName());
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) {
                        throw new IOException("无法创建运行时目录: " + entry.getName());
                    }
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建运行时目录: " + parent);
                    }
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                        }
                    }
                    target.setExecutable(true, false);
                }
                zip.closeEntry();
                count++;
                listener.onProgress("正在准备内置文件…", Math.min(95, 5 + count / 4));
            }
        } catch (Exception e) {
            deleteTree(temp);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("运行时解压失败", e);
        }

        File node = new File(temp, "node/bin/node");
        File entry = new File(temp, "dsh/lib/bin.js");
        if (!node.isFile() || !entry.isFile()) {
            deleteTree(temp);
            throw new IOException("内置 dsh 运行时不完整");
        }
        writeText(new File(temp, VERSION_FILE_NAME), RUNTIME_VERSION);
        writeText(new File(temp, SOURCE_FILE_NAME), SOURCE_BUNDLED);
        node.setExecutable(true, false);

        File installed = installDir(context);
        deleteTree(installed);
        if (!temp.renameTo(installed)) {
            deleteTree(temp);
            throw new IOException("无法提交内置运行时");
        }
        listener.onProgress("内置运行时准备完成", 100);
    }

    /** 强制从 APK 重新展开 runtime；用户配置、工程和日志完全不触碰。 */
    public static synchronized void repairBundled(Context context, ProgressListener listener) throws IOException {
        if (isRunning()) throw new IOException("runtime_running");
        deleteTree(installDir(context));
        ensureInstalled(context, listener);
    }

    /**
     * Install a downloaded runtime package transactionally. The current
     * runtime is kept until the new archive is completely extracted, checked,
     * and optionally SHA-256 verified. A running child is deliberately not
     * replaced; the caller can apply the package on the next launch.
     */
    public static synchronized void installUpdate(
            Context context,
            InputStream source,
            String version,
            String expectedSha256,
            ProgressListener listener) throws IOException {
        if (isRunning()) throw new IOException("runtime_running");
        if (version == null || version.trim().isEmpty()) throw new IOException("runtime_version_missing");
        installZipPackage(context, source, version.trim(), expectedSha256, listener, "update");
    }

    private static void installZipPackage(
            Context context,
            InputStream source,
            String version,
            String expectedSha256,
            ProgressListener listener,
            String tempSuffix) throws IOException {
        File temp = new File(context.getFilesDir(), INSTALL_DIR_NAME + "." + tempSuffix + ".tmp");
        File installed = installDir(context);
        File backup = new File(context.getFilesDir(), INSTALL_DIR_NAME + ".backup");
        deleteTree(temp);
        deleteTree(backup);
        if (!temp.mkdirs() && !temp.isDirectory()) throw new IOException("runtime_temp_create_failed");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("sha256_unavailable", e);
        }
        listener.onProgress("正在下载/解压 runtime 更新", 5);
        try (InputStream raw = source;
             DigestInputStream checked = new DigestInputStream(new BufferedInputStream(raw), digest);
             ZipInputStream zip = new ZipInputStream(checked)) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                File target = safeChild(temp, entry.getName());
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("runtime_dir_create_failed");
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("runtime_parent_create_failed");
                    }
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                    target.setExecutable(true, false);
                }
                zip.closeEntry();
                count++;
                listener.onProgress("正在校验 runtime 文件", Math.min(92, 5 + count / 4));
            }
        } catch (Exception e) {
            deleteTree(temp);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("runtime_update_extract_failed", e);
        }

        if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                deleteTree(temp);
                throw new IOException("runtime_sha256_mismatch");
            }
        }
        File node = new File(temp, "node/bin/node");
        File entry = new File(temp, "dsh/lib/bin.js");
        if (!node.isFile() || !entry.isFile()) {
            deleteTree(temp);
            throw new IOException("runtime_update_incomplete");
        }
        writeText(new File(temp, VERSION_FILE_NAME), version);
        writeText(new File(temp, SOURCE_FILE_NAME), SOURCE_NETWORK);
        node.setExecutable(true, false);

        if (installed.exists() && !installed.renameTo(backup)) {
            deleteTree(temp);
            throw new IOException("runtime_backup_failed");
        }
        if (!temp.renameTo(installed)) {
            deleteTree(temp);
            if (backup.exists()) backup.renameTo(installed);
            throw new IOException("runtime_commit_failed");
        }
        deleteTree(backup);
        listener.onProgress("runtime 更新已准备好，下次启动生效", 100);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    public static synchronized Process start(Context context, int port) throws IOException {
        if (process != null && process.isAlive() && processPort == port) {
            return process;
        }
        stop();

        File root = installDir(context);
        String source = readVersion(new File(root, SOURCE_FILE_NAME));
        File node = SOURCE_BUNDLED.equals(source)
                ? new File(context.getApplicationInfo().nativeLibraryDir, BUNDLED_NATIVE_NODE)
                : new File(root, "node/bin/node");
        File entry = new File(root, "dsh/lib/bin.js");
        if (!node.isFile()) throw new IOException("dsh_node_executable_missing");
        File userRoot = userRoot(context);
        File home = homeDir(context);
        File projects = projectsDir(context);
        File plugins = pluginsDir(context);
        File log = logFile(context);
        if (!userRoot.exists() && !userRoot.mkdirs()) {
            throw new IOException("无法创建 dsh 用户数据目录");
        }
        if (!home.exists() && !home.mkdirs()) {
            throw new IOException("无法创建 dsh 数据目录");
        }
        if (!projects.exists() && !projects.mkdirs()) {
            throw new IOException("无法创建 dsh 工程目录");
        }
        if (!plugins.exists() && !plugins.mkdirs()) {
            throw new IOException("无法创建 dsh 插件目录");
        }
        File logParent = log.getParentFile();
        if (logParent != null && !logParent.exists() && !logParent.mkdirs()) {
            throw new IOException("无法创建 dsh 日志目录");
        }

                // Android ships the browser/chat surface without the desktop-only
                // sandbox and PTY backends. Keep this overlay separate from user
                // patches so upgrades never rewrite user configuration.
                File androidPatch = new File(home, "android-runtime.cordis.patch.yml");
                writeText(androidPatch,
                // Ordinary child-process execution remains the Android shell
                        // foundation; only the desktop confinement/PTY layers stay off.
                "- id: hmr\n"
                        + "  disabled: true\n"
                        + "- id: subprocess\n"
                        + "  disabled: false\n"
                        + "- id: sandbox\n"
                        + "  disabled: true\n"
                        + "- id: bash-sandbox\n"
                        + "  disabled: true\n"
                        + "- id: permission\n"
                        + "  disabled: true\n"
                        + "- id: tool-bash\n"
                        + "  disabled: false\n"
                        + "- insert:\n"
                        + "    - id: android-bash-local\n"
                        + "      name: '@deepseek-ai/dsh-bash-local'\n");

        ProcessBuilder builder = new ProcessBuilder(
                node.getAbsolutePath(), entry.getAbsolutePath(),
                "--profile", "web", "--patch", androidPatch.getAbsolutePath(),
                "--host", "127.0.0.1", "--port", String.valueOf(port));
        Map<String, String> env = builder.environment();
        env.put("HOME", userRoot.getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("DSH_CWD", projects.getAbsolutePath());
        // Android app-private storage does not permit the desktop profile
        // symlink fallback's readlink path. Shipped bundles resolve from the
        // immutable installation anchor instead; user/plugin data stays in
        // its independent directories above.
        env.put("DSH_ANDROID", "1");
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        File nodeRoot = new File(root, "node");
        env.put("PREFIX", nodeRoot.getAbsolutePath());
        env.put("PATH", new File(nodeRoot, "bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", SOURCE_BUNDLED.equals(source)
                ? context.getApplicationInfo().nativeLibraryDir
                : new File(nodeRoot, "lib").getAbsolutePath());
        env.put("DSH_TELEMETRY_DISABLED", "1");
        builder.directory(home);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        process = builder.start();
        processPort = port;
        return process;
    }

    public static synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public static synchronized int runningPort() {
        return processPort;
    }

    public static synchronized void stop() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            process = null;
            processPort = -1;
        }
    }

    private static File safeChild(File root, String name) throws IOException {
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) {
            throw new IOException("非法运行时路径: " + name);
        }
        return target;
    }

    private static String readVersion(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[128];
            int n = in.read(bytes);
            return n <= 0 ? "" : new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        // 这里只处理 APP 私有目录中的临时/旧运行时。
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
