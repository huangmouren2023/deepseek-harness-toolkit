package com.dsh.shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DshProbe {

    public static final int TIMEOUT_MS = 3000;

    public enum Status { RUNNING, NOT_RUNNING, CONFLICT }

    public static class Result {
        public final Status status;
        public final int httpCode;

        Result(Status status, int httpCode) {
            this.status = status;
            this.httpCode = httpCode;
        }
    }

    public static Result probe(int port) {
        // 先做 TCP 端口探测：连不上 = 端口空闲，dsh 未启动
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
        } catch (Exception e) {
            return new Result(Status.NOT_RUNNING, -1);
        }

        // 端口有服务，再确认是不是 dsh web
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "text/html,*/*");

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                String body = readFully(conn);
                if (body != null && body.contains("__DSH_BOOT__")) {
                    return new Result(Status.RUNNING, code);
                }
            }
            // 端口被别的服务占用
            return new Result(Status.CONFLICT, code);
        } catch (Exception e) {
            // 端口有服务但不是一个可读的 HTTP 服务
            return new Result(Status.CONFLICT, -1);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readFully(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int n;
        int total = 0;
        while ((n = br.read(buf)) != -1 && total < 16384) {
            sb.append(buf, 0, n);
            total += n;
        }
        br.close();
        return sb.toString();
    }
}
