package rkr.simplekeyboard.inputmethod.ai;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rkr.simplekeyboard.inputmethod.BuildConfig;

public class GeminiImeClient {
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";

    private static final String[] MODEL_CANDIDATES = new String[] {
            "openrouter/free",
            "openrouter/free",
            "qwen/qwen3-next-80b-a3b-instruct:free",
            "openrouter/free"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface StreamCallback {
        void onToken(String chunk);
        void onComplete(String fullText);
        void onError(String message);
    }

    public GeminiImeClient(String ignoredEndpointUrl) {
        // Uses BuildConfig.OPENROUTER_API_KEY injected via GitHub Actions Secrets
    }

    public void streamAction(String prompt, String editorText, String action, StreamCallback callback) {
        executor.execute(() -> {
            String apiKey = BuildConfig.OPENROUTER_API_KEY;
            if (apiKey == null || apiKey.trim().isEmpty()) {
                postError(callback, "OPENROUTER_API_KEY belum diisi di GitHub Secrets.");
                return;
            }

            String systemText = buildSystemInstruction(action);
            String userText = buildUserMessage(action, prompt, editorText);
            String lastError = "Network error";

            for (int attempt = 0; attempt < MODEL_CANDIDATES.length; attempt++) {
                String modelName = MODEL_CANDIDATES[attempt];
                StringBuilder fullResponse = new StringBuilder();
                HttpURLConnection conn = null;

                try {
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "system").put("content", systemText));
                    messages.put(new JSONObject().put("role", "user").put("content", userText));

                    JSONObject body = new JSONObject();
                    body.put("model", modelName);
                    body.put("messages", messages);
                    body.put("stream", true);

                    URL url = new URL(OPENROUTER_API_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                    conn.setRequestProperty("HTTP-Referer", "https://github.com/hamzahwardoyo-cmd/simple-keyboard");
                    conn.setRequestProperty("X-Title", "Simple Keyboard AI");

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        String errBody = readStream(conn.getErrorStream());
                        lastError = "HTTP " + code + " (" + modelName + ")" + (errBody.isEmpty() ? "" : ": " + errBody);
                        if ((code == 503 || code == 429 || code == 404 || code == 502) && attempt < MODEL_CANDIDATES.length - 1) {
                            Thread.sleep(1000);
                            continue;
                        }
                        postError(callback, lastError);
                        return;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
                    );
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String jsonStr = line.substring(6).trim();
                            if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) continue;
                            JSONObject data = new JSONObject(jsonStr);
                            JSONArray choices = data.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) continue;
                            JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                            if (delta == null) continue;
                            String chunk = delta.optString("content", "");
                            if (!chunk.isEmpty()) {
                                fullResponse.append(chunk);
                                mainHandler.post(() -> callback.onToken(chunk));
                            }
                        }
                    }

                    String finalText = fullResponse.toString();
                    mainHandler.post(() -> callback.onComplete(finalText));
                    return;
                } catch (Exception e) {
                    lastError = e.getMessage() != null ? e.getMessage() : "Network error";
                    if (attempt < MODEL_CANDIDATES.length - 1) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {}
                        continue;
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }

            postError(callback, lastError);
        });
    }

    private String buildSystemInstruction(String action) {
        String base = "You are a fast, concise AI assistant built directly inside a mobile keyboard. Return ready-to-insert text without unnecessary preamble.";
        switch (action) {
            case "grammar":
                return base + " Fix spelling and grammar while keeping the original language. Output ONLY the corrected text.";
            case "formal":
                return base + " Rewrite in a polite, formal tone in the same language. Output ONLY the rewritten text.";
            case "casual":
                return base + " Rewrite in a natural, casual tone in the same language. Output ONLY the rewritten text.";
            case "translate_en":
                return base + " Translate accurately into natural English. Output ONLY the translation.";
            case "reply":
                return base + " Draft a short, natural reply ready to send. Output ONLY the reply.";
            default:
                return base + " Answer clearly and concisely inside a compact keyboard panel.";
        }
    }

    private String buildUserMessage(String action, String prompt, String editorText) {
        boolean hasEditor = editorText != null && !editorText.trim().isEmpty();
        boolean hasPrompt = prompt != null && !prompt.trim().isEmpty();
        if ("ask".equals(action)) {
            if (hasEditor && hasPrompt) {
                return "App text:\n" + editorText + "\n\nRequest: " + prompt;
            }
            return hasPrompt ? prompt : (hasEditor ? editorText : "");
        }
        return hasEditor ? editorText : (hasPrompt ? prompt : "");
    }

    private String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void postError(StreamCallback cb, String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }
}
