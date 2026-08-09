package com.xinyv.median;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

final class BrowserIconView extends View {
    static final int BACK = 1;
    static final int FORWARD = 2;
    static final int HOME = 3;
    static final int TABS = 4;
    static final int MENU = 5;
    static final int RELOAD = 6;
    static final int SHIELD = 7;
    static final int SEARCH = 8;
    static final int CLOSE = 9;
    static final int PLUS = 10;
    static final int KEY = 11;
    static final int SPEED = 12;
    static final int STORAGE = 13;
    static final int DESKTOP = 14;
    static final int SHARE = 15;
    static final int INFO = 16;
    static final int SCRIPT = 17;
    static final int DOWNLOAD = 18;

    private static final Typeface BOLD_TYPEFACE = Typeface.create("sans", Typeface.BOLD);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF rect2 = new RectF();
    private int icon;
    private int count = 1;
    private String countText = "1";
    private boolean active;
    private int tintColor = Color.rgb(60, 64, 67);

    BrowserIconView(Context context, int icon) {
        super(context);
        this.icon = icon;
        setClickable(true);
        setFocusable(true);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setMinimumWidth(dp(40));
        setMinimumHeight(dp(40));
    }

    void setIcon(int icon) {
        if (this.icon == icon) return;
        this.icon = icon;
        invalidate();
    }

    void setCount(int count) {
        if (this.count == count) return;
        this.count = count;
        countText = count > 99 ? "99" : String.valueOf(Math.max(1, count));
        invalidate();
    }

    void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        invalidate();
    }

    void setTintColor(int color) {
        if (tintColor == color) return;
        tintColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float s = Math.min(getWidth(), getHeight());
        int color = active ? Color.rgb(26, 115, 232) : tintColor;
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(dp(1.8f), s * .045f));
        paint.setStyle(Paint.Style.STROKE);
        path.reset();

        switch (icon) {
            case BACK:
                path.moveTo(cx + s * .16f, cy - s * .24f);
                path.lineTo(cx - s * .10f, cy);
                path.lineTo(cx + s * .16f, cy + s * .24f);
                canvas.drawPath(path, paint);
                break;
            case FORWARD:
                path.moveTo(cx - s * .16f, cy - s * .24f);
                path.lineTo(cx + s * .10f, cy);
                path.lineTo(cx - s * .16f, cy + s * .24f);
                canvas.drawPath(path, paint);
                break;
            case HOME:
                path.moveTo(cx - s * .25f, cy - s * .02f);
                path.lineTo(cx, cy - s * .24f);
                path.lineTo(cx + s * .25f, cy - s * .02f);
                path.moveTo(cx - s * .18f, cy - s * .06f);
                path.lineTo(cx - s * .18f, cy + s * .22f);
                path.lineTo(cx + s * .18f, cy + s * .22f);
                path.lineTo(cx + s * .18f, cy - s * .06f);
                canvas.drawPath(path, paint);
                break;
            case TABS:
                rect.set(cx - s * .22f, cy - s * .22f, cx + s * .22f, cy + s * .22f);
                canvas.drawRoundRect(rect, s * .06f, s * .06f, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(BOLD_TYPEFACE);
                paint.setTextSize(count > 9 ? s * .22f : s * .27f);
                canvas.drawText(countText, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint);
                break;
            case MENU:
                paint.setStyle(Paint.Style.FILL);
                float r = s * .035f;
                canvas.drawCircle(cx, cy - s * .17f, r, paint);
                canvas.drawCircle(cx, cy, r, paint);
                canvas.drawCircle(cx, cy + s * .17f, r, paint);
                break;
            case RELOAD:
                rect.set(cx - s * .21f, cy - s * .21f, cx + s * .21f, cy + s * .21f);
                canvas.drawArc(rect, -65, 285, false, paint);
                path.moveTo(cx + s * .22f, cy - s * .18f);
                path.lineTo(cx + s * .22f, cy - s * .01f);
                path.lineTo(cx + s * .06f, cy - s * .06f);
                canvas.drawPath(path, paint);
                break;
            case SHIELD:
                path.moveTo(cx, cy - s * .27f);
                path.cubicTo(cx + s * .12f, cy - s * .20f, cx + s * .21f, cy - s * .20f, cx + s * .23f, cy - s * .19f);
                path.lineTo(cx + s * .20f, cy + s * .05f);
                path.cubicTo(cx + s * .17f, cy + s * .21f, cx + s * .04f, cy + s * .28f, cx, cy + s * .31f);
                path.cubicTo(cx - s * .04f, cy + s * .28f, cx - s * .17f, cy + s * .21f, cx - s * .20f, cy + s * .05f);
                path.lineTo(cx - s * .23f, cy - s * .19f);
                path.cubicTo(cx - s * .12f, cy - s * .20f, cx - s * .08f, cy - s * .23f, cx, cy - s * .27f);
                canvas.drawPath(path, paint);
                if (active) {
                    path.reset();
                    path.moveTo(cx - s * .10f, cy);
                    path.lineTo(cx - s * .02f, cy + s * .08f);
                    path.lineTo(cx + s * .13f, cy - s * .09f);
                    canvas.drawPath(path, paint);
                }
                break;
            case SEARCH:
                canvas.drawCircle(cx - s * .04f, cy - s * .04f, s * .16f, paint);
                canvas.drawLine(cx + s * .08f, cy + s * .08f, cx + s * .23f, cy + s * .23f, paint);
                break;
            case CLOSE:
                canvas.drawLine(cx - s * .17f, cy - s * .17f, cx + s * .17f, cy + s * .17f, paint);
                canvas.drawLine(cx + s * .17f, cy - s * .17f, cx - s * .17f, cy + s * .17f, paint);
                break;
            case PLUS:
                canvas.drawLine(cx - s * .20f, cy, cx + s * .20f, cy, paint);
                canvas.drawLine(cx, cy - s * .20f, cx, cy + s * .20f, paint);
                break;
            case KEY:
                canvas.drawCircle(cx - s * .11f, cy - s * .07f, s * .12f, paint);
                canvas.drawLine(cx - s * .01f, cy + s * .01f, cx + s * .22f, cy + s * .24f, paint);
                canvas.drawLine(cx + s * .12f, cy + s * .14f, cx + s * .18f, cy + s * .08f, paint);
                canvas.drawLine(cx + s * .18f, cy + s * .20f, cx + s * .24f, cy + s * .14f, paint);
                break;
            case SPEED:
                rect.set(cx - s * .25f, cy - s * .19f, cx + s * .25f, cy + s * .31f);
                canvas.drawArc(rect, 200, 140, false, paint);
                canvas.drawLine(cx, cy + s * .05f, cx + s * .15f, cy - s * .10f, paint);
                canvas.drawCircle(cx, cy + s * .05f, s * .025f, paint);
                break;
            case STORAGE:
                rect.set(cx - s * .21f, cy - s * .22f, cx + s * .21f, cy + s * .22f);
                rect2.set(rect.left, rect.top, rect.right, cy - s * .08f);
                canvas.drawOval(rect2, paint);
                canvas.drawLine(rect.left, cy - s * .15f, rect.left, cy + s * .16f, paint);
                canvas.drawLine(rect.right, cy - s * .15f, rect.right, cy + s * .16f, paint);
                rect2.set(rect.left, cy + s * .08f, rect.right, rect.bottom + s * .08f);
                canvas.drawArc(rect2, 0, 180, false, paint);
                rect2.set(rect.left, cy - s * .05f, rect.right, cy + s * .13f);
                canvas.drawArc(rect2, 0, 180, false, paint);
                break;
            case DESKTOP:
                rect.set(cx - s * .25f, cy - s * .20f, cx + s * .25f, cy + s * .15f);
                canvas.drawRoundRect(rect, s * .035f, s * .035f, paint);
                canvas.drawLine(cx, cy + s * .15f, cx, cy + s * .25f, paint);
                canvas.drawLine(cx - s * .13f, cy + s * .25f, cx + s * .13f, cy + s * .25f, paint);
                break;
            case SHARE:
                float rr = s * .065f;
                float x1 = cx - s * .17f, y1 = cy;
                float x2 = cx + s * .17f, y2 = cy - s * .17f;
                float x3 = cx + s * .17f, y3 = cy + s * .17f;
                canvas.drawLine(x1 + rr, y1 - rr * .3f, x2 - rr, y2 + rr * .3f, paint);
                canvas.drawLine(x1 + rr, y1 + rr * .3f, x3 - rr, y3 - rr * .3f, paint);
                canvas.drawCircle(x1, y1, rr, paint);
                canvas.drawCircle(x2, y2, rr, paint);
                canvas.drawCircle(x3, y3, rr, paint);
                break;
            case INFO:
                canvas.drawCircle(cx, cy, s * .23f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy - s * .11f, s * .025f, paint);
                rect.set(cx - s * .025f, cy - s * .02f, cx + s * .025f, cy + s * .15f);
                canvas.drawRoundRect(rect, s * .02f, s * .02f, paint);
                break;
            case SCRIPT:
                path.moveTo(cx - s * .07f, cy - s * .19f);
                path.lineTo(cx - s * .19f, cy);
                path.lineTo(cx - s * .07f, cy + s * .19f);
                path.moveTo(cx + s * .07f, cy - s * .19f);
                path.lineTo(cx + s * .19f, cy);
                path.lineTo(cx + s * .07f, cy + s * .19f);
                canvas.drawPath(path, paint);
                canvas.drawLine(cx + s * .04f, cy - s * .23f, cx - s * .04f, cy + s * .23f, paint);
                break;
            case DOWNLOAD:
                canvas.drawLine(cx, cy - s * .25f, cx, cy + s * .10f, paint);
                path.moveTo(cx - s * .13f, cy - s * .01f);
                path.lineTo(cx, cy + s * .13f);
                path.lineTo(cx + s * .13f, cy - s * .01f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(cx - s * .22f, cy + s * .14f);
                path.lineTo(cx - s * .22f, cy + s * .25f);
                path.lineTo(cx + s * .22f, cy + s * .25f);
                path.lineTo(cx + s * .22f, cy + s * .14f);
                canvas.drawPath(path, paint);
                break;
            default:
                break;
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
