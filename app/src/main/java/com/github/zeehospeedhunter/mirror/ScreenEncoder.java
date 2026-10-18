package com.github.zeehospeedhunter.mirror;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;

/** Serialized codec fallback; a single VirtualDisplay per projection consent token. */
final class ScreenEncoder {
    private final HandlerThread thread = new HandlerThread("MirrorEncoder");
    private final Handler handler;
    private final MediaProjection projection;
    private final Consumer<byte[]> frame;
    private final Consumer<String> error, log;
    private final boolean smooth;
    private final int targetFps;
    private final Object snapshotLock=new Object();
    private long totalEncoded, totalSubmitted, lastOutputMs;
    static final class Snapshot {
        final long encoded,submitted,lastOutput;
        final String codec;
        Snapshot(long e,long s,long last,String codec) {encoded=e;submitted=s;lastOutput=last;this.codec=codec;}
    }
    Snapshot snapshot() { synchronized(snapshotLock) {return new Snapshot(totalEncoded,totalSubmitted,lastOutputMs,codecName);} }
    private final Consumer<Performance> performance;
    private boolean syncRequested=true;
    private long statsAt, outputCount, forwardedCount, ageCount;
    private double ageSum;
    static final class Performance {
        final double encodedFps, forwardedFps, ageMs;
        final String codec;
        Performance(double encodedFps,double forwardedFps,double ageMs,String codec) {
            this.encodedFps=encodedFps; this.forwardedFps=forwardedFps; this.ageMs=ageMs; this.codec=codec;
        }
    }
    private final List<Attempt> attempts = new ArrayList<>();
    private MediaCodec codec;
    private VirtualDisplay display;
    private Surface surface;
    private byte[] headers = new byte[0];
    private boolean closed, delivered;
    private int width, height, nextAttempt;
    private long lastSyncRequest;
    private volatile String codecName="未选择";
    private String phase="枚举编码器";
    private static final String MIME="video/avc";

    private static final class Attempt {
        final String name;
        final int fps, bitrate;
        final boolean baseline;
        Attempt(String name,int fps,int bitrate,boolean baseline) {
            this.name=name; this.fps=fps; this.bitrate=bitrate; this.baseline=baseline;
        }
    }
    ScreenEncoder(MediaProjection projection, Consumer<byte[]> frame, Consumer<String> error, Consumer<String> log) {
        this(projection,frame,error,log,false,p -> {});
    }
    ScreenEncoder(MediaProjection projection, Consumer<byte[]> frame, Consumer<String> error, Consumer<String> log,
                  boolean smooth, Consumer<Performance> performance) {
        this(projection,frame,error,log,smooth,performance,20);
    }
    ScreenEncoder(MediaProjection projection, Consumer<byte[]> frame, Consumer<String> error, Consumer<String> log,
                  boolean smooth, Consumer<Performance> performance, int targetFps) {
        this.projection=projection; this.frame=frame; this.error=error; this.log=log;
        this.targetFps=targetFps;
        this.smooth=smooth; this.performance=performance;
        thread.start(); handler=new Handler(thread.getLooper());
    }
    void configure(int w,int h) { handler.post(() -> {
        if(closed || (codec!=null && width==w && height==h)) return;
        try {
            if(w<16 || h<16 || w>1920 || h>1920 || (long)w*h>2073600)
                throw new IllegalArgumentException("仪表请求的分辨率不受支持："+w+"×"+h);
            releaseCodec(); width=w; height=h; delivered=false; nextAttempt=0; attempts.clear();
            phase="枚举编码器";
            Set<String> seen=new HashSet<>();
            List<MediaCodecInfo> infos=new ArrayList<>(Arrays.asList(new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()));
            // Android's known software codecs are last; keep vendor ordering otherwise.
            infos.sort(Comparator.comparingInt(i -> isKnownSoftware(i.getName()) ? 1 : 0));
            for(MediaCodecInfo info:infos) {
                if(!info.isEncoder() || !seen.add(info.getName())) continue;
                boolean avc=false;
                for(String type:info.getSupportedTypes()) if(MIME.equalsIgnoreCase(type)) avc=true;
                if(!avc) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(MIME);
                    boolean supportsSurface=false, baseline=false;
                    for(int color:caps.colorFormats)
                        if(color==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) supportsSurface=true;
                    if(!supportsSurface) continue;
                    for(MediaCodecInfo.CodecProfileLevel profile:caps.profileLevels)
                        if(profile.profile==MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) baseline=true;
                    MediaCodecInfo.VideoCapabilities video=caps.getVideoCapabilities();
                    for(int pass=0;pass<2;pass++) {
                        int fps=pass==0?targetFps:Math.min(15,targetFps); // 15 FPS remains the compatibility fallback
                        if(video!=null && !video.areSizeAndRateSupported(w,h,fps)) continue;
                        int rate=smooth?(fps>=20?1200000:(fps>=15?800000:500000)):(fps>=20?2000000:1000000);
                        if(video!=null) rate=video.getBitrateRange().clamp(rate);
                        // GOP=1 rather than all-I(0), which some vendor OMX encoders reject.
                        // Retry without a forced profile, even when advertised Baseline fails.
                        attempts.add(new Attempt(info.getName(),fps,rate,pass==0 && baseline));
                    }
                } catch(RuntimeException e) { log.accept("跳过编码器 "+info.getName()+"："+detail(e)); }
            }
            if(attempts.isEmpty()) throw new IllegalStateException("没有支持 "+w+"×"+h+" Surface 输入的 H.264 编码器");
            startNext();
        } catch(Exception e) { fail(e); }
    }); }

    private void startNext() {
        handler.removeCallbacks(drain);
        while(!closed && nextAttempt<attempts.size()) {
            Attempt a=attempts.get(nextAttempt++);
            statsAt=SystemClock.elapsedRealtime(); outputCount=0;forwardedCount=0;ageCount=0;ageSum=0;syncRequested=true;
            codecName=a.name; phase="创建编码器"; headers=new byte[0]; delivered=false; lastSyncRequest=0;
            log.accept("尝试编码器 "+codecName+" · "+width+"×"+height+" · "+a.fps+"fps · "+a.bitrate/1000+"kbps · "+(a.baseline?"Baseline":"默认 profile"));
            try {
                MediaFormat format=MediaFormat.createVideoFormat(MIME,width,height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE,a.bitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE,a.fps);
                if(smooth && Build.VERSION.SDK_INT>=29) format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,(float)a.fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
                if(a.baseline) format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                codec=MediaCodec.createByCodecName(a.name);
                phase="配置编码器"; codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                phase="创建输入 Surface"; surface=codec.createInputSurface();
                phase="启动编码器"; codec.start();
            } catch(Exception e) {
                log.accept("编码器尝试失败（"+codecName+" / "+phase+"）："+detail(e));
                releaseCodec(); continue;
            }
            // Projection failures are distinct from codec failures. Never consume a token twice.
            try {
                phase="连接屏幕采集";
                if(display==null) display=projection.createVirtualDisplay("SpeedHunter",width,height,160,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,handler);
                else { display.resize(width,height,160); display.setSurface(surface); }
                if(display==null) throw new IllegalStateException("系统未创建屏幕采集显示");
                log.accept("编码器已启动："+codecName+"；等待首个关键帧");
                phase="读取编码帧"; handler.post(drain); return;
            } catch(Exception e) { fail(e); return; }
        }
        if(!closed) { phase="编码器回退"; fail(new IllegalStateException("所有兼容配置均失败，请提供本页编码器日志")); }
    }

    private final Runnable drain=new Runnable() { public void run() {
        if(closed || codec==null) return;
        try {
            long now=SystemClock.elapsedRealtime();
            if(syncRequested || now-lastSyncRequest>=(smooth?500:100)) {
                // Short GOP limits embedded-decoder recovery time; the native queue preserves
                // every predictive dependency and requests a new IDR after congestion.
                try { Bundle params=new Bundle(); params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0); codec.setParameters(params); }
                catch(RuntimeException e) { if(lastSyncRequest==0) log.accept("编码器不接受即时关键帧请求，将使用周期关键帧："+detail(e)); }
                lastSyncRequest=now; syncRequested=false;
            }
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            for(int n=0;n<12;n++) {
                int index=codec.dequeueOutputBuffer(info,0);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    MediaFormat f=codec.getOutputFormat();
                    for(String key:new String[]{"csd-0","csd-1"}) {
                        ByteBuffer b=f.getByteBuffer(key);
                        if(b!=null) out.write(AvcFrames.annexB(copy(b.duplicate())));
                    }
                    headers=out.toByteArray(); continue;
                }
                if(index<0) break;
                try {
                    ByteBuffer b=codec.getOutputBuffer(index);
                    if(b==null || info.size<=0) continue;
                    b.position(info.offset); b.limit(info.offset+info.size);
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) {
                        headers=AvcFrames.annexB(copy(b)); continue;
                    }
                    byte[] sample=AvcFrames.annexB(copy(b));
                    // Check the actual NAL too: codec flags alone do not guarantee an IDR.
                    outputCount++;
                    double age=(System.nanoTime()/1000L-info.presentationTimeUs)/1000.0;
                    if(age>=0 && age<10000) { ageSum+=age; ageCount++; }
                    boolean key=AvcFrames.hasIdr(sample);
                    synchronized(snapshotLock) {
                        totalEncoded++;if(smooth || key) totalSubmitted++;
                        lastOutputMs=SystemClock.elapsedRealtime();
                    }
                    if(!smooth && !key) continue;
                    byte[] joined=sample;
                    if(key) {
                        joined=new byte[headers.length+sample.length];
                        System.arraycopy(headers,0,joined,0,headers.length);
                        System.arraycopy(sample,0,joined,headers.length,sample.length);
                    }
                    frame.accept(joined); forwardedCount++;
                    if(key && !delivered) { delivered=true; log.accept("首个 H.264 关键帧已编码（"+codecName+"），等待仪表拉取"); }
                } finally { codec.releaseOutputBuffer(index,false); }
            }
            long elapsed=SystemClock.elapsedRealtime()-statsAt;
            if(elapsed>=2000) {
                performance.accept(new Performance(outputCount*1000.0/elapsed,forwardedCount*1000.0/elapsed,
                        ageCount==0?-1:ageSum/ageCount,codecName));
                statsAt=SystemClock.elapsedRealtime();outputCount=0;forwardedCount=0;ageCount=0;ageSum=0;
            }
            handler.postDelayed(this,5);
        } catch(Exception e) {
            log.accept("编码器运行失败（"+codecName+" / "+phase+"）："+detail(e));
            releaseCodec(); startNext();
        }
    }};
    void requestSync() { handler.post(() -> { if(!closed) syncRequested=true; }); }
    private static boolean isKnownSoftware(String name) {
        String n=name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.");
    }
    private static byte[] copy(ByteBuffer b) { byte[] a=new byte[b.remaining()]; b.get(a); return a; }
    private static String detail(Exception e) {
        if(e instanceof MediaCodec.CodecException) {
            MediaCodec.CodecException c=(MediaCodec.CodecException)e;
            return c.getDiagnosticInfo()+" [code="+c.getErrorCode()+", recoverable="+c.isRecoverable()+", transient="+c.isTransient()+"] "+c.getMessage();
        }
        return e.getClass().getSimpleName()+": "+e.getMessage();
    }
    private void fail(Exception e) {
        releaseCodec(); error.accept("屏幕编码失败（"+codecName+" / "+phase+"）："+detail(e));
    }
    private void releaseCodec() {
        handler.removeCallbacks(drain);
        if(display!=null) try { display.setSurface(null); } catch(RuntimeException ignored) {}
        if(codec!=null) {
            try { codec.stop(); } catch(RuntimeException ignored) {}
            try { codec.release(); } catch(RuntimeException ignored) {}
            codec=null;
        }
        if(surface!=null) { try { surface.release(); } catch(RuntimeException ignored) {} surface=null; }
    }
    void close(Runnable done) { handler.post(() -> {
        try {
            closed=true; releaseCodec();
            if(display!=null) { try { display.release(); } finally { display=null; } }
        } finally { thread.quitSafely(); done.run(); }
    }); }
}
