import ai.wellsa.koreanime.Gate;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class GateTest {
    static int fails = 0;
    static void check(boolean ok, String name) { if (!ok) { fails++; System.out.println("FAIL " + name); } else System.out.println("ok   " + name); }
    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] a) {
        check(Gate.senderAllowed(2000), "shell uid allowed");
        check(Gate.senderAllowed(Gate.INVALID_UID), "INVALID_UID passes to manifest-permission boundary");
        check(!Gate.senderAllowed(10234), "own/app uid rejected (no self fallback)");
        check(!Gate.senderAllowed(0) && !Gate.senderAllowed(1000), "root/system rejected");
        check(Gate.targetAllowed("com.example.chat", "2131296789", "com.example.chat", 2131296789), "pkg+field match");
        check(!Gate.targetAllowed("com.example.chat", "2131296789", "com.example.chat", 2131296790), "same app other field rejected");
        check(!Gate.targetAllowed("com.example.chat", null, "com.example.chat", 2131296789), "field missing rejected");
        check(!Gate.targetAllowed("com.example.chat", "x", "com.example.chat", 2131296789), "field non-numeric rejected");
        check(!Gate.targetAllowed("com.example.chat", "1", "com.android.settings", 1), "pkg mismatch rejected");
        check(!Gate.targetAllowed(null, "1", "com.example.chat", 1) && !Gate.targetAllowed("com.example.chat", "1", null, 1), "missing pkg/editor rejected");
        // 0.3.1: web fields have fieldId = View.NO_ID = -1; the dumpsys spellings 0xffffffff / 4294967295 are the same 32-bit value.
        check(Gate.targetAllowed("com.android.chrome", "-1", "com.android.chrome", -1), "web field -1 match");
        check(Gate.targetAllowed("com.android.chrome", "4294967295", "com.android.chrome", -1), "web field unsigned decimal match (0.3.0 regression)");
        check(Gate.targetAllowed("com.android.chrome", "0xffffffff", "com.android.chrome", -1), "web field hex match");
        check(Gate.targetAllowed("com.android.settings", "0x7f0a0785", "com.android.settings", 0x7f0a0785), "hex resource id match");
        check(!Gate.targetAllowed("com.android.chrome", "0", "com.android.chrome", -1) && !Gate.targetAllowed("com.android.chrome", "-1", "com.android.chrome", 0), "0 and -1 are different fields");
        check(!Gate.targetAllowed("com.example.chat", "-1", "com.android.chrome", -1), "web field other pkg rejected");
        check(!Gate.targetAllowed("com.android.chrome", "4294967296", "com.android.chrome", 0) && !Gate.targetAllowed("com.android.chrome", "-2147483649", "com.android.chrome", 2147483647), "out of 32-bit range rejected (no wrap)");
        check(!Gate.targetAllowed("com.android.chrome", "0x1ffffffff", "com.android.chrome", -1) && !Gate.targetAllowed("com.android.chrome", "0x", "com.android.chrome", 0), "bad hex rejected");
        check(!Gate.targetAllowed("com.android.chrome", "+1", "com.android.chrome", 1) && !Gate.targetAllowed("com.android.chrome", " 1", "com.android.chrome", 1) && !Gate.targetAllowed("com.android.chrome", "1-", "com.android.chrome", 1) && !Gate.targetAllowed("com.android.chrome", "", "com.android.chrome", 0), "odd spellings rejected");
        check(Gate.isPasswordField(Gate.TYPE_CLASS_TEXT | Gate.TYPE_TEXT_VARIATION_PASSWORD), "text password rejected");
        check(Gate.isPasswordField(Gate.TYPE_CLASS_TEXT | Gate.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD), "visible password rejected");
        check(Gate.isPasswordField(Gate.TYPE_CLASS_TEXT | Gate.TYPE_TEXT_VARIATION_WEB_PASSWORD), "web password rejected");
        check(Gate.isPasswordField(Gate.TYPE_CLASS_NUMBER | Gate.TYPE_NUMBER_VARIATION_PASSWORD), "number password rejected");
        check(!Gate.isPasswordField(Gate.TYPE_CLASS_TEXT) && !Gate.isPasswordField(Gate.TYPE_CLASS_TEXT | 0x20), "plain/normal text allowed");
        check(Gate.imeActive("ai.wellsa.koreanime/.TextIme", "ai.wellsa.koreanime/.TextIme"), "active ime match");
        check(!Gate.imeActive("com.example.keyboard/.Service", "ai.wellsa.koreanime/.TextIme"), "other ime active rejected");
        check(!Gate.imeActive(null, "ai.wellsa.koreanime/.TextIme"), "unknown default ime rejected");
        check("한글 입력 시험".equals(Gate.decode(b64("한글 입력 시험"))), "korean roundtrip");
        check(Gate.decode(null) == null && Gate.decode("") == null && Gate.decode("***") == null, "empty/bad base64 rejected");
        check(Gate.decode(b64("a\u0007b")) == null, "control char rejected");
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < Gate.MAX_CHARS + 1; i++) sb.append('가');
        check(Gate.decode(b64(sb.toString())) == null, "over max rejected");
        System.out.println(fails == 0 ? "ALL OK" : ("FAILS=" + fails)); System.exit(fails == 0 ? 0 : 1);
    }
}
