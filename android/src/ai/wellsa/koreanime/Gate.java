package ai.wellsa.koreanime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Pure decision logic (no Android dependency), unit-testable on a plain JVM. Text is never logged. */
public final class Gate {
    public static final int SHELL_UID = 2000;
    public static final int INVALID_UID = -1;          // android.os.Process.INVALID_UID
    public static final int MAX_CHARS = 2000;
    // android.text.InputType constants (copied to avoid a framework dependency)
    public static final int TYPE_MASK_CLASS = 0x0000000f, TYPE_MASK_VARIATION = 0x00000ff0;
    public static final int TYPE_CLASS_TEXT = 1, TYPE_CLASS_NUMBER = 2;
    public static final int TYPE_TEXT_VARIATION_PASSWORD = 0x80, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90, TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0;
    public static final int TYPE_NUMBER_VARIATION_PASSWORD = 0x10;

    private Gate() {}

    /** Sender UID (getSentFromUid, API 34+): if known, only adb shell (2000) is allowed. INVALID_UID passes because the
     *  manifest permission boundary (INJECT_EVENTS) already restricts senders; own-app and other-app UIDs are rejected. */
    public static boolean senderAllowed(int sentFromUid) {
        return sentFromUid == SHELL_UID || sentFromUid == INVALID_UID;
    }

    /** Target binding: the requested pkg and fieldId must equal the current editor (EditorInfo.packageName / fieldId). Missing = reject.
     *  Since 0.3.1 fieldId is compared as a 32-bit value. Fields with View.NO_ID (-1), such as web views in browsers, are printed by
     *  dumpsys as `fieldId=0xffffffff`; a caller sending 4294967295 used to fail Integer.parseInt and always mismatch. */
    public static boolean targetAllowed(String requestedPkg, String requestedField, String editorPkg, int editorFieldId) {
        if (requestedPkg == null || requestedPkg.isEmpty() || editorPkg == null || editorPkg.isEmpty()) return false;
        if (!requestedPkg.equals(editorPkg)) return false;
        Integer f = parseFieldId(requestedField);
        return f != null && f.intValue() == editorFieldId;
    }

    /** Parse a fieldId: signed decimal (-1), unsigned 32-bit decimal (4294967295) or 0x hex (up to 8 digits).
     *  Out of 32-bit range or any other format returns null (reject). Only equal 32-bit values count as the same field. */
    public static Integer parseFieldId(String v) {
        if (v == null || v.isEmpty() || v.length() > 11) return null;
        try {
            long n;
            if (v.startsWith("0x") || v.startsWith("0X")) {
                if (v.length() < 3 || v.length() > 10) return null;
                n = Long.parseLong(v.substring(2), 16);
            } else {
                for (int i = 0; i < v.length(); i++) { char c = v.charAt(i); if (!(c >= '0' && c <= '9') && !(c == '-' && i == 0)) return null; }
                n = Long.parseLong(v);
            }
            if (n < Integer.MIN_VALUE || n > 0xFFFFFFFFL) return null;
            return Integer.valueOf((int) n);
        } catch (NumberFormatException e) { return null; }
    }

    /** Password fields are refused (three text variations and numeric password). */
    public static boolean isPasswordField(int inputType) {
        int cls = inputType & TYPE_MASK_CLASS, var = inputType & TYPE_MASK_VARIATION;
        if (cls == TYPE_CLASS_TEXT) return var == TYPE_TEXT_VARIATION_PASSWORD || var == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == TYPE_TEXT_VARIATION_WEB_PASSWORD;
        if (cls == TYPE_CLASS_NUMBER) return var == TYPE_NUMBER_VARIATION_PASSWORD;
        return false;
    }

    /** Whether this IME is the active one (compared with Settings.Secure default_input_method). */
    public static boolean imeActive(String defaultImeId, String myImeId) {
        return defaultImeId != null && myImeId != null && !myImeId.isEmpty() && defaultImeId.equals(myImeId);
    }

    /** Decode base64(UTF-8) text. Returns null if empty, too long, invalid base64 or containing control characters. */
    public static String decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            String s = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            if (s.isEmpty() || s.length() > MAX_CHARS) return null;
            for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c < 0x20 && c != '\n' && c != '\t') return null; }
            return s;
        } catch (IllegalArgumentException e) { return null; }
    }
}
