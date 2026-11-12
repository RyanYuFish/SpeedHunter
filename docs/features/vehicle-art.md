# 车模替换（vehicle-art）—— 主页车辆图片 / 3D 模型自由切换

> v4.7.68 (vc121) · 2026-11-12 · Android 实现见 `net.VehicleArtUnlock` + `net.ModelCatalog` + 骑行页卡片；iOS 见 `SpeedHunter-iOS/scripts/ZeehoVehicleModel.js`

## 功能

把 App 主页的车辆视觉换成任意车型/颜色。v4.7.68 起 3D 车模从**目录列表直接选车型**
（lswlc33/zeeho-3d-models，多 CDN 回退），并可在模型加载后应用**车漆配色**
（`changeCarColor` 索引）；2D 车图 / 主题色仍走 URL 填写。

## 逆向依据（v3.0.5 DEX 字段级定案）

反查工具：`dist/nitro-fw/dex_{fields,class,grep,ret}.py`（最小 DEX 解析器，按
string_ids/type_ids/field_ids/method_ids/proto_ids 索引表；`app_full.dex` 的 map_list
损坏、dexdump 拒读，但 id 表完好）。

| 事实 | 证据 |
| --- | --- |
| 主页车辆视觉全部来自 `MyCarHomePageRsp` 的 URL 字段 | getter 表：getVehiclePicUrl / getVehiclePic3dUrl / getVehicleScalePicUrl / getVehicleParkingPhoto / getAppThemeRgb |
| 3D 模型按 URL 加载 | `Lcom/cfmoto/ui/filament/CFFilamentView;` 方法表有 `loadUrl` / `setModel` / `clearModel` / `isCurrentModel` / go2Expanded / go2Collapsed / go2HomePosition |
| 颜色随 glb 内嵌，App 端无独立颜色参数 | `MyCarHomePageRsp` **无 carColorIndex/carColor 字段**；`ZeehoModelProcessor` 持 carColorMaterials[carColorIndex] 车漆材质表 + carLightMaterialON/OFFMap 车灯材质 —— 换 glb 即换颜色 |
| APK 无内置车模 | assets/3d/ 只有 debug_ibl.ktx / debug_skybox.ktx / gradient_alpha.filamat（Filament 引擎材质） |
| 实采字段值（AE5i/AE7） | vehiclePicUrl：AE5i=…/media/1758172630857.png、AE7=…/media/1757672587864.png；vehicleScalePicUrl=同 PNG+`?x-oss-process=image/resize,m_fixed,w_500,h_315`；**两车均无 vehiclePic3dUrl**（服务端未配 3D，当前主页是 2D PNG） |
| 消费方 | MotoHomeFragment / VehicleFragment / CarViewModule(CarData.getCar) / NoNetCacheUtils.loadCarHomePage |

商城流程（选色参考）：`VehicleEditionRsp.getVehicleColors` / `VehicleColorsRsp` 用于
`ui/shop/vehicle/VehicleOrderActivity` + `VehicleSkuActivity`（商城下单选色）；
`CarModelAndColorPopupWindow`（ui/carsetting/popupwindow）+ ItemVehicleColorBinding
是选车型+颜色的弹窗 UI。选中后服务端回对应 URL —— 与本功能「填 URL」等价。

## 改写面

- 递归全树替换（与 NetPatch 同哲学，不按 URL 分流）：`vehiclePicUrl` /
  `vehiclePic3dUrl` / `vehicleScalePicUrl`（= 新 PNG + 原 OSS resize 参数；无参补
  `?x-oss-process=image/resize,m_fixed,w_500,h_315`）。
- 可选：`appThemeRgb`。
- 拦到的接口：`vehicleHomePageV2`、`vehicle/list`（两处都带 vehiclePicUrl）。

## 配置键

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `vehicle_art` | bool | false | 总开关 |
| `vehicle_art_pic` | string | "" | 2D PNG URL（空=不替换 2D） |
| `vehicle_art_pic3d` | string | "" | 3D glb URL（空=不替换 3D） |
| `vehicle_art_rgb` | string | "" | 主题色如 #131415（空=不动） |
| `vehicle_art_color` | int | -1 | 车漆配色索引（-1=关闭；模型加载后 changeCarColor(idx)） |
| `vehicle_art_model_name` | string | "" | 目录模型显示名（仅模块 UI，hook 不消费） |

配置链路与账号键同法：ConfigSender 显式广播，RemoteSettings 端 putAccountString /
putAccountInt 继承式写入 —— 压弯标定等局部广播不会把 URL / 配色冲掉。

## 目录选择器（v4.7.68）

- **目录仓库** `github.com/lswlc33/zeeho-3d-models`（2026-11-12 实测评估可用）：
  `index.json` 清单 `{ver,updated,models:[{id,name,file,size,ver,note}]}`，
  models/*.glb = AE4-MAX2plus（官方原版 6.2MB）+ 3 个第三方趣味模型；
  jsDelivr 实测 HEAD 200、CORS *、content-type model/gltf-binary。
- **CDN 回退**（借 zeehowebnext 面板实现）：jsDelivr → raw.githubusercontent
  → ghfast.top → gh-proxy.com；单文件 >20MB（jsDelivr 硬限）自动跳过 jsDelivr
  走 ghfast 镜像（如 miko-bust 36MB）。
- **加载器** `net.ModelCatalog`：HttpsURLConnection + 8s 超时，逐级回退，
  静态缓存 5 分钟，回调回主线程；拉取失败仍弹选择器（保留跟随云端/自定义 URL）。

## 车漆配色 hook（v4.7.68）

| 事实 | 证据 |
| --- | --- |
| 锚点 `onModelLoaded` 存在 | app_full.dex method_ids：`onModelLoaded in Lcom/cfmoto/ui/filament/ZeehoModelProcessor;` |
| `changeCarColor(I)` 存在 | 同上 method_ids（idx 语义静态不可解 —— proto_ids 区在损坏 map_list 内） |
| 材质表字段 | `carColorIndex:I` + `carColorMaterials:ArrayList`（FIELD 表） |
| 调用模式 | `XposedBridge.hookAllMethods`（签名无关）；CustomKeyForce 证明 com.cfmoto 类在爱加密下可 hook（2026-10-28 实车） |

机制：`onModelLoaded` after → 反射读 carColorMaterials（记 size + 元素类型，
这是「本车型配色槽数」的唯一运行时事实）→ 用户 idx 界内才
`callMethod(processor, "changeCarColor", idx)`，越界只记日志。同时 hook
`changeCarColor` 本身观察 App 自发调用（服务端颜色驱动路径），只记日志不改参。

**首次使用建议**：开启后看 hook 日志 `art color:` 行 —— `materials=N` 即本车型
配色槽数，之后把索引选在 0..N-1 内。`art color observe: app changeCarColor(K)`
能反向定位服务端配色字段的枚举值。

## 官方 2D 素材目录（v4.7.69）

官方 2D URL 形如 `media/<上传时间戳>.png` —— 车型/颜色无法从命名推导，
目录只能实捕扩充。当前 3 条（net.OfficialArt）：

| 条目 | URL 末段 | 来源 |
| --- | --- | --- |
| AE5i Pro · 白 | media/1758172630857.png | 本机账号 netdump vehiclePicUrl |
| AE5i Pro · 深色 | media/1758172641684.png | 社区面板库 mock.json「虚拟车辆1」同批 |
| AE7 Pro · 白 | media/1757672587864.png | 本机账号 netdump vehiclePicUrl |

均 1125×708，appThemeRgb 全 #131415。扩充：抓另一辆车主页响应的 vehiclePicUrl，
往 ENTRIES 加行即可。

## 本地车模上传（v4.7.69）

AssetServer 跑在官方 App 进程（MainHook 启动时 `AssetServer.start(dataDir)`），
绑 127.0.0.1:8979-8983。模块 UI 选文件 → cacheDir 暂存 → 依次探测端口 /ping →
POST /upload?name=X → 落 `files/speedhunter_art/` → 返回
`http://127.0.0.1:PORT/f/<name>` 写进车模配置。

明文 HTTP 能被官方 App 加载器接受：其 network_security_config 是
`cleartextTrafficPermitted="true"`（apktool-v3recovery 取证）。
安全边界：只绑 loopback；文件名白名单 [A-Za-z0-9._-]；80MB 上限；.part 原子改名。

## URL 从哪来

1. 官方素材选单（v4.7.69 起骑行页直接选）。
2. 另一辆车主页抓包的 `vehiclePicUrl`（oss-cfmoto.zeehoev.com media PNG）。
3. zhoodash `cmdModels` 下载的 .glb（车模库）对应的可访问 URL。
4. 本地上传（v4.7.69 起）：不依赖外网，官方 App 进程内自取。

## 边界

- 纯显示实验：只改 App 读取的响应 JSON，不写任何车辆身份字段（vehicleType /
  regulationType 不碰）。
- 关闭开关或清空 URL = 完全不触碰响应（零改动）。
- 生效时机：改完重启官方 App（主页数据在冷启动/车辆切换时拉取）。
