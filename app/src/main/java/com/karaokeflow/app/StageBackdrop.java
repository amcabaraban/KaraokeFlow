package com.karaokeflow.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Original scalable artwork: aurora, mountains and lake, with no network assets. */
final class StageBackdrop extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Override public void draw(Canvas canvas) {
        float w = getBounds().width(), h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xff020d1c,0xff12364d,0xff05101e}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(new RadialGradient(w*.65f,h*.25f,w*.65f,
                new int[]{0x7022c8b5,0x202294aa,0x002294aa},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(null);
        paint.setColor(0x8890dbe8);
        for(int i=0;i<70;i++) {
            float x=((i*137+29)%997)/997f*w;
            float y=((i*73+11)%541)/541f*h*.48f;
            canvas.drawCircle(x,y,i%5==0?1.4f:.7f,paint);
        }
        for(int layer=0;layer<3;layer++) {
            Path mountain=new Path();
            float base=h*(.61f+layer*.055f);
            mountain.moveTo(0,base);
            for(int i=0;i<=12;i++) {
                float x=i*w/12;
                float peak=base-h*(.06f+((i*7+layer*3)%9)*.016f);
                mountain.lineTo(x,peak);
            }
            mountain.lineTo(w,h*.76f); mountain.lineTo(0,h*.76f); mountain.close();
            paint.setColor(new int[]{0xff183044,0xff0c2337,0xff071827}[layer]);
            canvas.drawPath(mountain,paint);
        }
        paint.setShader(new LinearGradient(0,h*.72f,0,h,
                new int[]{0xff164351,0xff020d18},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,h*.72f,w,h,paint);
        paint.setShader(null);
        for(int i=0;i<30;i++){
            float y=h*(.73f+i*.008f);
            paint.setColor(i%3==0?0x2839bdce:0x1429a0b5);
            float width=w*(.1f+(i%7)*.035f);
            canvas.drawRect(w*.6f-width,y,w*.6f+width,y+1,paint);
        }
        paint.setShader(new LinearGradient(0,0,0,h,
                new int[]{0x28000000,0x08000000,0xa0000610},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(null);
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
