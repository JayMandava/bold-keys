import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Shell-only fixture: inject real touchscreen gestures without a VM launch between taps. */
public class TouchDriver {
    public static void main(String[] args) throws Exception {
        Class<?> cls = Class.forName("android.hardware.input.InputManager");
        Object manager = cls.getDeclaredMethod("getInstance").invoke(null);
        Method inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        float x = Float.parseFloat(args[0]), y = Float.parseFloat(args[1]);
        int taps = Integer.parseInt(args[2]);
        long hold = args.length > 3 ? Long.parseLong(args[3]) : 40;
        for (int i = 0; i < taps; i++) {
            long down = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN); inject.invoke(manager, event, 0); event.recycle();
            SystemClock.sleep(hold);
            String finish = args.length > 4 ? args[4] : "up";
            float endY = y;
            if (finish.equals("slide")) {
                endY = y - 200;
                event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, endY, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN); inject.invoke(manager, event, 0); event.recycle();
                SystemClock.sleep(100);
            }
            int action = finish.equals("cancel") ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP;
            event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, endY, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN); inject.invoke(manager, event, 0); event.recycle();
            if (i + 1 < taps) SystemClock.sleep(80);
        }
    }
}
