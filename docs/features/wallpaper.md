# 仪表壁纸推送（`socket_l` 10950）

## 协议

| 方向 | 命令 | 说明 |
|---|---|---|
| →车机 | `0x10000011` versionMessage | 车机回 `0x80000011 {"versionCode","vin","code"}` |
| →车机 | `0x10000043` wallpaperStartMessage | `{fileName, wallpaperNum, displayWallpaperSet, md5, fileSize}` |
| →手机 | `0x80000043 {"code":N}` | N=1 = 校验通过，随后 `startWallpaperByIndex()` |
| →车机 | `0x10000044` wirtewallpaperData | 数据帧（head[1]=0），1MB/片 |
| →车机 | `0x10000045` wallpaperEndMessage | `{recvWallpaperFileFinishedFlag:1}` |
| →手机 | `0x80000045` / `0x10000046` | 回执 / 校验请求 |
| →车机 | `0x80000046` wallpaperVerifyMessageAck | 不回则车机反复重发 |

车机收 end 后：`md5_file` → `unZIPWallPaperFile()` → `signalUseWallpaper()` → DASH 写 `persist.sys.wallpapername`。

## 包格式

```
wallpaper.zip
├── 1.png  307×460  RGBA   小图 / card
└── 2.png  1024×600 RGBA   大图 / 背景
```

- `wallpaperNum` 只能 1 或 2（固件 `stoi` + `(v-1U)<2`）
- `displayWallpaperSet` 非空，默认 `"1"`

## 选图 → 裁切 → 打包

1. 选大图 → 裁 `1024×600` → `2.png`
2. 选小图 → 裁 `307×460` → `1.png`
3. 打包推送 → 自动打 `wallpaper.zip`

设了哪张推哪张；`wallpaperNum` 自动推导。

```bash
adb shell am start -n com.github.speedhunter/.ui.MainActivity \
    --ei extra_tab 2131296584 \
    --es auto push --es file <zip> --es num 2 [--ez dry true]
```

> **v4.0+ 入口已变**：单 `MainActivity` + Fragment，`WallpaperActivity` 不存在。
> `--ei extra_tab` 传 `R.id.nav_wallpaper` 的数值（**v4.6 实测 `2131296584`**；资源变动后用
> `aapt2 dump resources <apk> | grep id/nav_wallpaper` 复核）。extras 由宿主转发给
> `WallpaperFragment`（`auto=push` / `file` / `num` / `dry`）。

## 边界

- 10950 单逻辑客户端，官方 App 占用时连不上
- 仪表不回报应用结果，以屏幕为准
- 只推壁纸 zip，不涉及 OTA 包

源码：`ui/WallpaperActivity`、`ota/WallpaperSocketPusher`
