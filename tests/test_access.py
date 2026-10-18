"""Run actual P2pAccess permission/settings flow with framework fakes; no phone test."""
from pathlib import Path
import subprocess, sys, tempfile, xml.etree.ElementTree as ET
from test_discovery import STUBS

EXTRA = {
'android/Manifest.java': '''package android;public class Manifest {public static class permission {
public static final String NEARBY_WIFI_DEVICES="android.permission.NEARBY_WIFI_DEVICES",ACCESS_FINE_LOCATION="android.permission.ACCESS_FINE_LOCATION",ACCESS_COARSE_LOCATION="android.permission.ACCESS_COARSE_LOCATION";}}''',
'android/content/Context.java': '''package android.content;import java.util.*;import android.net.wifi.WifiManager;
public class Context {public static final String WIFI_SERVICE="wifi";public WifiManager wifi=new WifiManager();public boolean location=true;
public Map<String,Integer> grants=new HashMap<>();public Object getSystemService(String s){return wifi;}
public Context getApplicationContext(){return this;}}''',
'android/content/pm/PackageManager.java': 'package android.content.pm;public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
'android/net/wifi/WifiManager.java': 'package android.net.wifi;public class WifiManager {public boolean enabled=true;public boolean isWifiEnabled(){return enabled;}}',
'android/net/Uri.java': 'package android.net;public class Uri {public static Uri parse(String s){return new Uri();}}',
'android/content/Intent.java': 'package android.content;import android.net.Uri;public class Intent {public String action;public Intent(String a){action=a;}public Intent(String a,Uri u){action=a;}}',
'android/provider/Settings.java': '''package android.provider;public class Settings {public static final String
 ACTION_APPLICATION_DETAILS_SETTINGS="app",ACTION_WIFI_SETTINGS="wifi",ACTION_LOCATION_SOURCE_SETTINGS="location";}''',
'android/content/DialogInterface.java': '''package android.content;public interface DialogInterface {
interface OnClickListener {void onClick(DialogInterface d,int w);}interface OnCancelListener {void onCancel(DialogInterface d);}}''',
'androidx/activity/result/ActivityResultCallback.java': 'package androidx.activity.result;public interface ActivityResultCallback<O>{void onActivityResult(O o);}',
'androidx/activity/result/ActivityResultLauncher.java': '''package androidx.activity.result;public class ActivityResultLauncher<I>{
public I input;public int calls;public void launch(I i){input=i;calls++;}}''',
'androidx/activity/result/contract/ActivityResultContract.java': 'package androidx.activity.result.contract;public abstract class ActivityResultContract<I,O>{}',
'androidx/activity/result/contract/ActivityResultContracts.java': '''package androidx.activity.result.contract;import java.util.Map;
public class ActivityResultContracts {public static class RequestMultiplePermissions extends ActivityResultContract<String[],Map<String,Boolean>>{}}''',
'androidx/activity/ComponentActivity.java': '''package androidx.activity;import android.content.*;import androidx.activity.result.*;import androidx.activity.result.contract.*;
public class ComponentActivity extends Context {public ActivityResultLauncher<String[]> launcher;public ActivityResultCallback<java.util.Map<String,Boolean>> callback;public Intent opened;
public String getPackageName(){return "test";} public void startActivity(Intent i){opened=i;}
@SuppressWarnings("unchecked") public <I,O> ActivityResultLauncher<I> registerForActivityResult(ActivityResultContract<I,O> c,ActivityResultCallback<O> cb){
ActivityResultLauncher<I> l=new ActivityResultLauncher<>();launcher=(ActivityResultLauncher<String[]>)l;callback=(ActivityResultCallback<java.util.Map<String,Boolean>>)cb;return l;}}''',
'androidx/core/content/ContextCompat.java': '''package androidx.core.content;import android.content.Context;
public class ContextCompat {public static int checkSelfPermission(Context c,String p){return c.grants.getOrDefault(p,-1);}}''',
'com/github/zeehospeedhunter/ota/CarNet.java': '''package com.github.zeehospeedhunter.ota;import android.content.Context;
public class CarNet {public static boolean isLocationOn(Context c){return c.location;}}''',
'com/google/android/material/dialog/MaterialAlertDialogBuilder.java': '''package com.google.android.material.dialog;import android.content.*;
public class MaterialAlertDialogBuilder {public static MaterialAlertDialogBuilder last;public DialogInterface.OnClickListener positive,negative;public DialogInterface.OnCancelListener cancel;
public MaterialAlertDialogBuilder(Context c){last=this;} public MaterialAlertDialogBuilder setTitle(String s){return this;}public MaterialAlertDialogBuilder setMessage(String s){return this;}
public MaterialAlertDialogBuilder setPositiveButton(String s,DialogInterface.OnClickListener l){positive=l;return this;}
public MaterialAlertDialogBuilder setNegativeButton(String s,DialogInterface.OnClickListener l){negative=l;return this;}
public MaterialAlertDialogBuilder setOnCancelListener(DialogInterface.OnCancelListener l){cancel=l;return this;}public void show(){}
}''',
'com/github/zeehospeedhunter/ui/AccessTest.java': '''package com.github.zeehospeedhunter.ui;
import android.Manifest.permission;import android.os.Build;import androidx.activity.ComponentActivity;import java.util.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
public class AccessTest {static int cases;static void ok(boolean b,String s){if(!b)throw new AssertionError(s);}
static class F {ComponentActivity a=new ComponentActivity();List<String> status=new ArrayList<>();int scans;P2pAccess p;
 F(int api){Build.VERSION.SDK_INT=api;p=new P2pAccess(a,status::add);}void click(){p.run(()->scans++);}void grant(String s){a.grants.put(s,0);}
 void result(){a.callback.onActivityResult(Collections.emptyMap());}void done(String n){p.close();cases++;System.out.println("PASS "+n);}}
public static void main(String[] args){
 for(int api:new int[]{24,30,31,32}){F f=new F(api);f.click();ok(f.scans==0&&f.a.launcher.calls==1,"permission gate API"+api);
 ok(Arrays.asList(f.a.launcher.input).containsAll(Arrays.asList(permission.ACCESS_FINE_LOCATION,permission.ACCESS_COARSE_LOCATION)),"legacy pair");
 f.grant(permission.ACCESS_FINE_LOCATION);f.grant(permission.ACCESS_COARSE_LOCATION);f.result();ok(f.scans==1,"auto resume API"+api);f.done("API "+api+" precise permission -> automatic scan");}
 for(int api:new int[]{33,35}){F f=new F(api);f.click();ok(Arrays.equals(f.a.launcher.input,new String[]{permission.NEARBY_WIFI_DEVICES}),"nearby only");
 f.grant(permission.NEARBY_WIFI_DEVICES);f.result();ok(f.scans==1,"nearby resume");f.done("API "+api+" nearby permission");}
 F approx=new F(31);approx.grant(permission.ACCESS_COARSE_LOCATION);approx.click();ok(approx.scans==0,"approximate must not discover");approx.result();ok(approx.scans==0,"denial leaked");approx.done("approximate-only/denied never starts scan");
 F loc=new F(30);loc.grant(permission.ACCESS_FINE_LOCATION);loc.a.location=false;loc.click();ok(loc.scans==0,"location off");
 MaterialAlertDialogBuilder.last.positive.onClick(null,0);ok(loc.a.opened.action.equals("location"),"location settings");loc.a.location=true;loc.p.onResume();ok(loc.scans==1,"return location");loc.done("location settings -> resume scan");
 F wifi=new F(30);wifi.grant(permission.ACCESS_FINE_LOCATION);wifi.a.wifi.enabled=false;wifi.click();ok(wifi.scans==0,"wifi off");MaterialAlertDialogBuilder.last.positive.onClick(null,0);
 wifi.p.onResume();ok(wifi.scans==0,"unchanged settings must not run");wifi.done("WLAN off / unchanged settings blocked");
 F closed=new F(30);closed.click();closed.p.close();closed.grant(permission.ACCESS_FINE_LOCATION);closed.result();ok(closed.scans==0,"closed callback ran");closed.done("closed page ignores grant callback");
 F ready=new F(30);ready.grant(permission.ACCESS_FINE_LOCATION);ready.click();ok(ready.scans==1&&ready.a.launcher.calls==0,"already granted");ready.done("already authorized starts directly");
 System.out.println("TOTAL "+cases+" permission/settings cases passed (framework fakes)");}}
'''
}

def main():
    repo, jdk = map(Path, sys.argv[1:])
    manifest = ET.parse(repo/'app/src/main/AndroidManifest.xml')
    names = {x.get('{http://schemas.android.com/apk/res/android}name') for x in manifest.findall('uses-permission')}
    assert {'android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION','android.permission.NEARBY_WIFI_DEVICES'} <= names
    print('PASS manifest declares legacy precise/approximate + nearby permissions',flush=True)
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        for p, text in {'android/os/Build.java':STUBS['android/os/Build.java'], **EXTRA}.items():
            f=root/p;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
        source=repo/'app/src/main/java/com/github/zeehospeedhunter/ui/P2pAccess.java'
        subprocess.run([str(jdk/'bin/javac'),'-d',str(root/'classes'),str(source),*map(str,root.rglob('*.java'))],check=True)
        subprocess.run([str(jdk/'bin/java'),'-cp',str(root/'classes'),'com.github.zeehospeedhunter.ui.AccessTest'],check=True)

if __name__=='__main__':main()
