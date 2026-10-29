package ai.boldkeys

/** Keep client-local inset quantities separate from real screen-space IME bounds. */
object CaptureImeGeometry {
    data class Rect(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width get()=right-left
        val height get()=bottom-top
        fun contains(other:Rect)=left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom
        fun positive()=width>0 && height>0
    }
    fun consistentInsets(visible:Boolean,rawBottom:Int,observedBottom:Int)=
        visible && rawBottom>=0 && rawBottom==observedBottom
    fun stableLayoutCandidate(width:Int,height:Int,ime:Rect,keyboard:Rect,bar:Rect):Boolean {
        val screen=Rect(0,0,width,height)
        return ime.positive() && keyboard.positive() && bar.positive() &&
            screen.contains(ime) && ime.contains(keyboard) && ime.contains(bar) &&
            keyboard.width>=width*0.8f && bar.bottom<=keyboard.top
    }
}
