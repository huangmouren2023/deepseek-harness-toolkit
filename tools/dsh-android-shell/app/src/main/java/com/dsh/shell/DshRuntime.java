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
    private static final String PROOT_ASSET = "android-tools/proot";
    private static final String PROOT_WRAPPER_ASSET = "android-tools/bwrap-proot.sh";
    private static final String ROOT_TOOLS_DIR = "/data/adb/dsh";
    private static final String RUNTIME_VERSION = "dsh-0.1.0-rc.5-node24-arm64-22-android-tools5-router5";

    private static Process process;
    private static int processPort = -1;
    private static volatile boolean compatibilityFallback;

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

    public static boolean isCompatibilityFallback() {
        return compatibilityFallback;
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
        compatibilityFallback = false;

        File root = installDir(context);
        String source = readVersion(new File(root, SOURCE_FILE_NAME));
        File node = SOURCE_BUNDLED.equals(source)
                ? new File(context.getApplicationInfo().nativeLibraryDir, BUNDLED_NATIVE_NODE)
                : new File(root, "node/bin/node");
        File entry = new File(root, "dsh/lib/bin.js");
        if (!node.isFile()) throw new IOException("dsh_node_executable_missing");
        patchAndroidRuntime(root);
        File userRoot = userRoot(context);
        File home = homeDir(context);
        File projects = projectsDir(context);
        File plugins = pluginsDir(context);
        File log = logFile(context);
        DshCapabilities.Settings storedCapabilities = DshCapabilities.load(context);
        DshCapabilities.Settings capabilities = storedCapabilities;
        boolean needsCompatibility = capabilities.sandbox
                || capabilities.bashSandbox
                || capabilities.permissionPrompts;
        boolean rootAvailable = capabilities.rootShell && DshCapabilities.rootAvailable();
        if (capabilities.rootShell && !rootAvailable) {
            throw new IOException("未检测到可用的 Root su，请在能力配置中关闭 Root Shell");
        }
        if (rootAvailable && needsCompatibility) {
            installRootTools(context);
        }
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
                boolean needsProot = needsCompatibility;
                String prootRunner = new File(
                        context.getApplicationInfo().nativeLibraryDir,
                        "libdshproot.so").getAbsolutePath();
                String prootLoader = new File(
                        context.getApplicationInfo().nativeLibraryDir,
                        "libdshproot-loader.so").getAbsolutePath();
                boolean useRootRunner = rootAvailable && needsProot;
                boolean prootAvailable = !needsProot || useRootRunner;
                if (!useRootRunner && needsProot) {
                    try {
                        verifyProotRunner(prootRunner, prootLoader, log, context.getCacheDir());
                        prootAvailable = true;
                    } catch (IOException error) {
                        compatibilityFallback = true;
                        try {
                            appendText(log, "\n[android] PRoot unavailable; falling back to unconfined /system/bin/sh: "
                                    + error.getMessage() + "\n");
                        } catch (IOException ignored) {
                            // The fallback must not depend on diagnostic-log availability.
                        }
                    }
                }
                boolean sandboxEnabled = capabilities.sandbox && prootAvailable;
                boolean bashSandboxEnabled = capabilities.bashSandbox && prootAvailable;
                boolean permissionEnabled = capabilities.permissionPrompts && prootAvailable;
                writeText(androidPatch,
                // Ordinary child-process execution remains the Android shell
                        // foundation; only the desktop confinement/PTY layers stay off.
                "- id: hmr\n"
                        + "  disabled: true\n"
                        + "- id: subprocess\n"
                        + "  disabled: false\n"
                        + "- id: sandbox\n"
                        + "  disabled: " + (!sandboxEnabled) + "\n"
                        + (sandboxEnabled
                        ? "  config:\n    runnerCommand: "
                                + (useRootRunner
                                ? "['/system/bin/su', '-c', '/data/adb/dsh/bwrap-proot.sh']"
                                : "['" + prootRunner + "']")
                                + "\n    runnerFailureSignatures: ['permission denied', 'not found', 'proot', 'bwrap-proot']\n"
                        : "")
                        + "- id: bash-sandbox\n"
                        + "  disabled: " + (!bashSandboxEnabled) + "\n"
                        + "- id: permission\n"
                        + "  disabled: " + (!permissionEnabled) + "\n"
                        + "- id: tool-bash\n"
                        + "  disabled: false\n"
                        + (!bashSandboxEnabled
                        ? "- insert:\n"
                                + "    - id: android-bash-local\n"
                                + "      name: '@deepseek-ai/dsh-bash-local'\n"
                        : ""));

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
        env.put("DSH_ROOT", capabilities.rootShell ? "1" : "0");
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        if (!capabilities.rootShell) {
            env.put("PROOT_TMP_DIR", context.getCacheDir().getAbsolutePath());
            env.put("PROOT_LOADER", new File(
                    context.getApplicationInfo().nativeLibraryDir,
                    "libdshproot-loader.so").getAbsolutePath());
        }
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

    private static void installRootTools(Context context) throws IOException {
        File proot = new File(context.getCacheDir(), "dsh-proot");
        File wrapper = new File(context.getCacheDir(), "dsh-bwrap-proot.sh");
        copyAsset(context, PROOT_ASSET, proot);
        copyAsset(context, PROOT_WRAPPER_ASSET, wrapper);
        String command = "mkdir -p " + ROOT_TOOLS_DIR
                + " && cp " + proot.getAbsolutePath() + " " + ROOT_TOOLS_DIR + "/proot"
                + " && cp " + wrapper.getAbsolutePath() + " " + ROOT_TOOLS_DIR + "/bwrap-proot.sh"
                + " && chmod 755 " + ROOT_TOOLS_DIR + "/proot " + ROOT_TOOLS_DIR + "/bwrap-proot.sh";
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("root_tool_install_failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("root_tool_install_interrupted", e);
        }
    }

    /** Confirm that the APK-native PRoot binary is executable in the app domain. */
    private static void verifyProotRunner(String runner, String loader, File log, File tempDir)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                runner, "-0", "-r", "/", "/system/bin/echo", "dsh-proot-preflight")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        builder.environment().put("PROOT_TMP_DIR", tempDir.getAbsolutePath());
        builder.environment().put("PROOT_LOADER", loader);
        Process process = builder.start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("proot_runner_preflight_failed");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("proot_runner_preflight_interrupted", e);
        }
    }

    /**
     * The Android bundle never uses the Windows ACL runner, but the published
     * sandbox-local module imports that desktop package statically. Replace
     * that unused surface with an Android-only stub after extraction so Koffi
     * is not loaded merely while composing the Android PRoot runner.
     */
    private static void patchAndroidRuntime(File root) throws IOException {
        patchAgentPresetResolution(root);

        File aclEntry = new File(root,
                "dsh/node_modules/@deepseek-ai/dsh-sandbox-windows-acl/lib/index.js");
        if (aclEntry.isFile()) writeText(aclEntry,
                "// Android runtime stub: Windows ACL is not used by the Android runner.\n"
                        + "class AndroidUnsupportedAclError extends Error {\n"
                        + "  constructor() {\n"
                        + "    super('Windows ACL sandbox is not available in the Android runtime');\n"
                        + "    this.name = 'AndroidUnsupportedAclError';\n"
                        + "  }\n"
                        + "}\n"
                        + "class AclWriteGrant {\n"
                        + "  static create() { throw new AndroidUnsupportedAclError(); }\n"
                        + "}\n"
                        + "class AclSandbox {\n"
                        + "  constructor() { throw new AndroidUnsupportedAclError(); }\n"
                        + "}\n"
                        + "class Win32Error extends AndroidUnsupportedAclError {}\n"
                        + "function unsupportedPath() { throw new AndroidUnsupportedAclError(); }\n"
                        + "const quoteArg = unsupportedPath;\n"
                        + "const assertTempRootOutsideWorkspace = unsupportedPath;\n"
                        + "const tempWriteSid = unsupportedPath;\n"
                        + "const workspaceWriteSid = unsupportedPath;\n"
                        + "export { AclSandbox, AclWriteGrant, AndroidUnsupportedAclError, Win32Error, "
                        + "assertTempRootOutsideWorkspace, quoteArg, tempWriteSid, workspaceWriteSid };\n");

        // sandbox-local imports the Windows package statically. Patch that
        // import too, so a runtime shipped with a different package export
        // shape cannot pull the desktop Koffi module into Android.
        File sandboxEntry = new File(root,
                "dsh/node_modules/@deepseek-ai/dsh-sandbox-local/lib/index.js");
        if (sandboxEntry.isFile()) {
            String source = readText(sandboxEntry);
            String desktopImport = "import { AclWriteGrant, assertTempRootOutsideWorkspace, tempWriteSid, workspaceWriteSid } from \"@deepseek-ai/dsh-sandbox-windows-acl\";\n";
            String androidImports = "const AndroidUnsupportedAclError = class extends Error { constructor() { super('Windows ACL sandbox is not available in the Android runtime'); } };\n"
                    + "const AclWriteGrant = { create() { throw new AndroidUnsupportedAclError(); } };\n"
                    + "const assertTempRootOutsideWorkspace = () => { throw new AndroidUnsupportedAclError(); };\n"
                    + "const tempWriteSid = () => { throw new AndroidUnsupportedAclError(); };\n"
                    + "const workspaceWriteSid = () => { throw new AndroidUnsupportedAclError(); };\n";
            if (source.contains(desktopImport)) writeText(sandboxEntry, source.replace(desktopImport, androidImports));
        }

        File koffiEntry = new File(root, "dsh/node_modules/koffi/src/koffi/index.js");
        if (koffiEntry.isFile()) writeText(koffiEntry,
                "// Android runtime stub: desktop Win32 FFI is unavailable and unused.\n"
                        + "const unavailable = () => { throw new Error('Koffi desktop FFI is unavailable in the Android runtime'); };\n"
                        + "const koffi = { pointer: () => ({}), struct: () => ({ size: 0, alignment: 1 }), "
                        + "proto: unavailable, register: unavailable, unregister: unavailable, alloc: unavailable, "
                        + "encode: unavailable, decode: unavailable, address: unavailable, view: unavailable, "
                        + "load: unavailable, sizeof: () => 0, alignof: () => 1 };\n"
                        + "export default koffi;\n");
    }

    /**
     * Older embedded/network runtimes omit Loader internals on Android. Their
     * agent-preset fallback asks native import to resolve bare package names
     * beside a writable profile whose runtime-package links cannot be read
     * under Android SELinux. Resolve the APK's shipped packages from the real
     * runtime first, then retain the profile fallback for genuine extensions.
     * The embedded progressive-router preset therefore remains independent of
     * any manually installed copy in dsh-user.
     */
    private static void patchAgentPresetResolution(File root) throws IOException {
        File presetEntry = new File(root,
                "dsh/node_modules/@deepseek-ai/dsh-agent-presets/lib/index.js");
        if (!presetEntry.isFile()) return;
        String source = readText(presetEntry);
        String oldFallback = "if (internal === void 0) return super.import(specifier, getOuterStack);";
        String profileOnlyFallback = "if (internal === void 0) {\n"
                + "\t\t\tconst hostRequire = createRequire(base);\n"
                + "\t\t\treturn super.import(pathToFileURL(hostRequire.resolve(specifier)).href, getOuterStack);\n"
                + "\t\t}";
        if (!source.contains(oldFallback) && !source.contains(profileOnlyFallback)) return;

        String urlImport = "import { pathToFileURL } from \"node:url\";";
        if (!source.contains(urlImport)) {
            throw new IOException("agent_preset_url_import_missing");
        }
        String moduleImport = "import { createRequire } from \"node:module\";\n";
        if (!source.contains(moduleImport.trim())) {
            source = source.replace(urlImport, moduleImport + urlImport);
        }
        String resolvedFallback = "if (internal === void 0) {\n"
                + "\t\t\tlet resolved;\n"
                + "\t\t\ttry {\n"
                + "\t\t\t\tresolved = createRequire(import.meta.url).resolve(specifier);\n"
                + "\t\t\t} catch {\n"
                + "\t\t\t\tresolved = createRequire(base).resolve(specifier);\n"
                + "\t\t\t}\n"
                + "\t\t\treturn super.import(pathToFileURL(resolved).href, getOuterStack);\n"
                + "\t\t}";
        String patched = source.contains(oldFallback)
                ? source.replace(oldFallback, resolvedFallback)
                : source.replace(profileOnlyFallback, resolvedFallback);
        writeText(presetEntry, patched);
    }

    private static void copyAsset(Context context, String assetName, File target) throws IOException {
        try (InputStream input = context.getAssets().open(assetName);
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
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

    private static void appendText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
            int offset = 0;
            int n;
            while (offset < bytes.length && (n = input.read(bytes, offset, bytes.length - offset)) != -1) {
                offset += n;
            }
            return new String(bytes, 0, offset, java.nio.charset.StandardCharsets.UTF_8);
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
