package com.android2.calculator3.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import com.rey.material.drawable.RippleDrawable;

/**
 * RippleDrawable has to be set in code (for backwards compatibility reasons)
 */
public class ImageButton extends com.rey.material.widget.ImageButton {
    private int mColor;

    public ImageButton(Context context) {
        super(context);
        determineColor(context, null, 0, 0);
    }

    public ImageButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        determineColor(context, attrs, 0, 0);
    }

    public ImageButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        determineColor(context, attrs, defStyleAttr, 0);
    }

    public ImageButton(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr);
        determineColor(context, attrs, defStyleAttr, defStyleRes);
    }

    private void determineColor(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes){
        ColorDrawable oldDrawable = (ColorDrawable) getBackground();
        mColor = oldDrawable.getColor();
//        invalidateBackground();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        invalidateBackground();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidateBackground();
    }

    private void invalidateBackground() {
        Drawable drawable = isEnabled() && !isSelected() ? createRipple() : null;
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            setBackground(drawable);
        } else {
            setBackgroundDrawable(drawable);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        invalidateBackground();
    }

    private Drawable createRipple() {
        return new RippleDrawable.Builder()
                .rippleColor(mColor)
                .backgroundColor(Color.argb(0x33, Color.red(mColor), Color.green(mColor), Color.blue(mColor)))
                .cornerRadius(getMeasuredWidth() / 2)
                .build();
    }
}
