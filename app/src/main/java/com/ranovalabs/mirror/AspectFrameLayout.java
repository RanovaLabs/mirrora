package com.ranovalabs.mirror;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Letterboxes its child to the mirrored stream's aspect ratio (portrait phones, rotation). */
public final class AspectFrameLayout extends FrameLayout {

    private float aspect; // width / height, 0 = fill

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setAspectRatio(int width, int height) {
        float next = height > 0 ? (float) width / height : 0;
        if (next != aspect) {
            aspect = next;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (aspect > 0) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            if ((float) w / h > aspect) {
                w = Math.round(h * aspect);
            } else {
                h = Math.round(w / aspect);
            }
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
