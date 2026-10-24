"""Exercise the actual CarP2p class with deterministic Android framework fakes.
This does not simulate radio discovery and does not prove device compatibility.
Usage: python3 test_discovery.py REPO_PATH JDK_HOME
"""
from pathlib import Path
import subprocess, sys, tempfile

STUBS = {
'android/content/Context.java': '''package android.content;
public class Context { public static final String WIFI_P2P_SERVICE="p2p"; public static final int RECEIVER_EXPORTED=2;
 public Object service; public BroadcastReceiver receiver;
 public Object getSystemService(String s){return service;}
 public Intent registerReceiver(BroadcastReceiver r, IntentFilter f){receiver=r;return null;}
 public Intent registerReceiver(BroadcastReceiver r, IntentFilter f,int flags){receiver=r;return null;}
 public void unregisterReceiver(BroadcastReceiver r){receiver=null;}}
''',
'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Intent.java': '''package android.content; public class Intent {String action; public int state=-1;
 public Intent(String a){action=a;} public String getAction(){return action;}
 public int getIntExtra(String k,int d){return state<0?d:state;}}''',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public void addAction(String s){}}',
'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=30;}}',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 static class Task {Runnable r;long at;Task(Runnable r,long at){this.r=r;this.at=at;}}
 List<Task> q=new ArrayList<>();long now;
 public Object getLooper(){return null;}
 public void postDelayed(Runnable r,long delay){q.add(new Task(r,now+delay));}
 public void removeCallbacks(Runnable r){q.removeIf(t->t.r==r);}
 public void advance(long ms){long end=now+ms;int cap=10000;
  while(--cap>0){Task n=q.stream().filter(t->t.at<=end).min(Comparator.comparingLong(t->t.at)).orElse(null);
   if(n==null)break;q.remove(n);now=n.at;n.r.run();}if(cap==0)throw new AssertionError("timer loop");now=end;}}
''',
'android/net/wifi/WpsInfo.java': 'package android.net.wifi; public class WpsInfo {public static final int PBC=0;public int setup;}',
'android/net/wifi/p2p/WifiP2pConfig.java': '''package android.net.wifi.p2p; import android.net.wifi.WpsInfo;
public class WifiP2pConfig {public String deviceAddress;public int groupOwnerIntent; public WpsInfo wps=new WpsInfo();}''',
'android/net/wifi/p2p/WifiP2pDevice.java': 'package android.net.wifi.p2p; public class WifiP2pDevice {public String deviceAddress,deviceName;}',
'android/net/wifi/p2p/WifiP2pInfo.java': 'package android.net.wifi.p2p; public class WifiP2pInfo {public boolean groupFormed;}',
'android/net/wifi/p2p/WifiP2pGroup.java': 'package android.net.wifi.p2p; public class WifiP2pGroup {}',
'android/net/wifi/p2p/WifiP2pDeviceList.java': '''package android.net.wifi.p2p; import java.util.*;
public class WifiP2pDeviceList {public List<WifiP2pDevice> peers=new ArrayList<>();
 public Collection<WifiP2pDevice> getDeviceList(){return peers;}}''',
'android/net/wifi/p2p/WifiP2pManager.java': '''package android.net.wifi.p2p;
import android.content.Context;
public class WifiP2pManager {
 public static final int ERROR=0,P2P_UNSUPPORTED=1,BUSY=2,WIFI_P2P_STATE_DISABLED=1;
 public static final String WIFI_P2P_PEERS_CHANGED_ACTION="peers",WIFI_P2P_CONNECTION_CHANGED_ACTION="conn",WIFI_P2P_STATE_CHANGED_ACTION="state",EXTRA_WIFI_STATE="wifi";
 public interface ActionListener {void onSuccess();void onFailure(int reason);}
 public interface ChannelListener {void onChannelDisconnected();}
 public interface PeerListListener {void onPeersAvailable(WifiP2pDeviceList peers);}
 public interface ConnectionInfoListener {void onConnectionInfoAvailable(WifiP2pInfo info);}
 public interface GroupInfoListener {void onGroupInfoAvailable(WifiP2pGroup group);}
 public static class Channel {public boolean closed;public void close(){closed=true;}}
 public Channel channel=new Channel();public WifiP2pDeviceList peers=new WifiP2pDeviceList();public WifiP2pInfo info=new WifiP2pInfo();
 public int discovers,stops,requests,connects,cancels,nextError=-1;public boolean throwPeers,throwDiscover;public WifiP2pConfig config;
 public Channel initialize(Context c,Object loop,ChannelListener l){return channel;}
 public void discoverPeers(Channel c,ActionListener l){discovers++;if(throwDiscover)throw new SecurityException("denied");
  if(nextError>=0){int e=nextError;nextError=-1;l.onFailure(e);}else l.onSuccess();}
 public void requestPeers(Channel c,PeerListListener l){requests++;if(throwPeers)throw new SecurityException("revoked");l.onPeersAvailable(peers);}
 public void requestConnectionInfo(Channel c,ConnectionInfoListener l){l.onConnectionInfoAvailable(info);}
 public void requestGroupInfo(Channel c,GroupInfoListener l){l.onGroupInfoAvailable(new WifiP2pGroup());}
 public void stopPeerDiscovery(Channel c,ActionListener l){stops++;if(l!=null)l.onSuccess();}
 public void connect(Channel c,WifiP2pConfig cfg,ActionListener l){connects++;config=cfg;l.onSuccess();}
 public void cancelConnect(Channel c,ActionListener l){cancels++;if(l!=null)l.onSuccess();}
 public void removeGroup(Channel c,ActionListener l){if(l!=null)l.onSuccess();}}
''',
'DiscoveryTest.java': '''import android.content.*;import android.os.*;import android.net.wifi.p2p.*;import java.util.*;
import com.github.speedhunter.ota.CarP2p;
public class DiscoveryTest {
 static int cases;static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 static class Fixture implements CarP2p.Listener {
  Context ctx=new Context();Handler h=new Handler();WifiP2pManager m=new WifiP2pManager();CarP2p p;
  List<String> lines=new ArrayList<>();List<WifiP2pDevice> seen=new ArrayList<>();
  Fixture(){ctx.service=m;p=new CarP2p(ctx,h,this);}
  public void onLine(String s){lines.add(s);}public void onPeers(List<WifiP2pDevice> d){seen=d;}
  public void onGroup(WifiP2pInfo i,WifiP2pGroup g){}
  void done(String name){p.close();System.out.println("PASS "+name);cases++;}}
 public static void main(String[] args){
  Fixture a=new Fixture();a.p.scan();check(a.m.discovers==1&&a.m.stops==0,"scan must not stop/start");
  a.p.scan();a.h.advance(16000);check(a.m.discovers==1&&a.m.requests>=9,"single discovery + polling");a.done("single start, double tap, polling");
  Fixture b=new Fixture();b.m.nextError=2;b.p.scan();check(b.m.discovers==1,"busy immediate retry");b.h.advance(1999);check(b.m.discovers==1,"backoff missing");
  b.h.advance(1);check(b.m.discovers==2&&b.p.isScanning()&&b.m.stops==0,"busy retry failed");b.done("BUSY backoff");
  Fixture c=new Fixture();c.m.nextError=1;c.p.scan();c.h.advance(10000);check(!c.p.isScanning()&&c.m.discovers==1,"unsupported repeated");c.done("unsupported stops retries");
  Fixture d=new Fixture();d.p.scan();d.h.advance(40000);check(!d.p.isScanning()&&d.m.stops==1,"deadline");int n=d.m.requests;d.h.advance(20000);check(d.m.requests==n,"poll leaked");d.done("40-second timeout cleanup");
  Fixture e=new Fixture();e.p.scan();WifiP2pDevice dev=new WifiP2pDevice();dev.deviceName="DIRECT-unknown";dev.deviceAddress="02:11:22:33:44:55";
  e.m.peers.peers.add(dev);e.ctx.receiver.onReceive(e.ctx,new Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION));check(e.seen.size()==1,"name filtered");
  e.p.connect(dev);check(e.m.stops==0&&e.m.connects==1&&!e.p.isScanning(),"connect race");e.done("unbranded peer retained; connect without stop");
  Fixture f=new Fixture();f.p.scan();f.m.throwPeers=true;f.h.advance(2000);check(!f.p.isScanning()&&f.lines.stream().anyMatch(s->s.contains("缺少权限")),"revocation hidden");f.done("revoked permission visible");
  Fixture g=new Fixture();g.p.scan();g.p.close();n=g.m.requests;g.h.advance(60000);check(g.m.requests==n&&g.m.channel.closed,"close leaked");System.out.println("PASS close cancels callbacks");cases++;
  Fixture h=new Fixture();h.p.scan();h.m.info.groupFormed=true;h.p.refreshGroup();h.h.advance(40000);check(!h.p.isScanning()&&h.m.stops==0,"formed group disrupted");h.done("formed group preserved");
  Fixture i=new Fixture();i.p.scan();Intent disabled=new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);disabled.state=1;i.ctx.receiver.onReceive(i.ctx,disabled);
  check(!i.p.isScanning()&&i.lines.stream().anyMatch(s->s.contains("当前不可用")),"disabled hidden");i.done("disabled P2P state visible");
  Fixture j=new Fixture();j.m.throwDiscover=true;j.p.scan();check(!j.p.isScanning()&&j.lines.stream().anyMatch(s->s.contains("缺少权限")),"discover exception");j.done("discovery permission failure stops");
  System.out.println("TOTAL "+cases+" cases passed (framework fakes; no radio test)");
 }}'''
}

def main():
    repo, jdk = map(Path, sys.argv[1:])
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        for p, text in STUBS.items():
            f = root/p
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(text)
        source = repo/'app/src/main/java/com/github/speedhunter/ota/CarP2p.java'
        subprocess.run([str(jdk/'bin/javac'),'-d',str(root/'classes'),str(source),
                        *map(str,root.rglob('*.java'))],check=True)
        subprocess.run([str(jdk/'bin/java'),'-cp',str(root/'classes'),'DiscoveryTest'],check=True)

if __name__ == '__main__': main()
