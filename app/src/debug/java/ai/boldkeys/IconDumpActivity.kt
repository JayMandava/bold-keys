package ai.boldkeys

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import java.io.File

/** Debug only: draws the app's launcher icon the way the system loads it and saves it as a picture (adb run-as ... cat files/icon.png). */
class IconDumpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = StringBuilder()
        for ((name, id) in listOf("background" to R.drawable.ic_launcher_background, "foreground" to R.drawable.ic_launcher_foreground, "monochrome" to R.drawable.ic_launcher_monochrome, "mipmap" to R.mipmap.ic_launcher)) {
            try { val x = resources.getDrawable(id, theme); report.append("$name OK ${x.javaClass.simpleName}\n") }
            catch (t: Throwable) { report.append("$name FAILED: $t\n"); var c = t.cause; while (c != null) { report.append("   caused by $c\n"); c = c.cause }; android.util.Log.e("BKIcon", "$name failed", t) }
        }
        File(filesDir, "report.txt").writeText(report.toString())
        val d = packageManager.getApplicationIcon(packageName)
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF808080.toInt())
        d.setBounds(0, 0, 432, 432); d.draw(Canvas(bmp))
        File(filesDir, "icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(filesDir, "icon.txt").writeText("${d.javaClass.name} ${d.intrinsicWidth}x${d.intrinsicHeight}")
        finish()
    }
}
