# MedianBrowser 2.1.9

- `versionName`: 2.1.9
- `versionCode`: 73
- 原包名：`com.xinyv.median.compat`
- 使用稳定更新证书签名，可覆盖 2.1.8。

## 修复

标签页切换器不再依赖 `ListView.onItemClick()`。每张标签卡片直接处理点击和长按，关闭按钮不再抢占焦点，并在点击时按标签对象重新定位当前索引，避免列表复用或标签变化导致失效。
