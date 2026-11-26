package rkr.simplekeyboard.inputmethod.ai;

import android.content.Context;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AiStealthController {
    private static final String SECRET_TRIGGER = "..ai";

    private final InputMethodService ime;
    private final GeminiImeClient geminiClient;
    private final LinearLayout rootContainer;
    private final LinearLayout drawer;
    private final TextView promptPreview;
    private final TextView outputView;
    private final Button insertBtn;

    private final StringBuilder typedTriggerBuffer = new StringBuilder();
    private final StringBuilder aiPromptBuffer = new StringBuilder();
    private final StringBuilder aiOutputBuffer = new StringBuilder();
    private boolean isUnlocked = false;
    private long lastShiftTapMs = 0L;

    public AiStealthController(InputMethodService ime, View keyboardView, String backendUrl) {
        this.ime = ime;
        this.geminiClient = new GeminiImeClient(backendUrl);

        Context ctx = ime;
        this.rootContainer = new LinearLayout(ctx);
        this.rootContainer.setOrientation(LinearLayout.VERTICAL);
        this.rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        this.drawer = new LinearLayout(ctx);
        this.drawer.setOrientation(LinearLayout.VERTICAL);
        this.drawer.setBackgroundColor(Color.parseColor("#111827"));
        int pad = dp(ctx, 10);
        this.drawer.setPadding(pad, pad, pad, pad);
        this.drawer.setVisibility(View.GONE);

        // Top prompt + Send / Hide row
        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        this.promptPreview = new TextView(ctx);
        this.promptPreview.setHint("Type prompt on keyboard...");
        this.promptPreview.setTextColor(Color.parseColor("#F8FAFC"));
        this.promptPreview.setHintTextColor(Color.parseColor("#94A3B8"));
        this.promptPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        this.promptPreview.setLayoutParams(previewParams);

        Button askBtn = makeSmallButton(ctx, "Send", "#2563EB");
        askBtn.setOnClickListener(v -> runAction("ask"));

        Button hideBtn = makeSmallButton(ctx, "Hide", "#334155");
        hideBtn.setOnClickListener(v -> hideStealth(true));

        topRow.addView(this.promptPreview);
        topRow.addView(askBtn);
        topRow.addView(hideBtn);
        this.drawer.addView(topRow);

        // Quick action strip
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actionsRow = new LinearLayout(ctx);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));

        addActionButton(ctx, actionsRow, "Fix Grammar", "grammar");
        addActionButton(ctx, actionsRow, "Formal", "formal");
        addActionButton(ctx, actionsRow, "Casual", "casual");
        addActionButton(ctx, actionsRow, "Translate EN", "translate_en");
        addActionButton(ctx, actionsRow, "Smart Reply", "reply");
        scroll.addView(actionsRow);
        this.drawer.addView(scroll);

        // Output box
        this.outputView = new TextView(ctx);
        this.outputView.setTextColor(Color.parseColor("#E2E8F0"));
        this.outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        this.outputView.setMaxLines(5);
        this.outputView.setMovementMethod(new ScrollingMovementMethod());
        this.outputView.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
        this.outputView.setVisibility(View.GONE);
        this.drawer.addView(this.outputView);

        // Insert button
        this.insertBtn = makeSmallButton(ctx, "Insert to App", "#2563EB");
        this.insertBtn.setVisibility(View.GONE);
        this.insertBtn.setOnClickListener(v -> commitAiOutputToHostApp());
        this.drawer.addView(this.insertBtn);

        this.rootContainer.addView(this.drawer);
        this.rootContainer.addView(keyboardView);
    }

    public View getRootView() {
        return rootContainer;
    }

    public boolean isUnlocked() {
        return isUnlocked;
    }

    public void unlock() {
        isUnlocked = true;
        drawer.setVisibility(View.VISIBLE);
    }

    public void hideStealth(boolean wipeBuffers) {
        isUnlocked = false;
        drawer.setVisibility(View.GONE);
        if (wipeBuffers) {
            aiPromptBuffer.setLength(0);
            aiOutputBuffer.setLength(0);
            promptPreview.setText("");
            outputView.setText("");
            outputView.setVisibility(View.GONE);
            insertBtn.setVisibility(View.GONE);
        }
    }

    public boolean handleCodeInput(int codePoint) {
        if (codePoint == -1) { // CODE_SHIFT double-tap to hide
            long now = System.currentTimeMillis();
            if (isUnlocked && now - lastShiftTapMs < 350) {
                hideStealth(true);
                lastShiftTapMs = 0L;
                return true;
            }
            lastShiftTapMs = now;
            return false;
        }

        if (isUnlocked) {
            if (codePoint == -5) { // CODE_DELETE
                if (aiPromptBuffer.length() > 0) {
                    aiPromptBuffer.deleteCharAt(aiPromptBuffer.length() - 1);
                }
                promptPreview.setText(aiPromptBuffer.toString());
                return true;
            } else if (codePoint == 10) { // CODE_ENTER
                runAction("ask");
                return true;
            } else if (codePoint >= 32) {
                aiPromptBuffer.append((char) codePoint);
                promptPreview.setText(aiPromptBuffer.toString());
                return true;
            }
            return false;
        }

        if (codePoint >= 32) {
            typedTriggerBuffer.append((char) codePoint);
            if (typedTriggerBuffer.length() > 12) {
                typedTriggerBuffer.delete(0, typedTriggerBuffer.length() - 12);
            }
            if (typedTriggerBuffer.toString().endsWith(SECRET_TRIGGER)) {
                InputConnection ic = ime.getCurrentInputConnection();
                if (ic != null) {
                    ic.deleteSurroundingText(SECRET_TRIGGER.length() - 1, 0);
                }
                typedTriggerBuffer.setLength(0);
                unlock();
                return true;
            }
        }
        return false;
    }

    private void runAction(String action) {
        String editorText = readHostEditorText();
        aiOutputBuffer.setLength(0);
        outputView.setVisibility(View.VISIBLE);
        outputView.setText("Thinking...");
        insertBtn.setVisibility(View.GONE);

        geminiClient.streamAction(aiPromptBuffer.toString(), editorText, action, new GeminiImeClient.StreamCallback() {
            @Override
            public void onToken(String chunk) {
                if ("Thinking...".equals(outputView.getText().toString())) {
                    outputView.setText("");
                }
                aiOutputBuffer.append(chunk);
                outputView.setText(aiOutputBuffer.toString());
            }

            @Override
            public void onComplete(String fullText) {
                insertBtn.setVisibility(fullText.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onError(String message) {
                outputView.setText("Error: " + message);
            }
        });
    }

    private String readHostEditorText() {
        InputConnection ic = ime.getCurrentInputConnection();
        if (ic == null) return "";
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return et != null && et.text != null ? et.text.toString() : "";
    }

    private void commitAiOutputToHostApp() {
        InputConnection ic = ime.getCurrentInputConnection();
        if (ic != null && aiOutputBuffer.length() > 0) {
            ic.commitText(aiOutputBuffer.toString(), 1);
            hideStealth(true);
        }
    }

    private void addActionButton(Context ctx, LinearLayout parent, String label, String action) {
        Button btn = makeSmallButton(ctx, label, "#1E293B");
        btn.setOnClickListener(v -> runAction(action));
        parent.addView(btn);
    }

    private Button makeSmallButton(Context ctx, String label, String hexBg) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hexBg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34)
        );
        lp.setMarginStart(dp(ctx, 6));
        b.setLayoutParams(lp);
        int hp = dp(ctx, 10);
        b.setPadding(hp, 0, hp, 0);
        return b;
    }

    private int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()
        );
    }
                                            }
