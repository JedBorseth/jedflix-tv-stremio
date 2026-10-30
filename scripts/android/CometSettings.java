package com.jedflix.tv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.compose.runtime.RememberObserver;
import android.view.View;
import android.view.WindowManager;
import androidx.compose.foundation.lazy.LazyItemScope;
import androidx.compose.foundation.lazy.LazyListScope;
import androidx.compose.runtime.Composer;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.runtime.internal.ComposableLambdaKt;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt;
import com.stremio.tv.composables.MenuItem;
import com.stremio.tv.views.settings.composables.SectionKt;
import com.stremio.tv.views.settings.composables.SectionItemKt;
import com.stremio.tv.composables.JedflixCounterKt;
import com.stremio.tv.composables.ModalKt;
import com.stremio.tv.views.settings.composables.rows.JedflixCounterRowKt;
import com.stremio.tv.views.settings.composables.rows.ModalRowKt;
import com.stremio.tv.views.settings.composables.rows.JedflixKeyInputRowKt;
import com.stremio.tv.views.settings.composables.SectionListKt;
import com.stremio.tv.views.settings.composables.rows.ButtonRowKt;
import com.stremio.tv.views.settings.composables.rows.MenuRowKt;
import com.stremio.tv.views.settings.composables.rows.SwitchRowKt;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import org.json.JSONObject;

/** Uses the same Compose list, sections, rows, and focus system as stock settings. */
public final class CometSettings {
    private CometSettings() {}

    public static void Content(Composer composer) {
        composer.startReplaceGroup(0x4a524446);
        Object remembered = composer.rememberedValue();
        if (remembered == Composer.Companion.getEmpty()) {
            Context context = composer.consume(AndroidCompositionLocals_androidKt.getLocalContext());
            remembered = new Form(context);
            composer.updateRememberedValue(remembered);
        }
        Form form = (Form) remembered;
        SectionListKt.SectionList(form.sections, composer, 0);
        if (form.qrOpen.getValue()) ModalKt.Modal("Scan QR code", form.closeQr, null, form.qrContent, composer, 0, 0);
        composer.endReplaceGroup();
    }

    @SuppressWarnings("unchecked")
    private static Function2<Composer, Integer, Unit> content(int key, Function2<Composer, Integer, Unit> block) {
        return (Function2<Composer, Integer, Unit>) ComposableLambdaKt.composableLambdaInstance(key, true, block);
    }

    private static final class Form implements RememberObserver {
        final Context context;
        final MutableState<Integer> revision = SnapshotStateKt.mutableStateOf(0, SnapshotStateKt.<Integer>structuralEqualityPolicy());
        String key, quality;
        int results;
        double size;
        boolean cached, trash, deduplicate;
        final boolean hadSavedKey;
        final String savedKey;
        final JSONObject savedAccount;
        final Function0<Unit> save = action(3), clear = action(4), pair = action(5);
        final Handler main = new Handler(Looper.getMainLooper());
        RdAccountClient validation;
        int validationVersion;
        boolean disposed;
        String status = "";
        final MutableState<Boolean> qrOpen = SnapshotStateKt.mutableStateOf(false, SnapshotStateKt.<Boolean>structuralEqualityPolicy());
        final Function0<Unit> closeQr = new Function0<Unit>() {
            @Override public Unit invoke() { qrOpen.setValue(false); return Unit.INSTANCE; }
        };
        final Function1<String, Unit> changeKey = new Function1<String, Unit>() {
            @Override public Unit invoke(String value) {
                try { keyChanged(RdKeyPairing.validateKey(value)); }
                catch (java.io.IOException error) { status = "Enter a valid Real-Debrid API key."; changed(); }
                return Unit.INSTANCE;
            }
        };
        final Function1<Integer, Unit> changeResults = new Function1<Integer, Unit>() {
            @Override public Unit invoke(Integer value) { results = Math.max(0, Math.min(100, value)); changed(); return Unit.INSTANCE; }
        };
        final Function1<Double, Unit> changeSize = new Function1<Double, Unit>() {
            @Override public Unit invoke(Double value) { size = Math.max(0, Math.min(1000, value)); changed(); return Unit.INSTANCE; }
        };
        final Function1<String, Unit> selectQuality = new Function1<String, Unit>() {
            @Override public Unit invoke(String value) { quality = value; changed(); return Unit.INSTANCE; }
        };
        final Function1<Boolean, Unit> changeCached = toggle(0), changeTrash = toggle(1), changeDeduplicate = toggle(2);
        final Function1<LazyListScope, Unit> sections;

        Form(Context context) {
            this.context = context;
            CometConfig.init(context);
            JSONObject saved = CometConfig.settings();
            key = saved.optString("apiKey"); hadSavedKey = !key.isEmpty();
            savedKey = key; savedAccount = saved.optJSONObject("account");
            results = saved.optInt("maxResults", 0); size = saved.optDouble("maxSizeGb", 0);
            quality = saved.optString("quality", "all");
            cached = saved.optBoolean("cachedOnly", true); trash = saved.optBoolean("removeTrash", true);
            deduplicate = saved.optBoolean("deduplicate", true);
            final Function2<Composer, Integer, Unit> account = content(0x4a524410, new Function2<Composer, Integer, Unit>() {
                @Override public Unit invoke(Composer c, Integer flags) {
                    revision.getValue();
                    ButtonRowKt.ButtonRow("Set up with your phone", "Scan QR code", null, pair, c, 0, 4);
                    ModalRowKt.ModalRow("Real-Debrid account", accountSummary(), keyEditor, c, 0, 0);
                    if (!status.isEmpty()) SectionItemKt.SectionItem(status, null, emptyContent, c, 0, 2);
                    return Unit.INSTANCE;
                }
            });
            final Function2<Composer, Integer, Unit> streams = content(0x4a524411, new Function2<Composer, Integer, Unit>() {
                @Override public Unit invoke(Composer c, Integer flags) {
                    revision.getValue();
                    JedflixCounterRowKt.CounterRow("Results per resolution", results, 0, 100,
                        results == 0 ? "Unlimited" : "%.0f", changeResults, c, 0);
                    SectionItemKt.SectionItem("Maximum file size", null, sizeCounter, c, 0, 2);
                    List<MenuItem<String>> choices = new ArrayList<>();
                    choices.add(new MenuItem<String>("all", "All", "all", null, null, quality.equals("all")));
                    choices.add(new MenuItem<String>("1080p", "1080p", "1080p", null, null, quality.equals("1080p")));
                    choices.add(new MenuItem<String>("720p", "720p", "720p", null, null, quality.equals("720p")));
                    MenuRowKt.MenuRow("Maximum resolution", choices, selectQuality, c, 0);
                    SwitchRowKt.SwitchRow("Show cached streams only", cached, changeCached, c, 0);
                    SwitchRowKt.SwitchRow("Filter low-quality releases", trash, changeTrash, c, 0);
                    SwitchRowKt.SwitchRow("Hide duplicate streams", deduplicate, changeDeduplicate, c, 0);
                    return Unit.INSTANCE;
                }
            });
            final Function2<Composer, Integer, Unit> changes = content(0x4a524412, new Function2<Composer, Integer, Unit>() {
                @Override public Unit invoke(Composer c, Integer flags) {
                    ButtonRowKt.ButtonRow("Apply configuration", "Save and restart", null, save, c, 0, 4);
                    ModalRowKt.ModalRow("Remove account", "Remove key", removeConfirmation, c, 0, 0);
                    return Unit.INSTANCE;
                }
            });
            final Function3<LazyItemScope, Composer, Integer, Unit> accountItem = section(0x4a524420, "Account", account);
            final Function3<LazyItemScope, Composer, Integer, Unit> streamsItem = section(0x4a524421, "Streams", streams);
            final Function3<LazyItemScope, Composer, Integer, Unit> changesItem = section(0x4a524422, "Changes", changes);
            sections = new Function1<LazyListScope, Unit>() {
                @Override public Unit invoke(LazyListScope scope) {
                    scope.item("comet-account", "section", accountItem);
                    scope.item("comet-streams", "section", streamsItem);
                    scope.item("comet-changes", "section", changesItem);
                    return Unit.INSTANCE;
                }
            };
        }

        @SuppressWarnings("unchecked")
        private Function3<LazyItemScope, Composer, Integer, Unit> section(int key, final String title,
                final Function2<Composer, Integer, Unit> body) {
            Function3<LazyItemScope, Composer, Integer, Unit> block = new Function3<LazyItemScope, Composer, Integer, Unit>() {
                @Override public Unit invoke(LazyItemScope scope, Composer c, Integer flags) {
                    SectionKt.Section(title, body, c, 0, 0); return Unit.INSTANCE;
                }
            };
            return (Function3<LazyItemScope, Composer, Integer, Unit>) ComposableLambdaKt.composableLambdaInstance(key, true, block);
        }

        void changed() { revision.setValue(revision.getValue() + 1); }
        private boolean hasVerifiedAccount() {
            return !key.isEmpty() && key.equals(savedKey) && savedAccount != null
                && !savedAccount.optString("username").trim().isEmpty() && savedAccount.optLong("validatedAt", 0) > 0;
        }
        private String accountSummary() {
            if (key.isEmpty()) return "Not configured";
            if (hasVerifiedAccount()) return "Authenticated as " + savedAccount.optString("username");
            return "Save to verify account";
        }
        Function1<Boolean, Unit> toggle(final int option) {
            return new Function1<Boolean, Unit>() { @Override public Unit invoke(Boolean value) {
                if (option == 0) cached = value; else if (option == 1) trash = value; else deduplicate = value;
                changed(); return Unit.INSTANCE;
            }};
        }
        Function0<Unit> action(final int action) {
            return new Function0<Unit>() { @Override public Unit invoke() {
                if (action == 3) confirmSave();
                else if (action == 5) pair();
                else confirmClear();
                return Unit.INSTANCE;
            }};
        }

        private void pair() { qrOpen.setValue(true); }

        private void keyChanged(String value) {
            cancelValidation(); key = value; status = ""; changed();
        }

        private void cancelValidation() {
            validationVersion++;
            if (validation != null) validation.cancel();
            validation = null;
        }
        @Override public void onRemembered() { disposed = false; }
        @Override public void onForgotten() { disposed = true; cancelValidation(); main.removeCallbacksAndMessages(null); }
        @Override public void onAbandoned() { onForgotten(); }

        private void confirmSave() {
            if (validation != null) return;
            if (key.isEmpty()) { message("Real-Debrid API key", "Enter your API key before saving."); return; }
            if (hasVerifiedAccount()) { saveAndRestart(key, savedAccount); return; }
            final String candidate = key;
            final int version = ++validationVersion;
            final RdAccountClient client = new RdAccountClient();
            validation = client; status = "Verifying Real-Debrid account…"; changed();
            new Thread(new Runnable() { @Override public void run() {
                try {
                    final JSONObject account = client.validate(candidate);
                    main.post(new Runnable() { @Override public void run() {
                        if (disposed || version != validationVersion) return;
                        validation = null; saveAndRestart(candidate, account);
                    }});
                } catch (final java.io.IOException error) {
                    main.post(new Runnable() { @Override public void run() {
                        if (disposed || version != validationVersion) return;
                        validation = null;
                        String text = error.getMessage();
                        status = text != null && (text.startsWith("This Real-Debrid") || text.startsWith("Real-Debrid denied"))
                            ? text : "Could not verify your account. Check your connection and try again.";
                        changed();
                    }});
                }
            }}, "Jedflix-RD-account").start();
        }

        private void saveAndRestart(String candidate, JSONObject account) {
            try {
                CometConfig.saveVerified(candidate, results, size, quality, cached, trash, deduplicate, account);
                restart(context);
            } catch (Exception error) { message("Could not save settings", "Please check your settings and try again."); }
        }

        private void confirmClear() {
            cancelValidation();
            if (!hadSavedKey) { key = ""; status = ""; changed(); return; }
            try { CometConfig.clear(); restart(context); }
            catch (Exception error) { message("Could not remove settings", "Please try again."); }
        }
        private void message(String title, String text) { status = text; changed(); }

        final Function2<Composer, Integer, Unit> emptyContent = content(0x4a524431, new Function2<Composer, Integer, Unit>() {
            @Override public Unit invoke(Composer c, Integer flags) { return Unit.INSTANCE; }
        });
        final Function2<Composer, Integer, Unit> keyEditor = content(0x4a524432, new Function2<Composer, Integer, Unit>() {
            @Override public Unit invoke(Composer c, Integer flags) {
                revision.getValue();
                c.startReplaceGroup(0x4a524435);
                Object editor = c.rememberedValue();
                if (editor == Composer.Companion.getEmpty()) {
                    View view = c.consume(AndroidCompositionLocals_androidKt.getLocalView());
                    editor = new SecureEditor(context, view); c.updateRememberedValue(editor);
                }
                c.endReplaceGroup();
                JedflixKeyInputRowKt.TextInputRow("API key", key, changeKey, c, 0);
                return Unit.INSTANCE;
            }
        });
        final Function2<Composer, Integer, Unit> qrContent = content(0x4a524436, new Function2<Composer, Integer, Unit>() {
            @Override public Unit invoke(Composer c, Integer flags) {
                c.startReplaceGroup(0x4a524437);
                Object secured = c.rememberedValue();
                if (secured == Composer.Companion.getEmpty()) {
                    View view = c.consume(AndroidCompositionLocals_androidKt.getLocalView());
                    secured = new SecureEditor(context, view); c.updateRememberedValue(secured);
                }
                c.endReplaceGroup();
                c.startReplaceGroup(0x4a524438);
                Object session = c.rememberedValue();
                if (session == Composer.Companion.getEmpty()) {
                    session = new RdKeyPairingDialog(context, new RdKeyPairingDialog.Receiver() {
                        @Override public void received(String value) {
                            keyChanged(value); status = "Key received. Choose Save and restart to apply it."; changed();
                        }
                    }, closeQr);
                    c.updateRememberedValue(session);
                }
                ((RdKeyPairingDialog)session).render(c);
                c.endReplaceGroup();
                return Unit.INSTANCE;
            }
        });
        final Function2<Composer, Integer, Unit> sizeCounter = content(0x4a524433, new Function2<Composer, Integer, Unit>() {
            @Override public Unit invoke(Composer c, Integer flags) {
                revision.getValue();
                JedflixCounterKt.Counter(size, 0, 1000, size == 0 ? "Unlimited" : "%.1f GB", changeSize, c, 0);
                return Unit.INSTANCE;
            }
        });
        final Function2<Composer, Integer, Unit> removeConfirmation = content(0x4a524434, new Function2<Composer, Integer, Unit>() {
            @Override public Unit invoke(Composer c, Integer flags) {
                ButtonRowKt.ButtonRow("Remove saved key and Comet options?", "Remove and restart", null, clear, c, 0, 4);
                return Unit.INSTANCE;
            }
        });
    }

    private static final class SecureEditor implements RememberObserver {
        private final android.view.Window window;
        private boolean alreadySecure;
        SecureEditor(Context context, View view) {
            while (context instanceof android.content.ContextWrapper && !(context instanceof android.app.Activity))
                context = ((android.content.ContextWrapper)context).getBaseContext();
            window = view.getParent() instanceof androidx.compose.ui.window.DialogWindowProvider
                ? ((androidx.compose.ui.window.DialogWindowProvider)view.getParent()).getWindow()
                : context instanceof android.app.Activity ? ((android.app.Activity)context).getWindow() : null;
        }
        @Override public void onRemembered() {
            if (window == null) return;
            alreadySecure = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        @Override public void onForgotten() { if (window != null && !alreadySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); }
        @Override public void onAbandoned() {}
    }

    private static void restart(Context context) {
        Intent handoff = new Intent(context, CometRestartActivity.class);
        handoff.putExtra("oldPid", android.os.Process.myPid());
        handoff.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(handoff);
    }

    /** Called on each details state emission; a spinner/loading label is not an empty result. */
    public static void updateEmptyState(Object fragment, Object state) {
        try {
            Object parent = fragment.getClass().getMethod("getParentFragment").invoke(fragment);
            if (parent == null) return;
            View root = (View) parent.getClass().getMethod("getView").invoke(parent);
            if (root == null) return;
            View button = root.findViewWithTag("jedflix_real_debrid");
            if (button == null) return;
            Context context = root.getContext();
            boolean show = isEmptyResult(context, state);
            boolean appeared = show && button.getVisibility() != View.VISIBLE;
            button.setVisibility(show ? View.VISIBLE : View.GONE);
            button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSettings(fragment); }});
            if (appeared) button.requestFocus();
        } catch (Exception error) {
            android.util.Log.e("JedflixComet", "Cannot update stream settings shortcut");
        }
    }

    public static boolean isEmptyResult(Context context, Object state) throws Exception {
        int noStreamsId = context.getResources().getIdentifier("label_no_stream", "string", context.getPackageName());
        String message = (String) state.getClass().getMethod("getStreamsLabel").invoke(state);
        List<?> streams = (List<?>) state.getClass().getMethod("getStreams").invoke(state);
        boolean visible = (Boolean) state.getClass().getMethod("getStreamListVisible").invoke(state);
        return visible && streams.isEmpty() && noStreamsId != 0 && context.getString(noStreamsId).equals(message);
    }

    private static void openSettings(Object fragment) {
        try {
            Class<?> fragmentType = Class.forName("androidx.fragment.app.Fragment");
            Object nav = Class.forName("androidx.navigation.fragment.NavHostFragment")
                .getMethod("findNavController", fragmentType).invoke(null, fragment);
            Context context = (Context) fragment.getClass().getMethod("requireContext").invoke(fragment);
            int destination = context.getResources().getIdentifier("settings", "id", context.getPackageName());
            Bundle args = new Bundle(); args.putString("tab", "realdebrid");
            nav.getClass().getMethod("navigate", int.class, Bundle.class).invoke(nav, destination, args);
        } catch (Exception error) {
            android.util.Log.e("JedflixComet", "Cannot open Real-Debrid settings");
        }
    }
}
