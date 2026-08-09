# Median Browser 2.1.6 Positive1 更新包

这个包基于 `MedianBrowser-2.1.6-optimized-source`，保留正向优化，并把可能有负优化风险的点改成更保守的正优化。

## 一键构建签名更新 APK

把 `MedianBrowser-signing-backup.zip` 放在本目录、上一级目录，或直接传路径：

```bash
./build-update.sh /path/to/MedianBrowser-signing-backup.zip
```

生成文件：

```text
out/500kb/MedianBrowser-2.1.7.apk
```

要求本机已安装 Android SDK / Build Tools，并设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。

## 版本信息

- `applicationId`: `com.xinyv.median.compat`
- `versionCode`: `70`
- `versionName`: `2.1.7`

`versionCode` 已经高于 2.1.6 的 `69`，用于 Android 原地更新。
