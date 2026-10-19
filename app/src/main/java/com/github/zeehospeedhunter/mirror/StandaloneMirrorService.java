package com.github.zeehospeedhunter.mirror;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.net.wifi.p2p.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.ota.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import api.*;

/** Owns discovery, EC, capture and transport. No official app or Xposed calls. */
public final class StandaloneMirrorService extends Service implements CarP2p.Listener {
    private static volatile boolean projectionActive;
    public static boolean isActive() { return projectionActive; }
    public static final String STATE="com.github.zeehospeedhunter.mirror.STATE";
    public static final String START="start", STOP="stop", QUERY="query", SELECT="select";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<WifiP2pDevice> peers=new ArrayList<>();
    private CarP2p p2p;
    private MirrorRequest qr;
    private boolean smooth=true;
    private int targetFps=15;
    private double inputAt, previousInputs;
    private long previousEncoded,previousSubmitted;
    private android.hardware.display.DisplayManager displays;
    private int rotation=-1;
    private final android.hardware.display.DisplayManager.DisplayListener displayListener=new android.hardware.display.DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { }
        public void onDisplayRemoved(int id) { }
        public void onDisplayChanged(int id) {
            if(id!=android.view.Display.DEFAULT_DISPLAY || !active || displays==null) return;
            android.view.Display d=displays.getDisplay(id);if(d==null) return;
            int next=d.getRotation(); if(next==rotation) return;rotation=next;
            MobileSession s=session;if(s!=null) s.resetVideoStream();
            if(encoder!=null) encoder.requestSync();
            publish("屏幕方向已变化，清理旧预测帧并请求新关键帧");
        }
    };
    private MediaProjection projection;
    private ScreenEncoder encoder;
    private volatile MobileSession session;
    private volatile Socket initSocket;
    private volatile boolean active;
    private boolean connecting, handshake, stopping, ownsGroup, sentFrames;
    private WifiP2pDevice selected;
    private int addressRetries;
    private String status="就绪 · 独立投屏未启动";
    private volatile long generation;
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i==null) { stopSelf(); return START_NOT_STICKY; }
        String action=i.getAction();
        if(STOP.equals(action)) { stopMirror("已停止独立投屏"); return START_NOT_STICKY; }
        if(QUERY.equals(action)) {
            if(!active && !stopping) {
                String last=getSharedPreferences("mirror_runtime",MODE_PRIVATE).getString("lastStopReason",null);
                if(last!=null) status="上次结束："+last;
            }
            publish(status); if(!active && !stopping) stopSelf(); return START_NOT_STICKY; }
        if(SELECT.equals(action)) {
            String mac=i.getStringExtra("mac");
            if(active && !connecting && !handshake) for(WifiP2pDevice d:peers)
                if(d.deviceAddress.equals(mac)) { connect(d); break; }
            return START_NOT_STICKY;
        }
        if(!START.equals(action)) { if(!active) stopSelf(); return START_NOT_STICKY; }
        if(active || stopping) { publish("已有投屏会话，请先停止后重试"); return START_NOT_STICKY; }
        try {
            qr=MirrorRequest.parse(i.getStringExtra("url"));
            smooth=i.getBooleanExtra("smooth",true);
            int requested=i.getIntExtra("targetFps",15);
            targetFps=Math.max(10, Math.min(30, requested));
            inputAt=0;previousInputs=0;previousEncoded=0;previousSubmitted=0;
            Intent consent=Build.VERSION.SDK_INT>=33 ? i.getParcelableExtra("consent",Intent.class) : i.getParcelableExtra("consent");
            if(consent==null || i.getIntExtra("result",0)!=Activity.RESULT_OK) throw new IllegalArgumentException("缺少本次屏幕授权");
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel("mirror","仪表独立投屏",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,com.github.zeehospeedhunter.ui.MainActivity.class).putExtra(com.github.zeehospeedhunter.ui.MainActivity.EXTRA_TAB,R.id.nav_mirror),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,getClass()).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new NotificationCompat.Builder(this,"mirror").setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("SpeedHunter 独立投屏").setContentText("正在共享屏幕，点击停止可结束")
                    .setOngoing(true).setContentIntent(open).addAction(0,"停止投屏",stop).build();
            if(Build.VERSION.SDK_INT>=29) startForeground(409,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(409,notification);
            active=true; projectionActive=true; sentFrames=false; generation++;
            final long startedGeneration=generation;
            main.postDelayed(() -> { if(active && generation==startedGeneration && !sentFrames)
                stopMirror("120 秒内未能传输视频，已停止；请保持仪表在投屏二维码页后重试"); },120000);
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if(active) stopMirror("系统已结束屏幕共享"); }
            },main);
            encoder=new ScreenEncoder(projection, bytes -> {
                MobileSession s=session; if(s!=null && active) s.pushFrame(bytes);
            },error -> main.post(() -> stopMirror(error)), message -> main.post(() -> { if(active) publish(message); }),
              smooth, perf -> {}, targetFps);
            displays=getSystemService(android.hardware.display.DisplayManager.class);
            android.view.Display d=displays.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            rotation=d==null?-1:d.getRotation();
            displays.registerDisplayListener(displayListener,main);
            p2p=new CarP2p(this,main,this);
            publish((smooth?"连续帧模式 "+targetFps+"fps":"关键帧兼容模式")+" · 正在独立扫描仪表："+qr.name); p2p.scan();
        } catch(Throwable e) { stopMirror("启动失败："+e.getMessage()); }
        return START_NOT_STICKY;
    }
    @Override public void onLine(String line) { if(active) publish(line.replace("OTA","投屏")); }
    @Override public void onPeers(List<WifiP2pDevice> list) {
        peers.clear(); peers.addAll(list);
        if(active && !connecting && !handshake) {
            for(WifiP2pDevice d:list) if(qr.name.equals(d.deviceName) || (qr.mac!=null && qr.mac.equalsIgnoreCase(d.deviceAddress))) {
                connect(d); return;
            }
            publish("发现 "+list.size()+" 个设备，等待匹配二维码名称；可点「选择仪表」手动选择");
        }
    }
    private void connect(WifiP2pDevice d) {
        selected=d; addressRetries=0; connecting=true; publish("正在配对："+d.deviceName); p2p.connect(d);
        long current=generation;
        main.postDelayed(() -> { if(active && current==generation && connecting && !handshake) {
            connecting=false; publish("配对尚未完成，请在仪表确认，或停止后重试");
        } },35000);
    }
    @Override public void onGroup(WifiP2pInfo info,WifiP2pGroup group) {
        if(!active) return;
        if(!info.groupFormed) { if(handshake) stopMirror("Wi-Fi Direct 连接已断开"); return; }
        if(handshake || !connecting) return;
        if(group==null) { retryAddress(); return; }
        if(!info.isGroupOwner && selected!=null) {
            WifiP2pDevice owner=group.getOwner();
            if(owner==null || !(selected.deviceAddress.equalsIgnoreCase(owner.deviceAddress)
                    || (selected.deviceName!=null && !selected.deviceName.isEmpty() && selected.deviceName.equals(owner.deviceName)))) {
                publish("现有 P2P 组与所选仪表不匹配，请先在系统中断开旧连接"); return;
            }
        }
        ownsGroup=true;
        if(info.isGroupOwner) { stopMirror("手机成为组主，无法定位仪表 EC 地址；请让仪表停留投屏二维码页后重试"); return; }
        try {
            InetAddress host=info.groupOwnerAddress;
            if(!(host instanceof Inet4Address) || !host.isSiteLocalAddress()) throw new IllegalStateException("仪表地址无效");
            InetAddress local=null;
            NetworkInterface nif=group==null ? null : NetworkInterface.getByName(group.getInterface());
            if(nif!=null) for(InterfaceAddress a:nif.getInterfaceAddresses())
                if(a.getAddress() instanceof Inet4Address && a.getAddress().isSiteLocalAddress()) { local=a.getAddress(); break; }
            if(local==null) { retryAddress(); return; }
            handshake=true; ownsGroup=true;
            publish("P2P 已连接；正在建立 EC/PXC 握手（尚未出画面）");
            final InetAddress source=local; final long current=generation;
            worker.execute(() -> startProtocol(host,source,current));
        } catch(Exception e) { stopMirror("连接失败："+e.getMessage()); }
    }
    private void retryAddress() {
        if(++addressRetries>10) { stopMirror("P2P 地址未就绪，请停止后重试"); return; }
        main.postDelayed(() -> { if(active && p2p!=null && !handshake) p2p.refreshGroup(); },500);
    }
    private void startProtocol(InetAddress host,InetAddress local,long current) {
        try {
            MobileConfig config=Api.newMobileConfig(null,targetFps,25,5,3,1);
            config.setContinuousVideo(smooth);
            MobileSession s=Api.newMobileSession(config,new MobileCallback() {
                public void onError(String message,boolean fatal) { main.post(() -> {
                    if(current!=generation || !active) return;
                    if(fatal) stopMirror("EC 通道中断："+message); else publish("协议提示："+message);
                }); }
                public void onStopped() { main.post(() -> { if(active && current==generation) stopMirror("EC 会话已结束"); }); }
                public void onEvent(long time,long source,long command,byte[] payload) {
                    main.post(() -> {
                        if(!active || current!=generation) return;
                        if(source==5 || (source==3 && command==112)) { if(encoder!=null) encoder.requestSync(); }
                        else if(source==6 && payload!=null) { showPerformance(payload); }
                        else if(source==3 && command==16 && payload!=null && payload.length>=4) {
                            ByteBuffer b=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                            int w=(b.getShort(0)&65535)/16*16,h=(b.getShort(2)&65535)/16*16;
                            if(w<16) w=800; if(h<16) h=384;
                            if(payload.length>=12 && b.getInt(8)!=0 && b.getInt(8)!=2) { stopMirror("仪表请求非 H.264 编码，暂不支持"); return; }
                            publish("仪表已请求屏幕："+w+"×"+h+"；正在启动 H.264 编码"); encoder.configure(w,h);
                        } else if(source==4 && payload!=null) { sentFrames=true; publish("已向仪表发送 "+new String(payload,java.nio.charset.StandardCharsets.US_ASCII)+" 帧；请确认仪表画面"); }
                    });
                }
            });
            if(!active || current!=generation) { s.stopSession(); return; }
            session=s;
            s.configurePhone(Build.BRAND,Build.MODEL,String.valueOf(Build.VERSION.SDK_INT));
            s.setLocalAddress(local.getHostAddress());
            s.setECHost(Api.newStreamHost(host.getHostAddress(),"10930","com.cfmoto"));
            try(Socket socket=new Socket()) {
                initSocket=socket; socket.bind(new InetSocketAddress(local,0)); socket.connect(new InetSocketAddress(host,10930),5000);
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.fromSocket(socket)) { s.startSessionWithSocketFd(fd.detachFd()); }
            } finally { initSocket=null; }
            main.post(() -> { if(active && current==generation) publish("EC/PXC 握手完成；等待仪表拉取视频（尚未确认出画面）"); });
        } catch(Throwable e) { main.post(() -> { if(active && current==generation) stopMirror("独立投屏失败："+e.getMessage()); }); }
    }
    private void showPerformance(byte[] payload) {
        try {
            org.json.JSONObject p=new org.json.JSONObject(new String(payload,java.nio.charset.StandardCharsets.UTF_8));
            double now=SystemClock.elapsedRealtime(), inputs=p.optDouble("inputs",0);
            ScreenEncoder.Snapshot c=encoder==null?null:encoder.snapshot();
            boolean warm=inputAt>0 && c!=null && now>inputAt;
            double seconds=warm?(now-inputAt)/1000:1;
            String raw=warm?String.format(java.util.Locale.ROOT,"%.1f",(c.encoded-previousEncoded)/seconds):"统计中";
            String submitted=warm?String.format(java.util.Locale.ROOT,"%.1f",(c.submitted-previousSubmitted)/seconds):"统计中";
            String input=warm&&smooth?String.format(java.util.Locale.ROOT,"%.1f",(inputs-previousInputs)/seconds):"—";
            if(c!=null) {previousEncoded=c.encoded;previousSubmitted=c.submitted;}
            previousInputs=inputs;inputAt=now;
            String text=String.format(java.util.Locale.ROOT,
                "%s · 目标 %d fps\n同窗编码 %s · 提交 %s · 入队 %s fps\n发送 %.1f fps · 仪表请求 %.1f 次/秒\nTCP 写入 %.1f ms/帧 · %.0f kbps\n队列 %d 帧 / %.0f ms · 丢弃累计 %d 帧\n%s · 距最近输出 %s\n发送率不代表仪表解码/显示帧率",
                smooth?"连续帧":"关键帧兼容",targetFps,raw,submitted,input,p.optDouble("sentFps"),
                p.optDouble("pollFps"),p.optDouble("writeMs"),p.optDouble("kbps"),p.optInt("depth"),
                p.optDouble("ageMs"),p.optLong("drops"),c==null?"等待编码":c.codec,
                c==null||c.lastOutput==0?"尚无输出":String.format(java.util.Locale.ROOT,"%.0f ms",now-c.lastOutput));
            sendBroadcast(new Intent(STATE).setPackage(getPackageName()).putExtra("perf",text));
        } catch(org.json.JSONException ignored) { }
    }
    private void publish(String text) {
        status=text;
        Intent out=new Intent(STATE).setPackage(getPackageName()).putExtra("message",text);
        String[] names=new String[peers.size()], macs=new String[peers.size()];
        for(int n=0;n<peers.size();n++) { names[n]=peers.get(n).deviceName; macs[n]=peers.get(n).deviceAddress; }
        sendBroadcast(out.putExtra("names",names).putExtra("macs",macs));
    }
    private void stopMirror(String reason) {
        if(stopping) return;
        active=false; projectionActive=false; generation++; stopping=true;
        getSharedPreferences("mirror_runtime",MODE_PRIVATE).edit().putString("lastStopReason",reason).apply();
        if(displays!=null) { displays.unregisterDisplayListener(displayListener);displays=null; }
        publish(reason);
        Socket socket=initSocket; if(socket!=null) try { socket.close(); } catch(Exception ignored) {}
        MobileSession s=session; session=null;
        new Thread(() -> { if(s!=null) try { s.stopSession(); } catch(Exception ignored) {}
            main.post(() -> {
                Runnable finish=() -> main.post(() -> {
                    if(projection!=null) { projection.stop(); projection=null; }
                    if(p2p!=null) { if(ownsGroup) p2p.disconnect(); p2p.close(); p2p=null; }
                    ownsGroup=false; connecting=false; handshake=false; stopping=false;
                    stopForeground(true); stopSelf();
                });
                if(encoder!=null) { ScreenEncoder old=encoder; encoder=null; old.close(finish); } else finish.run();
            });
        },"MirrorStop").start();
    }
    @Override public void onDestroy() { if(active) stopMirror("投屏服务已结束"); worker.shutdown(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
