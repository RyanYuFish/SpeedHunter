# OTA 流量记录（只读）

| 开关 | 默认 | 作用 |
|---|---|---|
| `LOG_HTTP` | 开 | okhttp 请求 |
| `LOG_RESPONSE` | 开 | 响应体，OTA 接口族全打 |
| `LOG_DOWNLOAD` | 开 | 固件包：URL + 体积 + Content-Type |
| `LOG_WEBVIEW` | 开 | WebView OTA 说明页地址 |
| `LOG_SERVICE` | 开 | OTA 服务 Intent extras |

- 只读，不改写响应
- `fileInfoList` 为 `null` 时无下载动作

源码：`net/MqttHooks`、`net/NetPatch`、`core/HookLog`
