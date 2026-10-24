# SpeedHunter 3.5.2-mirrorfix

基于上游 a0bb0b925e69218d6faaacdd419f523d868ba811（3.5），合入此前3.5.1-p2pfix，再修复投屏入口。

## 安装与使用

1. 安装 `speedhunter-v3.5.2-mirrorfix.apk`。与此前本对话提供的3.5.1-p2pfix使用相同测试签名，可覆盖升级。其他来源/原始3.5的签名可能不同，不保证覆盖兼容。
2. 在LSPosed中启用模块、勾选官方 App 作用域（com.cfmoto）。更新模块后结束官方App并重新打开一次，使新Hook加载。
3. 官方App开启Wi-Fi，授予定位权限（Android11同时开启系统定位）或附近Wi-Fi设备权限（适用版本）；关闭手机个人热点。
4. 仪表进入手机互联/投屏二维码页。复制完整二维码URL到模块的“仪表投屏”，保留name、action、mac等参数，点击解析，然后开始。
5. 模块收到宿主确认后切换到官方App，由官方MotoPlay页面初始化SDK，发起Wi-Fi Direct连接。按提示完成系统配对、车辆授权及屏幕捕获授权。
6. 返回模块可“查看官方投屏状态”。最终成功以仪表实际画面为准。停止按钮调用官方停止镜像和断开连接，取消模块尚未提交的请求。

第一次打开模块若提示未收到宿主回应，先打开一次已勾选作用域的官方App，再返回重试。这是宿主广播接收器尚未加载，不代表仪表没有P2P功能。

## 修复内容

- 不再把二维码压缩成SSID/密码；完整URL原样传给官方业务逻辑。
- 正确识别action中的P2P位0x08，允许action9；支持有name的旧式action=wifi二维码。
- 替换按Activity名称打分的猜测入口，明确走官方MotoPlay2MapActivity及MotoPlayConnViewModel.connectDevice(qrUrl)。
- 不盲目构造ViewModel、不重设官方生命周期owner、不直接注入Frida。
- 官方App的定位/附近设备权限由宿主进程检查，避免错把模块权限当成宿主权限。
- 通过前台官方Activity启动私有页面，避开从后台广播直接启动私有页的限制。
- 增加宿主确认、目标发现、EC身份交换、屏幕授权、投屏通道、错误/超时状态。
- 回调缺失或App版本不匹配时报错，并撤销本次部分安装的回调Hook。
- 移除页面中的猜测EC握手探针和“先连普通热点就能投屏”的提示。旧探针源码保留供历史参考，投屏页不再调用。
- 停止按钮执行真实官方API；生命周期重建后可查询已有状态，重复点击不重复发起连接。
- 保留3.5.1-p2pfix的定位权限、系统定位检查、扫描BUSY退避及重试修复。
- 修正构建Wrapper至Gradle8.7（AGP8.6.1、JDK17），明确Build Tools35.0.0。

## 验证记录

- Release编译及APK v2签名验证通过；证书与3.5.1-p2pfix一致。
- 完整二维码兼容性/错误输入测试17项通过。
- 官方样本静态API/Manifest契约12项通过。
- 上一版P2P扫描框架模拟10项、权限/设置模拟11项通过。
- 完整Lint任务已完成；上游abortOnError=false保留。报告仍有17条Error和386条Warning，集中在既有BLE、网络权限、旧API、通知及资源代码；投屏桥接、投屏页面及其广播接收代码没有Error级发现。详见LINT_SUMMARY.txt。不能把Gradle任务成功理解为整个项目Lint零问题。
- 本页不输出二维码密码；官方SDK既有日志可能记录完整二维码，分享日志前需脱敏。

## 适配范围与验证边界

静态适配依据是已上传的官方Android3.0.5部分脱壳样本；已核对所需页面、ViewModel、方法签名及回调共12项。其他版本需实际验证，代码会在缺少已知页面/接口时明确报错。

本版接入官方导航投影流程，没有强制切换trueMirror或伪造仪表能力位。任意App整屏镜像仍取决于官方App及仪表支持。

本环境没有连接Android手机和仪表；编译/签名/静态接口及框架模拟测试不等于实车连接和视频传输验证。没有修改车辆固件。

## 源码与构建

`mirrorfix.patch`为相对上述上游提交的完整源码差异，包含新增类、测试、上一版P2P修复和本说明。克隆 https://github.com/RyanYuFish/SpeedHunter 并切到上述提交，执行 `git apply mirrorfix.patch` 后即可导入Android Studio。包内另附测试脚本与上游许可证；不包含任何签名私钥、用户二维码、机器路径或Gradle缓存。

JDK17、Android SDK35、Build Tools35.0.0；执行 `./gradlew :app:assembleRelease`。默认仓库为上游配置。重建默认测试签名不等于本次已发布测试证书，如需覆盖自己的版本请使用自己的原签名。

`tests/MirrorRequestTest.java`可用JDK直接运行；两个P2P测试脚本使用Android框架替身，不能模拟无线电。`tests/verify_host_contract.py`需要androguard及官方分析APK，验证静态接口而非实际连接。
