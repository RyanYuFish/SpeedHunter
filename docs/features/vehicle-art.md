# 车模替换（vehicle-art）—— 主页车辆图片 / 3D 模型自由切换

> v4.7.67 (vc120) · 2026-11-12 · Android 实现见 `net.VehicleArtUnlock` + 骑行页卡片；iOS 见 `SpeedHunter-iOS/scripts/ZeehoVehicleModel.js`

## 功能

把 App 主页的车辆视觉换成任意车型/颜色：用户填 2D 车图 URL（PNG）与/或 3D 车模 URL（glb），
开启后官方 App 主页按新 URL 渲染。可选主题色（`appThemeRgb`）跟随。

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

配置链路与账号键同法：ConfigSender 显式广播，RemoteSettings 端 putAccountString
继承式写入 —— 压弯标定等局部广播不会把 URL 冲掉。

## URL 从哪来

1. 另一辆车主页抓包的 `vehiclePicUrl`（oss-cfmoto.zeehoev.com media PNG）。
2. zhoodash `cmdModels` 下载的 .glb（车模库）对应的可访问 URL。
3. 自己上传到任意可公开访问的图床 / OSS。

## 边界

- 纯显示实验：只改 App 读取的响应 JSON，不写任何车辆身份字段（vehicleType /
  regulationType 不碰）。
- 关闭开关或清空 URL = 完全不触碰响应（零改动）。
- 生效时机：改完重启官方 App（主页数据在冷启动/车辆切换时拉取）。
