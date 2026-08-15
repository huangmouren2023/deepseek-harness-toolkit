package com.dsh.shell;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Network manifest/download helper for the optional runtime update channel. */
public final class DshRuntimeUpdater {

    public interface ProgressListener extends DshRuntime.ProgressListener {
    }

    public static final class UpdateInfo {
        public final String version;
        public final String packageUrl;
        public final String sha256;

        private UpdateInfo(String version, String packageUrl, String sha256) {
            this.version = version;
            this.packageUrl = packageUrl;
            this.sha256 = sha256;
        }
    }

    private DshRuntimeUpdater() {
    }

    public static UpdateInfo fetchManifest(String manifestUrl) throws IOException {
        HttpURLConnection connection = open(manifestUrl);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("manifest_http_" + code);
            String json = readText(connection.getInputStream(), 1024 * 1024);
            JSONObject object = new JSONObject(json);
            String version = object.optString("version", "").trim();
            String packageUrl = object.optString("url", "").trim();
            String sha256 = object.optString("sha256", "").trim();
            if (version.isEmpty() || packageUrl.isEmpty()) throw new IOException("manifest_incomplete");
            validateHttpUrl(packageUrl);
            return new UpdateInfo(version, packageUrl, sha256);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("manifest_invalid", e);
        } finally {
            connection.disconnect();
        }
    }

    public static void downloadAndInstall(
            Context context,
            UpdateInfo info,
            ProgressListener listener) throws IOException {
        HttpURLConnection connection = open(info.packageUrl);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("runtime_http_" + code);
            long total = connection.getContentLengthLong();
            listener.onProgress("正在下载 runtime 更新", 1);
            try (InputStream raw = connection.getInputStream();
                 CountingInputStream counted = new CountingInputStream(raw, total, listener)) {
                DshRuntime.installUpdate(context, counted, info.version, info.sha256, listener);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String value) throws IOException {
        validateHttpUrl(value);
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, application/zip, */*");
        return connection;
    }

    private static void validateHttpUrl(String value) throws IOException {
        if (!(value.startsWith("https://") || value.startsWith("http://"))) {
            throw new IOException("only_http_url_supported");
        }
    }

    private static String readText(InputStream source, int maxBytes) throws IOException {
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("manifest_too_large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long total;
        private final ProgressListener listener;
        private long count;
        private int lastPercent = -1;

        CountingInputStream(InputStream source, long total, ProgressListener listener) {
            super(source);
            this.total = total;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) report(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) report(n);
            return n;
        }

        private void report(int bytes) {
            count += bytes;
            if (total <= 0) return;
            int percent = (int) Math.min(50, Math.max(1, count * 50 / total));
            if (percent != lastPercent) {
                lastPercent = percent;
                listener.onProgress("正在下载 runtime 更新", percent);
            }
        }
    }
}
