package com.github.speedhunter.mirror;

import android.app.Activity;
import android.content.*;
import androidx.core.content.ContextCompat;
import android.graphics.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

/** Known moving source for separating map/app redraw rate from the projection pipeline. */
public final class MirrorTestActivity extends Activity {
    private Pattern pattern;
    private boolean registered;
    private final BroadcastReceiver stats=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            String perf=i.getStringExtra("perf"), message=i.getStringExtra("message");
            if(pattern==null) return;
            if(perf!=null) {pattern.lines=perf.split("\n");pattern.statsAt=SystemClock.elapsedRealtime();}
            if(message!=null) pattern.status=message;
        }
    };
    @Override public void onStart() {
        super.onStart();ContextCompat.registerReceiver(this,stats,new IntentFilter(StandaloneMirrorService.STATE),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;
    }
    @Override public void onStop() {if(registered){unregisterReceiver(stats);registered=false;}super.onStop();}
    @Override public void onCreate(Bundle state) { super.onCreate(state); pattern=new Pattern(this); setContentView(pattern); }
    @Override public void onResume() { super.onResume(); if(pattern!=null) { pattern.running=true; pattern.postInvalidateOnAnimation(); } }
    @Override public void onPause() { if(pattern!=null) pattern.running=false; super.onPause(); }
    static final class Pattern extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean running;
        String[] lines={"等待性能统计…"};
        String status="比较手机与仪表的移动条；返回键退出";
        long statsAt;
        long start=SystemClock.elapsedRealtime(), window=start, count;
        double fps;
        Pattern(Context c) { super(c); }
        @Override protected void onDraw(Canvas canvas) {
            long now=SystemClock.elapsedRealtime(), elapsed=now-start; count++;
            if(now-window>=1000) { fps=count*1000.0/(now-window); count=0;window=now; }
            canvas.drawColor(Color.rgb(16,22,32));
            float w=getWidth(), h=getHeight(), size=Math.max(16,w/28);
            paint.setColor(Color.WHITE);paint.setTextSize(size);
            canvas.drawText("SpeedHunter 投屏流畅度测试",w*.05f,h*.12f,paint);
            canvas.drawText(String.format(java.util.Locale.ROOT,"源画面 %.1f fps · 时间 %.3f s",fps,elapsed/1000.0),w*.05f,h*.2f,paint);
            paint.setColor(Color.rgb(89,210,190));
            float x=(elapsed%4000)/4000f*Math.max(1,w-w*.15f);
            canvas.drawRect(x,h*.26f,x+w*.15f,h*.43f,paint);
            paint.setColor(Color.LTGRAY);float font=Math.max(14,w/55);paint.setTextSize(font);
            float y=h*.49f;
            for(String line:lines) {canvas.drawText(line,w*.03f,y,paint);y+=font*1.25f;}
            paint.setColor(Color.rgb(255,210,120));
            canvas.drawText(status,w*.03f,Math.max(y+font,h*.9f),paint);
            if(statsAt>0) canvas.drawText(String.format(java.util.Locale.ROOT,"统计更新于 %.1f 秒前",(now-statsAt)/1000.0),w*.03f,Math.max(y+font*2.4f,h*.96f),paint);
            if(running) postInvalidateOnAnimation();
        }
    }
}
