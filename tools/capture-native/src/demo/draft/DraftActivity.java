package demo.draft;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.InputType;

public final class DraftActivity extends Activity {
  public EditText editor;
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setStatusBarColor(Color.rgb(247,246,242));
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.BOTTOM); root.setBackgroundColor(Color.rgb(247,246,242));
    int pad=(int)(24*getResources().getDisplayMetrics().density);
    root.setPadding(pad,pad,pad,pad);
    TextView label=new TextView(this); label.setText("Draft"); label.setTextSize(14); label.setTextColor(Color.rgb(119,117,108));
    root.addView(label,new LinearLayout.LayoutParams(-1,-2));
    editor=new EditText(this); editor.setTextSize(25); editor.setTextColor(Color.rgb(32,35,33));
    editor.setBackgroundColor(Color.TRANSPARENT); editor.setPadding(0,pad/2,0,pad/2);
    editor.setGravity(Gravity.TOP); editor.setMinLines(3); editor.setMaxLines(5);
    editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    root.addView(editor,new LinearLayout.LayoutParams(-1,-2));
    setContentView(root); editor.requestFocus();
    editor.postDelayed(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT),500);
  }
}
