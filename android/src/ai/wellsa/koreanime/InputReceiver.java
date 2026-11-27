package ai.wellsa.koreanime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.EditorInfo;

/**
 * Entry point for adb shell only. First boundary: manifest exported=true plus android:permission=INJECT_EVENTS (signature-level;
 * on API 34+ an exported=false receiver is not reached even by `am broadcast` from shell, so a permission-protected exported receiver is used).
 * Second boundary: getSentFromUid(): if known only shell (2000) is allowed, INVALID_UID (`am broadcast`) trusts the first boundary,
 * own-app and other-app UIDs are rejected. Commit happens only if this IME is active, an input session exists, pkg and fieldId match
 * exactly, and the field is not a password field.
 * Usage: adb shell am broadcast -n ai.wellsa.koreanime/.InputReceiver -a ai.wellsa.koreanime.COMMIT --es pkg <editor package> --es field <EditorInfo.fieldId> --es b64 <base64(UTF-8)>
 * field accepts -1, 4294967295 and 0xffffffff as the same 32-bit value (0.3.1; web fields have fieldId=-1).
 * Result is reported only via setResultCode: 0 rejected (sender), 1 target mismatch, 2 IME inactive or no session/input connection,
 * 3 invalid text, 4 password field, 10 success. No text or reason strings are printed.
 */
public class InputReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !"ai.wellsa.koreanime.COMMIT".equals(intent.getAction())) { setResultCode(0); return; }
        // First boundary: manifest permission (INJECT_EVENTS, signature-level); the framework delivers only from holders (shell/system).
        // Second: if the sender UID is known only shell is allowed; INVALID_UID (am broadcast carries no identity) trusts the first boundary.
        if (!Gate.senderAllowed(getSentFromUid())) { setResultCode(0); return; }
        TextIme ime = TextIme.current();
        if (ime == null || !ime.isActiveIme()) { setResultCode(2); return; }
        EditorInfo ei = ime.editor();
        if (ei == null) { setResultCode(2); return; }
        if (!Gate.targetAllowed(intent.getStringExtra("pkg"), intent.getStringExtra("field"), ei.packageName, ei.fieldId)) { setResultCode(1); return; }
        if (Gate.isPasswordField(ei.inputType)) { setResultCode(4); return; }
        String del = intent.getStringExtra("del");
        if (del != null) {
            int n; try { n = Integer.parseInt(del); } catch (NumberFormatException e) { setResultCode(3); return; }
            setResultCode(ime.sendDelete(n) ? 10 : 2); return;
        }
        String text = Gate.decode(intent.getStringExtra("b64"));
        if (text == null) { setResultCode(3); return; }
        setResultCode(ime.commit(text) ? 10 : 2);
    }
}
