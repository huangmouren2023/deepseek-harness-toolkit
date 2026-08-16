package com.dsh.shell;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Optional DSH capabilities. Conservative defaults keep the public APK safe. */
public final class DshCapabilities {

    private static final String FILE_NAME = "android-capabilities.properties";
    private static final String KEY_FULL_ACCESS = "full_access";
    // Keep reading the old keys so an existing installation gets a sensible
    // migration instead of silently losing its previous capability choice.
    private static final String KEY_PERMISSION_PROMPTS = "permission_prompts";
    private static final String KEY_SANDBOX = "sandbox";
    private static final String KEY_BASH_SANDBOX = "bash_sandbox";
    private static final String KEY_ROOT_SHELL = "root_shell";

    private DshCapabilities() {
    }

    public static final class Settings {
        public final boolean fullAccess;
        public final boolean rootShell;

        public Settings(boolean fullAccess, boolean rootShell) {
            this.fullAccess = fullAccess;
            this.rootShell = rootShell;
        }
    }

    public static Settings load(Context context) {
        Properties properties = new Properties();
        File file = settingsFile(context);
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file);
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException ignored) {
                // A damaged optional settings file falls back to the safe defaults.
            }
        }
        boolean fullAccess = properties.containsKey(KEY_FULL_ACCESS)
                ? getBoolean(properties, KEY_FULL_ACCESS)
                : getBoolean(properties, KEY_PERMISSION_PROMPTS)
                        || getBoolean(properties, KEY_SANDBOX)
                        || getBoolean(properties, KEY_BASH_SANDBOX);
        return new Settings(fullAccess, getBoolean(properties, KEY_ROOT_SHELL));
    }

    public static void save(Context context, Settings settings) throws IOException {
        File parent = DshRuntime.homeDir(context);
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建 DSH 配置目录");
        }
        Properties properties = new Properties();
        properties.setProperty(KEY_FULL_ACCESS, Boolean.toString(settings.fullAccess));
        properties.setProperty(KEY_ROOT_SHELL, Boolean.toString(settings.rootShell));
        File file = settingsFile(context);
        try (FileOutputStream output = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "DSH Android capability settings");
        }
    }

    /** True only when a real root-capable su command answers uid=0. */
    public static boolean rootAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return process.exitValue() == 0 && output.toString().contains("uid=0");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static File settingsFile(Context context) {
        return new File(DshRuntime.homeDir(context), FILE_NAME);
    }

    private static boolean getBoolean(Properties properties, String key) {
        return Boolean.parseBoolean(properties.getProperty(key, "false"));
    }
}
