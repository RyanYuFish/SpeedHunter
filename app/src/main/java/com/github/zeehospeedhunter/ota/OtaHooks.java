package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

/** OTA 区块的装配入口：按 {@link OtaOptions} 里的开关依次安装各条 hook。 */
public final class OtaHooks {

    private OtaHooks() {
    }

    public static void installAll() {
        try {
            if (OtaOptions.LOG_HTTP) OtaTraffic.hookRequests();
            if (OtaOptions.LOG_RESPONSE) OtaTraffic.hookResponseBodies();
            if (OtaOptions.LOG_DOWNLOAD) OtaTraffic.hookDownloadProbe();
            if (OtaOptions.LOG_WEBVIEW) OtaTraffic.hookWebView();
            if (OtaOptions.LOG_SERVICE) OtaTraffic.hookServices();
            if (OtaOptions.DISCOVER_CLASSES) OtaTraffic.hookClassDiscovery();
            // ★ 已移除：OtaPageUnlock（界面解锁）/ OtaInject / OtaMmiInject / OtaForceUpdate。
            //   它们只为「让官方 App 的 OTA 界面显示出来并走完流程」服务；
            //   现在写包走自建的 10086 推送台（ui/OtaActivity），官方 App 的 OTA UI 已无用，
            //   且注入类默认是开的，留着只会无谓改写服务端响应。
            // ★ 不再装 OtaPushUi：推送面板已改为独立界面 OtaPushActivity。
            //   理由见该类 javadoc —— 官方 App 进程实测抓不到 10950 流量（hook 抓包路线
            //   已被证伪），且挂在别人的 Activity 里扛不住 106MB 长传输。
            HookLog.log(HookLog.OTA + " ota hooks installed");
        } catch (Throwable t) {
            HookLog.log(HookLog.OTA + " install failed: " + t);
        }
    }
}
