# Median Browser 2.1.6

Median Browser 是面向 Android 8.0 及以上系统的轻量 WebView 浏览器。本仓库提供可审计源码、Gradle 构建、Google Play AAB 输出和 GitHub Release 自动化。

> 当前状态：**Release Candidate**。源码已完成静态安全加固，但仍必须经过 GitHub Actions、真机矩阵、Google Play 预发布报告和封闭测试后，才能标记为稳定版。

## 主要能力

- 多标签、标签冻结、会话恢复、书签、历史、站点设置与离线 MHTML。
- 广告过滤订阅、元素隐藏、跟踪参数清理。
- 用户脚本、权限声明、菜单命令和受限 GM 网络请求。
- 独立进程隐私窗口；无法可靠隔离时拒绝伪无痕降级。
- Android Keystore 密码库与密码加密完整备份。
- Median 内部单连接下载、断点续传、自动重试、真实进度和 APK 类型修正。
- 阅读模式、系统朗读、翻译、页内查找、桌面模式和媒体发现。
- 性能、标准和低功耗三档资源调度。


## 2.1.6 Logo 与主页设置交互修复

- 自定义文字代码只在点击“保存”后写入，取消不会改变原配置。
- 自动修复 2.1.5 泄漏的“我的主页”示例冲突。
- 个性化主页使用主标题、状态说明和进入箭头，页面布局总览不再显示“紧凑”后缀。
- applicationId 保持 `com.xinyv.median.compat`，versionCode 为 69。

## 2.1.5 主页定制重构

- 个性化主页从二十多个散乱条目收拢为页面布局、每次打开、Logo、搜索框、背景、快捷网站和自定义主页七个分区；进入设置时在上方显示本地主页预览。
- Logo 类型统一支持默认 Median、自定义文字、图片和无；文字可独立设置内容、配色、字号、字重、字距与渐变方向，图片可设置宽度、高度和圆角。
- 新增 Via 风格的自定义 CSS：内置搜索、时钟和快捷网站继续工作，CSS 只覆盖外观；提供常用选择器和示例，禁止外部 URL 与 `@import`。
- 完整 HTML 模式保留，并允许本地 JavaScript；脚本运行在无同源、无联网、无 Median 内部权限的沙箱中，用户点击的顶层链接仍可正常打开。
- HTML/CSS 编辑框改为有边界的紧凑滚动区域，不再用大块无意义的空白撑满屏幕。

## 2.1.4 主页启动与自定义 HTML

- “个性化主页”的第二项新增“每次打开”：可选择打开本地主页、把任意有效 HTTP(S) 网页设为主页，或在冷启动时保留上一次访问的标签内容。
- 自定义网页模式会统一作用于冷启动、主页键和新建标签页；未写协议的有效域名自动补全为 HTTPS，无效关键词不会被误当成网址。
- 列表最后新增“自定义主页”，可在本机编写最多 64 KB 的 HTML 与内联 CSS；壁纸继续由独立壁纸设置控制。
- 自定义 HTML 在无脚本、无同源权限的沙箱中显示，不能调用 Median 内部主页协议；普通链接通过用户点击进入正常浏览页面。
- 新启动策略、自定义网址和 HTML 均进入加密完整备份；旧版“恢复上次标签”设置会自动迁移，不清除现有标签、书签或主页配置。

## 2.1.3 真实下载进度

- WebView 提供的文件总大小不再被丢弃，任务创建后即可显示 `百分比 · 已下载 / 总计`。
- 下载响应的 `Content-Length`、`Content-Range` 及常见文件大小响应头会立即校正总大小，不再等下一轮慢速持久化。
- 进度条加粗并使用 0.1% 精度；速度、预计剩余时间约每 0.75 秒更新，任务重启或遥测暂时缺少大小时不会把已知总大小清零。
- 对真正采用未知长度流式传输的服务器，明确显示“总大小未知”，同时继续显示已下载字节和速度，不伪造百分比。

## 2.1.2 Logo 字距与渐变排版

- “个性化主页”新增全局 Logo 字间距，可在 `-3 px` 到 `+10 px` 之间选择，默认使用正常字距，不再强制挤压字母。
- 自定义 Logo 代码支持普通空格以及 `[space=0–24]` 精确局部间隔，例如 `Med[space=4]ian`。
- 新增横向、纵向和两种对角线渐变方向；极光、日落、海洋配色重新设计，并新增玫瑰金预设。
- 字距、渐变方向及代码均进入完整备份/恢复，恢复默认主页也会一并正确重置。

## 2.1.1 彩色与代码 Logo

- 文字 Logo 新增 Median 经典、Google 官方配色、极光、日落、海洋渐变和自定义代码六种模式。
- 自定义代码采用安全标记语法，不接收或执行任意 HTML/JavaScript；编辑器会即时校验闭合标记、颜色、嵌套、长度和可见文字。
- Google 示例可在编辑器中一键填入，也可以逐字设置颜色：

```text
[color=#4285F4]G[/color][color=#EA4335]o[/color][color=#FBBC05]o[/color][color=#4285F4]g[/color][color=#34A853]l[/color][color=#EA4335]e[/color]
```

- 2–4 色渐变示例：

```text
[gradient=#8B5CF6,#6366F1,#22D3EE]Median[/gradient]
```

- 标记以外的普通文字会自动转义；单色仅接受 `#RRGGBB`，可见文字最多 48 个字符，代码最多 1024 个字符。

## 2.1.0 个性化主页

- 主菜单新增“个性化主页”，可从系统图片选择器设置本地壁纸和 Logo 图片。
- 支持自定义标题、副标题、六种强调色、壁纸遮罩、模糊、填充/完整显示与居中/紧凑布局。
- 支持主页时钟、纯色/磨砂/隐藏搜索框、搜索引擎按钮、快捷网站和左上角品牌独立显隐。
- 快捷入口支持 3–5 列及圆角、圆形、小圆角三种形状，并可直接进入书签管理。
- 图片在后台完成方向修正、尺寸限制和压缩后保存到应用私有目录；主页通过内部资源流读取，不上传、不嵌入 APK，也不把 Base64 大图塞入 HTML。
- 一键恢复默认只重置主页外观并移除自定义图片，不删除书签。

## 2.0.2 菜单点击反馈修复

- 主菜单及其共享功能面板的整行点击波纹改为有边界样式。
- 点击反馈只显示在当前菜单条目内，不再扩散成覆盖多行的巨大圆形色块。
- 小尺寸工具栏图标继续使用无边界波纹，保持正常触控反馈。

## 2.0.1 地址栏判定修复

- 首页搜索框与顶部地址栏统一使用同一输入判定器。
- `12`、纯数字、普通词语和含空格文本进入所选搜索引擎，不再被拼成 `https://12`。
- 完整 HTTP(S) 地址、有效域名、合法 IPv4/IPv6 和 `localhost:端口` 直接作为网站打开。
- 地址/搜索分类拥有独立纯 Java 回归测试，覆盖数字、小数、关键词、域名、端口、IP、邮箱和非 HTTP 协议。

## 2.0.0 全浏览器重构

- 标签页总览改为可回收列表，不再一次创建最多 64 个完整卡片；固定标签也受热 WebView 上限约束，避免固定多个页面后渲染器失控增长。
- 冷标签的 WebView 状态按设备内存与性能档分级保留；后台取消重复预热并释放备用 WebView，重新前台后再按需建立。
- 会话快照只有发生实际变化才写入，浏览数据文件使用 dirty 状态合并落盘；无变化的退后台不再重复序列化整份历史与书签。
- 用户脚本 URL 匹配、GM 值和广告外观规则增加有界缓存；脚本安装、更新及网络请求统一进入可回收、有界线程池。
- 普通与隐私 WebView 共用同一安全基线；隐私窗口支持同一私有进程内反复打开，并补齐 Cookie 回调丢失白屏兜底、前后台暂停和完整销毁。
- 内部下载队列设为有界；服务被系统中止后可重投原任务并依据游标、ETag/Last-Modified 继续。实时进度留在内存，下载索引每 5 秒合并写入，完成、失败和暂停立即落盘。
- applicationId 仍为 `com.xinyv.median.compat`，versionCode 为 60，并沿用 1.7.3 的发布证书，可直接覆盖升级且不清除浏览数据。

## 1.7.3 下载链路重构

- 新下载不再先发 `bytes=0-0` 探测请求，首个成功响应直接用于传输；一次性链接不会在真正下载前被额外消耗。
- 断点重试永远从原始地址重新获取重定向，避免复用已经过期的 CDN 临时地址；服务器忽略或拒绝 Range 时复用完整响应或只降级一次。
- 暂停、取消会主动断开连接，不再等待最长 45 秒读超时；排队、连接中和传输中使用同一控制状态。
- 每一跳重定向都会接收 `Set-Cookie`，目标域只携带自己的实时 Cookie，不会把来源站 Cookie 泄漏给 CDN。
- 下载记录改为进程内一致缓存，不再每秒反复解析最多 500 条 JSON；下载线程空闲 30 秒后退出。
- 恢复按钮立即把任务切到“正在恢复”并继续刷新，前台服务启动失败也会落成可读错误，而不是假装下载中。

## 1.7.2 经典嗅探回退与稳定下载

- 媒体发现恢复到 1.5.1 的通用直链规则，彻底移除后续 YouTube、播放器响应、`videoplayback`、itag 与 SABR 实验代码。
- 下载中心保留轻量列表重写：仅活动任务刷新、复用列表行和按钮、批量清理记录，避免任务多时卡顿或重复绑定。
- 新任务拦截正在下载、暂停及刚失败的相同地址，避免一次点击堆出多条重复记录。
- 每次请求都会刷新当前 Cookie；同源携带完整 Referer，跨源仅携带来源 Origin，兼顾登录态、防盗链和隐私。
- 401/403/404/410 等永久错误立即停止；408/429/5xx 与网络中断才自动重试，避免无意义地重复请求。
- 暂停和取消可控制尚在等待队列的任务；应用进程被系统结束后，残留任务会安全转为“可继续”，不会永久假装下载中。
- 仍保持 Median 内部单连接下载、断点续传、最终文件名/MIME 修正和 APK 结构识别，不调用 Android DownloadManager。

## 1.5.1 Median 内部下载基线

- 所有新任务均由 Median 自己下载，不再创建 Android DownloadManager 任务。
- 固定单连接和 64–128 KB 缓冲，不主动分段，避免服务器拒绝分段与高内存开销。
- 中断续传时才尝试 Range；服务器不支持续传时自动从头重试。
- Cookie、User-Agent 与同源 Referer 随请求传递；跨源重定向会剥离 Cookie 等凭据。
- 下载中心只显示文件、真实进度、速度、状态和常用操作，不展示内部引擎或性能参数。
- 1.5.0 遗留的未完成系统任务会自动停止并转成 Median 下载；已完成文件保持可打开。

## 1.5.0 稳定下载架构

- 普通下载默认交给 Android DownloadManager，获得系统级断点、网络切换、重试、通知和重启恢复。
- Cookie、User-Agent 与同源 Referer 随任务传递，减少登录态、防盗链下载失败。
- 文件名或 MIME 不确定时先读取最终 HTTP 响应；APK 即使被标成二进制也会按结构修正为 `.apk`。
- 兼容引擎最多 6 连接、16 MB 全局预算、2 个工作任务；服务器拒绝分段时自动切换单连接。
- 下载目录修正为 `Download/Median`，同名任务自动编号，系统失败任务可一键转入兼容下载。

## 1.4.3 OEM 下载发布兼容

- 系统 MediaStore 拒绝 APK 时自动保存到应用下载区，并通过只读 ContentProvider 打开或分享。
- 已完成的数据不再因系统下载目录发布失败而被标记为下载失败。

## 1.4.2 下载兼容性修复

- 分段请求被服务器拒绝时自动切换为普通单连接下载，不再直接失败。
- 使用最终 HTTP 响应和 APK 文件结构修正文件名、扩展名与 MIME。
- 下载中心打开文件提供明确反馈，并引导授权安装 APK。

## 1.4.1 轻量化与更新基线

- applicationId 恢复为兼容版 `com.xinyv.median.compat`，versionCode 为 43。
- 可选功能按需初始化，减少首屏工作量、常驻线程和空闲 WebView。
- 首次从旧测试签名迁移必须备份后重装；之后需持续使用同一发布密钥。

## 安全与发布加固

- 高权限用户脚本改为最小权限：缺少 `@grant` 时不开放原生能力。
- 在支持的 System WebView 上使用 document-start 注入；不支持可靠注入时关闭高权限脚本能力，而不是不安全降级。
- 原生脚本桥接使用每个 WebView 的随机令牌、当前来源和脚本匹配范围复核；菜单回调不再暴露在网页全局对象中。
- 用户脚本跨域请求逐跳验证 `@connect`、拒绝 HTTPS 降级、限制响应体，并阻止远程网页借脚本访问本机/私网地址。
- 下载和订阅请求手动处理重定向；跨源重定向不转发 Cookie、Authorization 等凭据。
- 离线页面关闭 JavaScript 和网络访问，并仅在离线场景临时允许受控 `content://` 读取。
- Android 15+ 前台下载超时会保存并暂停任务，避免系统超时崩溃。
- 发布密钥仅从环境变量读取；构建脚本不会自动生成弱口令证书。
- 添加 Android App Bundle、R8、Lint、单元测试、静态安全检查、Dependabot 和 GitHub Actions。

## 本地构建

要求：JDK 17、Android SDK Platform 36、Build Tools，以及 Gradle 8.13。Android Studio 可直接打开本项目。

```bash
./tools/static_checks.sh
./build.sh
```

正式签名 APK 与 AAB：

```bash
export MEDIAN_KEYSTORE=/absolute/path/release-key.p12
export MEDIAN_STOREPASS='...'
export MEDIAN_KEY_ALIAS='...'
export MEDIAN_KEYPASS='...'
./build.sh --release
```

输出目录：

```text
app/build/outputs/apk/release/
app/build/outputs/bundle/release/
```

仓库不包含发布密钥。`./gradlew` 会优先使用标准 Wrapper JAR 或系统 Gradle；两者均不存在时，会从 Gradle 官方地址下载 Gradle 8.13，并在解压前校验官方 SHA-256。Windows 可使用 `gradlew.bat`。

## 发布前必须完成

按顺序执行 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)。本轮源码改动与剩余风险见 [PATCH_REPORT.md](PATCH_REPORT.md)，数据安全申报参考 [PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md)，隐私政策模板见 [PRIVACY_POLICY.md](PRIVACY_POLICY.md)，与 Via 的可量化验收门槛见 [COMPETITIVE_READINESS.md](COMPETITIVE_READINESS.md)。

## 安全边界

Median 使用设备上的 Android System WebView，因此网页渲染、编解码器和部分兼容性取决于用户设备的 WebView 版本。用户脚本是第三方代码，不能等同于普通网页脚本；只应安装可信来源的脚本。详细威胁模型和漏洞报告流程见 [SECURITY.md](SECURITY.md)。

## 许可

当前源码默认保留全部权利，见 [LICENSE](LICENSE)。在公开接受第三方贡献前，应明确选择开源许可证和贡献者协议。

## 500 KB compatibility build

The production source vendors a focused AndroidX WebKit-compatible slice for
`DOCUMENT_START_SCRIPT` rather than packaging AndroidX's unrelated APIs and
transitive runtimes. This keeps the AndroidX call shape used by the browser and
the Chromium support-library boundary protocol while preserving a strict KB
size budget.

A deterministic signed APK can be built with `tools/build_500kb_apk.sh` after
setting `ANDROID_SDK_ROOT`, `MEDIAN_KEYSTORE`, `MEDIAN_STOREPASS`,
`MEDIAN_KEY_ALIAS`, and optionally `MEDIAN_KEYPASS`. The script enforces a signed-APK ceiling of 500 KiB
and fails the build on a size regression. The normal Gradle build remains available for
Android Studio, CI, APK, and AAB workflows.
