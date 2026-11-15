package demo.draft;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import java.io.*;
import java.util.*;

/** Own disposable editor only. Keyboard content is changed exclusively by injected physical touch events. */
public final class Capture extends Instrumentation {
  UiAutomation ui; DraftActivity activity; File out; PrintWriter phases; long began;
  public void onCreate(Bundle b) { super.onCreate(b); start(); }
  public void onStart() {
    Bundle result=new Bundle();
    try {
      out=getTargetContext().getExternalFilesDir(null); out.mkdirs();
      phases=new PrintWriter(new File(out,"phases.tsv"),"UTF-8"); began=SystemClock.elapsedRealtime();
      ui=getUiAutomation();
      AccessibilityServiceInfo info=ui.getServiceInfo();
      info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
      ui.setServiceInfo(info);
      Intent i=new Intent(getTargetContext(),DraftActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity=(DraftActivity)startActivitySync(i);
      key("Space",15000); pause(1500); phase("empty-draft"); snap("00-empty");
      type("Meet me by the lake at five"); tapLabel("Alt"); tapLabel("Period");
      expect("Meet me by the lake at five."); phase("original-complete"); snap("01-original"); pause(2200);
      dragTrack(-1,false); checkSelection(27,27); phase("cursor-before-period"); snap("02-cursor"); pause(1100);
      dragTrack(-4,true); checkSelection(23,27); phase("five-selected"); snap("03-selection"); pause(2000);
      type("six"); expect("Meet me by the lake at six."); phase("replacement-complete"); snap("04-replaced"); pause(1500);
      dragTrack(1,false); checkSelection(27,27); tapLabel("Space");
      tapLabel("Emoji and Speed shortcuts"); phase("emoji-picker"); pause(1400); snap("05-picker");
      Rect wave=find("Emoji 👋");
      for(int n=0; wave==null && n<10; n++){tapLabel("More emoji"); pause(500); wave=find("Emoji 👋");}
      if(wave==null) throw new AssertionError("Wave not exposed in actual picker; refusing fabricated result");
      tap(wave); pause(600); tapLabel("Close picker");
      expect("Meet me by the lake at six. 👋"); phase("final-draft"); snap("06-final"); pause(5500);
      phase("PASS"); result.putString("result","PASS: actual taps, trackpad movement, multitouch selection, replacement and emoji verified");
      finish(Activity.RESULT_OK,result);
    } catch(Throwable e) {
      try {phase("FAIL "+e.toString()); snap("failure"); dump();}catch(Throwable ignored){}
      result.putString("error",android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED,result);
    } finally {if(phases!=null)phases.close();}
  }
  void pause(long n){SystemClock.sleep(n);}
  void phase(String s){phases.println((SystemClock.elapsedRealtime()-began)+"\t"+s);phases.flush();android.util.Log.i("NativeCapture",s);}
  void snap(String name) throws IOException {Bitmap b=ui.takeScreenshot();if(b==null)throw new IOException("No screenshot");try(FileOutputStream f=new FileOutputStream(new File(out,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}
  List<AccessibilityNodeInfo> nodes(){List<AccessibilityNodeInfo>a=new ArrayList<>();for(AccessibilityWindowInfo w:ui.getWindows())walk(w.getRoot(),a);return a;}
  void walk(AccessibilityNodeInfo n,List<AccessibilityNodeInfo>a){if(n==null)return;a.add(n);for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),a);}
  Rect find(String label){for(AccessibilityNodeInfo n:nodes()){if(!"ai.boldkeys".contentEquals(n.getPackageName()==null?"":n.getPackageName()))continue;String s=String.valueOf(n.getContentDescription());if(label.equals(s)&&n.isVisibleToUser()){Rect r=new Rect();n.getBoundsInScreen(r);if(r.width()>2&&r.height()>2)return r;}}return null;}
  Rect key(String label,long timeout){long until=SystemClock.elapsedRealtime()+timeout;do{Rect r=find(label);if(r!=null)return r;pause(100);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError("Missing real keyboard node: "+label);}
  void tapLabel(String s){tap(key(s,4000));}
  void tap(Rect r){long t=SystemClock.uptimeMillis();event(t,MotionEvent.ACTION_DOWN,new float[]{r.exactCenterX()},new float[]{r.exactCenterY()});pause(65);event(t,MotionEvent.ACTION_UP,new float[]{r.exactCenterX()},new float[]{r.exactCenterY()});pause(175);}
  void type(String text){for(char c:text.toCharArray()){if(c==' '){tapLabel("Space");continue;}String label=Character.isUpperCase(c)?"Capital "+c:String.valueOf(c);tapLabel(label);}}
  void event(long t,int action,float[] xs,float[] ys){int n=xs.length;MotionEvent.PointerProperties[]p=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[]c=new MotionEvent.PointerCoords[n];for(int i=0;i<n;i++){p[i]=new MotionEvent.PointerProperties();p[i].id=i;p[i].toolType=MotionEvent.TOOL_TYPE_FINGER;c[i]=new MotionEvent.PointerCoords();c[i].x=xs[i];c[i].y=ys[i];c[i].pressure=1;c[i].size=.1f;}MotionEvent e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,n,p,c,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);if(!ui.injectInputEvent(e,true))throw new AssertionError("Input injection failed");e.recycle();}
  void dragTrack(int chars,boolean select){Rect track=key("Trackpad",4000);Rect shift=key("Shift",4000);float x=track.exactCenterX(),y=track.exactCenterY();float d=getTargetContext().getResources().getDisplayMetrics().density;float delta=chars*13*d+Math.signum(chars);long t=SystemClock.uptimeMillis();if(select){event(t,MotionEvent.ACTION_DOWN,new float[]{shift.exactCenterX()},new float[]{shift.exactCenterY()});pause(40);event(t,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{shift.exactCenterX(),x},new float[]{shift.exactCenterY(),y});}else event(t,MotionEvent.ACTION_DOWN,new float[]{x},new float[]{y});
    for(int i=1;i<=8;i++){pause(25);float to=x+delta*i/8; if(select)event(t,MotionEvent.ACTION_MOVE,new float[]{shift.exactCenterX(),to},new float[]{shift.exactCenterY(),y});else event(t,MotionEvent.ACTION_MOVE,new float[]{to},new float[]{y});}
    if(select){event(t,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{shift.exactCenterX(),x+delta},new float[]{shift.exactCenterY(),y});event(t,MotionEvent.ACTION_UP,new float[]{shift.exactCenterX()},new float[]{shift.exactCenterY()});}else event(t,MotionEvent.ACTION_UP,new float[]{x+delta},new float[]{y});pause(350);
  }
  String[] state(){final String[]s=new String[3];runOnMainSync(()->{s[0]=activity.editor.getText().toString();s[1]=""+activity.editor.getSelectionStart();s[2]=""+activity.editor.getSelectionEnd();});return s;}
  void expect(String wanted){pause(300);String actual=state()[0];if(!wanted.equals(actual))throw new AssertionError("Text mismatch expected="+wanted+" actual="+actual);}
  void checkSelection(int start,int end){String[]s=state();if(!s[1].equals(""+start)||!s[2].equals(""+end))throw new AssertionError("Selection mismatch expected="+start+","+end+" actual="+Arrays.toString(s));}
  void dump()throws IOException{try(PrintWriter p=new PrintWriter(new File(out,"failure-nodes.txt"),"UTF-8")){for(AccessibilityNodeInfo n:nodes())p.println(n.toString());p.println("Own editor: "+Arrays.toString(state()));}}
}
