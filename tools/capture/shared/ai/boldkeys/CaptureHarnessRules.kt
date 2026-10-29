package ai.boldkeys

/** Test harness policies only: no keyboard behaviour or rendered pixels change. */
object CaptureHarnessRules {
    data class Bounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width get()=right-left
        val height get()=bottom-top
        val cx get()=(left+right)/2f
        val cy get()=(top+bottom)/2f
    }
    fun swatchIndices(bounds:List<Bounds>,sectionTop:Int,brightnessTop:Int,count:Int):List<Int> {
        val eligible=bounds.indices.filter { i ->
            val b=bounds[i]
            b.width>0 && b.height>0 && b.top>=sectionTop && b.bottom<=brightnessTop &&
                b.width.toFloat()/b.height in 0.8f..1.25f
        }
        val rows=eligible.map { anchor ->
            eligible.filter { i -> kotlin.math.abs(bounds[i].cy-bounds[anchor].cy)<=minOf(bounds[i].height,bounds[anchor].height)*0.2f }
                .sortedBy { bounds[it].cx }
        }.distinct().filter { row -> row.size==count && row.zipWithNext().all { (a,b) -> bounds[a].right<=bounds[b].left } }
        return rows.singleOrNull() ?: emptyList() // ambiguous/missing never means guessed coordinates
    }
    fun verticalOffsets(direction:Int):List<Float> {
        require(direction==1 || direction == -1)
        // Vertical cursor quantisation is 26dp. Previous 60px was only 22.86dp
        // at 420dpi, so it never generated a picker-scroll callback.
        return (0..8).map { direction*it*4f } // 32dp, paced by real injection below
    }
    fun requireDelivery(delivered:Boolean,expectedExternal:String?,actualForeground:String?,beforeForeground:String?) {
        check(delivered || (expectedExternal!=null && beforeForeground!=null && beforeForeground!=expectedExternal && actualForeground==expectedExternal)) {
            "Touch was not delivered and no verified expected external-window handoff occurred"
        }
    }
    fun eligibleWindow(type:Int,packageName:String,targetPackage:String)=
        packageName==targetPackage && type in setOf(1,2,1003) // base/application/attached dialog; never IME
}
