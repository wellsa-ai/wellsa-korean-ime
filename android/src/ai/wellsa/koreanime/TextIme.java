package ai.wellsa.koreanime;

import android.inputmethodservice.InputMethodService;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Minimal IME without a keyboard view. No UI; text is committed only via InputReceiver. */
public class TextIme extends InputMethodService {
    public static final String IME_ID = "ai.wellsa.koreanime/.TextIme";
    private static volatile TextIme current;
    private volatile boolean sessionStarted;

    @Override public void onCreate() { super.onCreate(); current = this; }
    @Override public void onDestroy() { if (current == this) current = null; super.onDestroy(); }
    @Override public boolean onEvaluateInputViewShown() { return false; }   // never show a keyboard
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) { super.onStartInput(attribute, restarting); sessionStarted = true; }
    @Override public void onFinishInput() { sessionStarted = false; super.onFinishInput(); }

    static TextIme current() { return current; }

    /** Whether this IME is the active one according to system settings. */
    boolean isActiveIme() {
        return Gate.imeActive(Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD), IME_ID);
    }

    /** Editor info of the current input session, or null without a session. */
    EditorInfo editor() {
        if (!sessionStarted) return null;
        return getCurrentInputEditorInfo();
    }

    /** Commit only when an active InputConnection exists. Returns success only; never logs the text. */
    boolean commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        return ic != null && ic.commitText(text, 1);
    }

    boolean sendDelete(int n) {
        InputConnection ic = getCurrentInputConnection();
        return ic != null && n > 0 && n <= 200 && ic.deleteSurroundingText(n, 0);
    }
}
