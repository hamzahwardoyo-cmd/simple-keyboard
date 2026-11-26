package rkr.simplekeyboard.inputmethod.ai;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiImeClient {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String endpointUrl;

    public interface StreamCallback {
        void onToken(String chunk);
        void onComplete(String fullText);
        void onError(String message);
    }

    public GeminiImeClient(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public void streamAction(String prompt, String editorText, String action, StreamCallback callback) {
        executor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("prompt", prompt);
                body.put("editorText", editorText);
                body.put("action", action);
                body.put("speedPreset", "fast");

                URL url = new URL(endpointUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    postError(callback, "HTTP " + code);
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
                );
                String line;
                String currentEvent = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        JSONObject data = new JSONObject(line.substring(6));
                        if ("chunk".equals(currentEvent)) {
                            String chunk = data.optString("text", "");
                            fullResponse.append(chunk);
                            mainHandler.post(() -> callback.onToken(chunk));
                        } else if ("error".equals(currentEvent)) {
                            postError(callback, data.optString("error", "API Error"));
                            return;
                        }
                    }
                }
                String finalText = fullResponse.toString();
                mainHandler.post(() -> callback.onComplete(finalText));
            } catch (Exception e) {
                postError(callback, e.getMessage() != null ? e.getMessage() : "Network error");
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private void postError(StreamCallback cb, String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }
                }
