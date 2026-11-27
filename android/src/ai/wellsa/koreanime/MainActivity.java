package ai.wellsa.koreanime;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Launcher screen: explains what the app is. It requests no permissions, has no network access and stores nothing.
 * Text is shown in Korean with English underneath (both always, no locale logic).
 */
public class MainActivity extends Activity {
    static final String VERSION = "0.3.2";
    static final String POLICY_URL = "https://wellsa.ai/privacy/korean-ime/";
    static final String SOURCE_URL = "https://github.com/wellsa-ai/wellsa-korean-ime";
    static final String CMD_ENABLE = "adb shell ime enable ai.wellsa.koreanime/.TextIme\n"
            + "adb shell ime set ai.wellsa.koreanime/.TextIme";
    static final String CMD_SEND = "adb shell am broadcast -n ai.wellsa.koreanime/.InputReceiver\n"
            + "  -a ai.wellsa.koreanime.COMMIT\n"
            + "  --es pkg <editor package> --es field <fieldId>\n"
            + "  --es b64 <base64 of UTF-8 text>";

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, float sp, boolean bold, int topDp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView code(String s) {
        TextView t = text(s, 14, false, 6);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setBackgroundColor(0x33808080);
        return t;
    }

    private Button button(String label, final Intent intent) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(intent); } catch (Exception e) { /* no handler (e.g. no browser): ignore */ }
            }
        });
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(32));

        col.addView(text("Wellsa Korean IME", 28, true, 0));
        col.addView(text("버전 " + VERSION + " / version " + VERSION, 14, false, 2));

        col.addView(text("이 앱은 무엇인가요", 20, true, 22));
        col.addView(text("컴퓨터에서 adb 로 보낸 한글 등 유니코드 글자를, 지금 선택된 입력칸에 넣어 주는 개발자·자동화 도구입니다. "
                + "화면 키보드는 없습니다. 일반 키보드를 대신하는 앱이 아닙니다.", 17, false, 6));
        col.addView(text("What it is: a developer / automation tool that types text (Korean and other Unicode) sent from a computer "
                + "via adb into the currently focused text field. It has no on-screen keyboard and is not a replacement for your normal keyboard.",
                15, false, 6));

        col.addView(text("사용 방법 (3단계) / How to use (3 steps)", 20, true, 22));
        col.addView(text("1. 아래 버튼으로 설정을 열어 \"Wellsa Korean IME\" 를 켭니다.\n"
                + "   Open settings with the button below and turn on \"Wellsa Korean IME\".", 16, false, 8));
        col.addView(button("언어 및 입력 설정 열기 / Open Languages & input", new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        col.addView(text("2. 컴퓨터에서 입력기를 켜고 선택합니다.\n   Enable and select the input method from the computer.", 16, false, 14));
        col.addView(code(CMD_ENABLE));
        col.addView(text("3. 입력칸을 선택한 뒤 글자를 보냅니다.\n   Focus a text field, then send text with the broadcast command.", 16, false, 14));
        col.addView(code(CMD_SEND));
        col.addView(text("호스트용 스크립트(host/ime_input.py)가 이 과정을 자동으로 처리합니다. 자세한 내용은 아래 소스 저장소를 보세요.\n"
                + "The host script (host/ime_input.py) automates these steps; see the source repository below.", 14, false, 8));

        col.addView(text("개인정보 / Privacy", 20, true, 22));
        col.addView(text("인터넷 권한이 없고, 어떤 글자도 저장하거나 외부로 보내지 않습니다. 비밀번호 칸에는 입력하지 않고 거부합니다.", 16, false, 6));
        col.addView(text("No network permission. Nothing is stored or sent anywhere. Password fields are refused.", 15, false, 6));
        col.addView(button("개인정보 처리방침 / Privacy policy", new Intent(Intent.ACTION_VIEW, Uri.parse(POLICY_URL))));
        col.addView(button("오픈소스 저장소 / Open source", new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return in;
            }
        });
        setContentView(sv);
    }
}
