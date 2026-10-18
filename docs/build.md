# 从源码构建

包名 `com.github.zeehospeedhunter`。

| 项 | 要求 |
|---|---|
| JDK | 17 |
| SDK | compileSdk 35 |
| Gradle | 仓库自带 wrapper（9.7.1） |
| Build Tools | 34.0.0+；**勿写死 35.0.0**（本机仅 34.0.0，写死报缺件） |

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Linux 指向 JDK 17 根目录
./gradlew assembleRelease
# 产物 release/zeeho-speedhunter-v<version>.apk
```

Windows：`gradlew.bat assembleRelease`。国内下载不畅加 `-I tools/mirrors.gradle`。

## 签名

`signing.properties` + `release/release.jks` 不入库；口令齐全才用 release 签名，否则回退 debug。

## `app/libs/ridedaemon.aar`

第三方 Go 协议库预编译产物（含 `libgojni.so`），`mirror/StandaloneMirrorService` 依赖，缺则编不过。

- `.gitignore` 有 `*.aar`，已对该路径开例外 `!app/libs/ridedaemon.aar`；本地规则更宽时用 `git add -f`
- 不想带二进制：Go 1.25.3 + NDK r27c 从 `native/ridedaemon` 编译（`native/build-android.sh` / `.ps1`）
- 不改 `native/` 下 Go 源码则不需要 Go / NDK

## 坑

- JitPack 的 `ucrop-2.2.11.pom` 无 `<dependencies>` 段 ⇒ Gradle 拉不到 `androidx.exifinterface`，运行时崩 ⇒ 须在 `app/build.gradle` 显式声明
- `WifiP2pInfo.groupOwnerAddress` 是 `InetAddress`，不能当 String
