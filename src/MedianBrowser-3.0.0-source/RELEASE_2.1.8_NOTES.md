# MedianBrowser 2.1.8

- `versionCode`: 72
- `versionName`: 2.1.8
- `applicationId`: `com.xinyv.median.compat`（保持原包名，可覆盖升级）
- 使用 Median Browser 稳定更新密钥签名。

## 快速与稳定性改进

- 新增网站兼容模式及受控的失败重试提示。
- 缩短 WebView 请求拦截热路径，缓存站点过滤状态。
- 用户脚本值与 Cookie 改为合并异步落盘，减少主线程和磁盘抖动。
- WebView 预热在内存压力后退避。
- 改进 renderer 崩溃后的整组安全恢复。
- 修复兼容模式状态残留、重复提示及 SSL 子资源误判。
