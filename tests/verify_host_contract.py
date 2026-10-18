"""Check the reflected bridge API against the supplied official analysis-only APK.
Usage: python verify_host_contract.py /path/to/official.apk
Requires androguard. This is static API compatibility, not an on-device test.
"""
import sys, zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.axml import AXMLPrinter
checks = {
 'Lcom/cfmoto/motoplay/global/MotoPlayViewModelMgr;': [('getConnViewModel','()Lcom/cfmoto/motoplay/manager/MotoPlayConnViewModel;')],
 'Lcom/cfmoto/motoplay/manager/MotoPlayConnViewModel;': [('connectDevice','(Ljava/lang/String;)V'),('getApplication','()Landroid/app/Application;'),('disconnectDevice','(Z)V'),('stopMirror','()V')],
 'Lcom/cfmoto/motoplay/manager/MotoPlayConnViewModel$mPxcCallback$1;': [('onReceiveClientInfo','(Lnet/easyconn/carman/sdk_communication/C2P/ECP_C2P_CLIENT_INFO;)I')],
 'Lcom/cfmoto/motoplay/manager/MotoPlayConnViewModel$mMediaProjectionListener$1;': [('onProjectionConnectStatusChange','(Z)V'),('requestMediaProjection','(Lnet/easyconn/carman/common/base/OnRequestMediaProjectionCallback;)V')],
 'Lcom/cfmoto/motoplay/manager/MotoPlayConnViewModel$connectDevice$resultListener$1;': [('onError','(ILjava/lang/String;)V'),('onFindDevice','(Ljava/lang/String;)V')],
}
z=zipfile.ZipFile(sys.argv[1]);d=DEX(z.read('classes5.dex'));classes={c.get_name():c for c in d.get_classes()};count=0
for name, methods in checks.items():
 assert name in classes, name
 available={(m.get_name(),m.get_descriptor().replace(' ','')) for m in classes[name].get_methods()}
 for entry in methods:
  assert entry in available,(name,entry)
  count+=1
mgr=classes['Lcom/cfmoto/motoplay/global/MotoPlayViewModelMgr;']
assert any(f.get_name()=='INSTANCE' for f in mgr.get_fields());count+=1
xml=AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
ns='{http://schemas.android.com/apk/res/android}'
assert any(a.get(ns+'name')=='com.cfmoto.motoplay.ui.MotoPlay2MapActivity' for a in xml.iter('activity'));count+=1
print(f'PASS: {count} official host API/manifest contracts; static check only')
