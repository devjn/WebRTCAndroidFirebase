package com.github.devjn.webrtcandroidfirebase.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout


/**
 * Created by @author Jahongir on 15-Feb-18
 * devjn@jn-arts.com
 * RoundedView
 */
public class RoundedView : FrameLayout {

    private val path = Path()

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // compute the path
        val halfWidth = w / 2f
        val halfHeight = h / 2f
        path.reset()
        path.addCircle(halfWidth, halfHeight, Math.min(halfWidth, halfHeight), Path.Direction.CW)
        path.close()

    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

}