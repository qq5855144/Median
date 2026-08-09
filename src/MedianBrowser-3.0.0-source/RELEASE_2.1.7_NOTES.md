# MedianBrowser 2.1.7

本包是 2.1.6 优化修正版的正式递进版本。

- versionName: 2.1.7
- versionCode: 71
- 保留正优化：锁粒度缩小、订阅更新队列保护、广告规则 HashSet 去重。
- 修正潜在负优化：URL 比较增加 normalizeUrl 兜底；手动订阅刷新不会被自动刷新吞掉。

