# SpeedHunter 3.5.3-qrscan

在3.5.2-mirrorfix基础上增加设备相机扫码，保留P2P及官方投屏修复。

## 使用

覆盖安装新APK。打开“仪表投屏”，点击“使用相机扫描二维码”，首次使用允许SpeedHunter访问相机；对准仪表投屏页二维码。识别后自动返回，将完整URL填入输入框，并解析P2P设备名称、action、SSID、MAC和密码是否已读取。确认信息后点击“开始官方Wi-Fi Direct投屏”。扫码只填入信息，不自动连接车辆。

取消扫码或识别到无效二维码时保留原来的URL及连接信息；也可以继续手动粘贴并解析。相机权限被拒绝时提供应用设置入口；没有相机的设备仍可使用粘贴入口。

## 实现

- 内嵌ZXing Android Embedded 4.3.0，使用ScanContract/ScanOptions，限定QR_CODE格式，不依赖另外安装扫码App。
- 自定义私有竖屏CaptureActivity，由库管理相机预览和释放。
- 显式申请相机运行时权限；相机硬件声明为可选，兼容没有相机的设备。
- 关闭识别提示音和二维码照片保存，保留原始URL，不把扫描内容当浏览器链接打开。
- 扫描结果先通过原投屏URL校验，再替换输入、保存连接信息，避免扫错覆盖旧信息。
- 密码只显示“已读取”，完整二维码仍在URL输入框内，请注意其中可能包含车辆凭据。

## 验证

Release编译、APK签名验证通过。与3.5.2-mirrorfix及此前3.5.1-p2pfix证书相同，可覆盖升级。

17项原二维码解析/错误输入测试通过；另有9项实际QR编码→解码→生产URL解析测试通过，覆盖完整URL不变、密码特殊字符、中文名称、SSID加号、连接摘要及无效二维码拒绝。

完整Lint已执行。新增扫码页面、CaptureActivity和解析代码无Error级发现；上游其他代码仍有17条Error，未扩大修改范围，详见包内记录。没有接入实体Android相机或车辆，首次相机权限、预览及实车投屏需设备验证。

## 源码

上游：https://github.com/RyanYuFish/SpeedHunter
基准提交：a0bb0b925e69218d6faaacdd419f523d868ba811。

扫码补丁包内speedhunter-3.5.3.patch是相对此基准的完整差异，含此前P2P和投屏修复，新增扫码类、测试及说明。源码未推送到远程仓库。执行git apply后，使用JDK17、SDK35、Build Tools35.0.0及Gradle8.7构建。

可运行tests/MirrorQrDecodeTest.java验证二维码解码。测试需要ZXing core 3.4.1依赖，与本次APK一致。
