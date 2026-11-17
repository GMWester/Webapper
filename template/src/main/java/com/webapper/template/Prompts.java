package com.webapper.template;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.List;

/** Native dialogs for alert()/confirm()/prompt(), &lt;select&gt;, &lt;input type=file|date|color&gt;. */
final class Prompts implements PromptDelegate {
    static final int REQUEST_FILE = 4711;

    private final Activity activity;
    private FilePrompt pendingFile;
    private GeckoResult<PromptResponse> pendingFileResult;

    Prompts(Activity activity) {
        this.activity = activity;
    }

    private AlertDialog.Builder dialog(String title, String message) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        if (title != null && !title.isEmpty()) b.setTitle(title);
        if (message != null && !message.isEmpty()) b.setMessage(message);
        return b;
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt) {
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        dialog(prompt.title, prompt.message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {})
                .setOnDismissListener(d -> res.complete(prompt.dismiss()))
                .show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt) {
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        dialog(prompt.title, prompt.message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE));
                })
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.dismiss());
                })
                .show();
        return res;
    }

    private GeckoResult<PromptResponse> textDialog(String title, String message, String value, int inputType,
                                                   java.util.function.Function<String, PromptResponse> ok,
                                                   java.util.function.Supplier<PromptResponse> cancel) {
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        final EditText edit = new EditText(activity);
        edit.setInputType(inputType);
        if (value != null) edit.setText(value);
        final boolean[] done = {false};
        dialog(title, message)
                .setView(edit)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    done[0] = true;
                    res.complete(ok.apply(edit.getText().toString()));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {})
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(cancel.get());
                })
                .show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session, TextPrompt prompt) {
        return textDialog(prompt.title, prompt.message, prompt.defaultValue, InputType.TYPE_CLASS_TEXT,
                prompt::confirm, prompt::dismiss);
    }

    @Override
    public GeckoResult<PromptResponse> onDateTimePrompt(GeckoSession session, DateTimePrompt prompt) {
        return textDialog(prompt.title, "Format: " + hint(prompt.type), prompt.defaultValue,
                InputType.TYPE_CLASS_DATETIME, prompt::confirm, prompt::dismiss);
    }

    private static String hint(int type) {
        switch (type) {
            case DateTimePrompt.Type.MONTH: return "YYYY-MM";
            case DateTimePrompt.Type.WEEK: return "YYYY-Www";
            case DateTimePrompt.Type.TIME: return "HH:MM";
            case DateTimePrompt.Type.DATETIME_LOCAL: return "YYYY-MM-DDTHH:MM";
            default: return "YYYY-MM-DD";
        }
    }

    @Override
    public GeckoResult<PromptResponse> onColorPrompt(GeckoSession session, ColorPrompt prompt) {
        return textDialog(prompt.title, "Colour (#RRGGBB)", prompt.defaultValue, InputType.TYPE_CLASS_TEXT,
                prompt::confirm, prompt::dismiss);
    }

    private static void flatten(ChoicePrompt.Choice[] in, List<ChoicePrompt.Choice> out) {
        if (in == null) return;
        for (ChoicePrompt.Choice c : in) {
            if (c.items != null) {
                flatten(c.items, out);
            } else if (!c.separator) {
                out.add(c);
            }
        }
    }

    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        final List<ChoicePrompt.Choice> list = new ArrayList<>();
        flatten(prompt.choices, list);
        final CharSequence[] labels = new CharSequence[list.size()];
        final boolean[] checked = new boolean[list.size()];
        int sel = -1;
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).label;
            checked[i] = list.get(i).selected;
            if (checked[i] && sel < 0) sel = i;
        }
        final boolean[] done = {false};
        AlertDialog.Builder b = dialog(prompt.title, null);
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            b.setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        List<String> ids = new ArrayList<>();
                        for (int i = 0; i < list.size(); i++) if (checked[i]) ids.add(list.get(i).id);
                        done[0] = true;
                        res.complete(prompt.confirm(ids.toArray(new String[0])));
                    });
        } else {
            b.setSingleChoiceItems(labels, sel, (d, which) -> {
                ChoicePrompt.Choice c = list.get(which);
                if (c.disabled) return;
                done[0] = true;
                res.complete(prompt.confirm(c.id));
                d.dismiss();
            });
        }
        b.setOnDismissListener(d -> {
            if (!done[0]) res.complete(prompt.dismiss());
        }).show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimes = prompt.mimeTypes;
        if (mimes != null && mimes.length == 1) {
            intent.setType(mimes[0]);
        } else {
            intent.setType("*/*");
            if (mimes != null && mimes.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        }
        if (prompt.type == FilePrompt.Type.MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pendingFile = prompt;
        pendingFileResult = new GeckoResult<>();
        try {
            activity.startActivityForResult(Intent.createChooser(intent, prompt.title), REQUEST_FILE);
        } catch (Exception e) {
            pendingFile = null;
            return GeckoResult.fromValue(prompt.dismiss());
        }
        return pendingFileResult;
    }

    /** Forwarded from Activity.onActivityResult. */
    void onFileResult(int resultCode, Intent data) {
        if (pendingFile == null) return;
        FilePrompt prompt = pendingFile;
        GeckoResult<PromptResponse> res = pendingFileResult;
        pendingFile = null;
        pendingFileResult = null;
        if (resultCode != Activity.RESULT_OK || data == null) {
            res.complete(prompt.dismiss());
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            res.complete(prompt.dismiss());
        } else if (prompt.type == FilePrompt.Type.MULTIPLE) {
            res.complete(prompt.confirm(activity, uris.toArray(new Uri[0])));
        } else {
            res.complete(prompt.confirm(activity, uris.get(0)));
        }
    }
}
