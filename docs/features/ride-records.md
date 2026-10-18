# 骑行记录恢复

| gate | 字段 | 原值 → 改值 | 控制 |
|---|---|---|---|
| G1 | `vehicleKinds` | `1` → `2` | 骑行分析入口 + 极速 |
| G2 | `cyclingEventStatisticFlag` | `false` → `true` | 急加速 / 急刹 / 压弯 |

- hook `Response$Builder#build()`：按响应体 key 判定，不按 URL 过滤，递归不限层级
- 数值为服务端真值；不改数据字段
- 不改车型标识
- 旧方案（点亮 `GONE` 控件）保留，默认关

源码：`ride/RideHooks`、`ride/RideFetch`、`ride/RideConfig`、`core/RemoteSettings`
