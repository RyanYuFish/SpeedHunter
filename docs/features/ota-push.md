# OTA 推送控制台

## 流程

```
进 OTA Update 窗口（10086 常开）
 → Wi-Fi Direct 建组取组主
 → SAF 选包
 → 六项检查（任一不过 ⇒ 禁推）
 → 确认弹窗
 → 推裸 GEMINI_PACK.BIN 到 10086
     ① 十进制长度
     ② 文件名        ★ 与①之间、②之间需秒级间隔
     ③ 文件体（1MB 分块）
     ④ 3 字节 "OTA"  ★ 与③之间同样需隔开
 → 仪表进度条 → 写完关机 → 上电
```

## 六项检查

车机「升级包校验失败」只覆盖 ①；②~⑥ 由 U-Boot 执行，**失败无提示**（进度条停住）⇒ 推送前必须验完。

| # | 检查 | 失败后果 |
|---|---|---|
| ① | 内层 `MD5SUM*` = `md5(pack[0x400:])` | 提示校验失败，未开始刷 |
| ② | main 脚本 uImage `dataCRC` + `headerCRC` | 只擦不写 |
| ③ | 分区表 MD5（核对识别数量） | 半刷 |
| ④ | main 脚本内硬编码 MD5（核对识别数量） | 半刷 |
| ⑤ | SPAPP SquashFS superblock | 挂载失败 / 失 +x |
| ⑥ | GD32_APP S-record 记录校验 | MCU 编程拿到垃圾数据 |

判定只看六项是否全过，与原厂无关。

## 改包同步四处（缺一必砖）

1. 分区表 MD5
2. 脚本 74 个硬编码 MD5
3. uImage `data CRC`（`+0x18`）+ `header CRC`（`+0x04`）
4. `MD5SUM*`（最后）

镜像起点 `off+0x400`；校验点按出现顺序配对。
`fix_pack_md5.py` 表基址 `0xC80`，`repack-spapp.py` 用 `PT=0x0C00`（同表编号差 1）⇒ 分区级校验用 `parse_table`。

## 红线

电量 ≥ 25%、非充电、非 Ready；不断电 / 不动车 / 不关 Wi-Fi；
疑在刷写中**禁止反复上电**（串口 `ttyS0 @921600`，`ispsp update usb|mmc|nvm 0|1|2`）。

源码：`ui/OtaActivity`、`ota/OtaPackVerify`、`ota/OtaPushService`、`ota/OtaSocketPusher`、`ota/OtaP2pToolPusher`
