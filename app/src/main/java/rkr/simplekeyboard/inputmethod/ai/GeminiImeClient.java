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
    private static final String GEMINI_STREAM_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface StreamCallback {
        void onToken(String chunk);
        void onComplete(String fullText);
        void onError(String message);
    }

    public GeminiImeClient(String ignoredEndpointUrl) {
        // Uses BuildConfig.GEMINI_API_KEY injected via GitHub Actions Secrets
    }

    public void streamAction(String prompt, String editorText, String action, StreamCallback callback) {
        executor.execute(() -> {
            String apiKey = BuildConfig.GEMINI_API_KEY;
            if (apiKey == null || apiKey.trim().isEmpty()) {
                postError(callback, "GEMINI_API_KEY belum diisi di GitHub Secrets.");
                return;
            }

            StringBuilder fullResponse = new StringBuilder();
            HttpURLConnection conn = null;
            try {
                String systemText = buildSystemInstruction(action);
                String userText = buildUserMessage(action, prompt, editorText);

                JSONObject body = new JSONObject();
                body.put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", systemText))));
                body.put("contents", new JSONArray().put(
                        new JSONObject()
                                .put("role", "user")
                                .put("parts", new JSONArray().put(new JSONObject().put("text", userText)))
                ));
                body.put("generationConfig", new JSONObject()
                        .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));

                URL url = new URL(GEMINI_STREAM_URL + apiKey.trim());
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
                    String errBody = readStream(conn.getErrorStream());
                    postError(callback, "HTTP " + code + (errBody.isEmpty() ? "" : ": " + errBody));
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
                        JSONArray candidates = data.optJSONArray("candidates");
                        if (candidates == null || candidates.length() == 0) continue;
                        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
                        if (content == null) continue;
                        JSONArray parts = content.optJSONArray("parts");
                        if (parts == null) continue;
                        for (int i = 0; i < parts.length(); i++) {
                            String chunk = parts.optJSONObject(i).optString("text", "");
                            if (!chunk.isEmpty()) {
                                fullResponse.append(chunk);
                                mainHandler.post(() -> callback.onToken(chunk));
                            }
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
