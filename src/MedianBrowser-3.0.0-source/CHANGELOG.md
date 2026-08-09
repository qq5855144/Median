# Changelog

## 2.1.9

- 修复部分 Android/OEM 设备上标签卡片突然无法点击的问题。
- 标签切换、长按固定和关闭按钮改为直接绑定到卡片及标签对象，不再依赖 ListView 行点击分发。
- 防止关闭按钮抢占列表焦点，并强化标签页覆盖层触摸接收。


## 2.1.8

- 保持正式包名与稳定签名，支持从 2.1.7 原地升级。
- 完成快稳优化：兼容模式、请求热路径缓存、异步脚本存储、Cookie 合并 flush、预热退避及 renderer 恢复。


## 2.1.6

- 修复选择“自定义代码”后未保存也会把示例“我的主页”写入真实 Logo 配置的问题；取消编辑不再改变任何设置。
- 首次升级会清理 2.1.5 产生的精确冲突状态，并恢复 Median Logo，不影响真正保存过的其他自定义代码。
- 个性化主页改为标题 + 状态说明的双层条目并加入进入箭头，明确每项都可点击；主页总览不再显示“页面布局：紧凑”。
- 自定义 CSS、完整 HTML 与每次打开的说明更清楚。
- versionCode 升至 69、versionName 升至 `2.1.6`；applicationId 仍为 `com.xinyv.median.compat`。

## 2.1.5

- 将个性化主页的 24 个平铺选项重构为 7 个清晰分区，并在设置面板上方显示本地主页效果。
- Logo 设置集中管理类型、文字、配色和排版；新增字号、字重，以及图片宽度、高度、圆角控制。
- 新增安全自定义 CSS，保留内置主页功能；CSS 经过 32 KB 边界和注入/外链校验，主页 CSP 同时禁止外部子资源。
- 完整 HTML 沙箱现在允许内联 JavaScript，但继续移除同源权限、网络连接和内部浏览器权限；外部子框架请求由 WebView 层阻断。
- HTML/CSS 编辑器改为紧凑、有底色、有边界的滚动输入区，修复代码下方出现巨大空白区域的问题。
- 新增 CustomHomeCss 独立自测，Logo 新字段和 CSS/HTML 沙箱进入配置、备份、HTML 生成及 release 构建门禁。
- versionCode 升至 68、versionName 升至 `2.1.5`；包名和发布证书不变，可直接覆盖 2.1.4。

## 2.1.4

- “个性化主页”第二项新增“每次打开”，统一管理打开主页、自定义网页和保留上次内容三种冷启动策略。
- 自定义网页地址同时成为主页键与新建标签页目标；地址经过 HTTP(S) 严格校验，并沿用 HTTPS 优先规则。
- 个性化主页最后新增本地 HTML 编辑器，支持最多 64 KB HTML/内联 CSS、示例代码、启用/停用与删除。
- 自定义 HTML 通过无脚本、无同源权限的 iframe 沙箱展示，并禁止调用受信主页的 `median://` 内部操作；壁纸继续独立流式加载。
- 新增启动策略与 HTML 纯 Java 自测，配置完整贯通冷启动、标签页、主页键、加密备份恢复和旧设置迁移。
- versionCode 升至 67、versionName 升至 `2.1.4`；包名和发布证书不变，可直接覆盖 2.1.3。

## 2.1.3

- 修复 WebView `DownloadListener` 的 `contentLength` 被忽略，导致下载任务初始总大小永远为零的问题。
- 下载器取得真实响应后立即发布并校正总大小，支持 Content-Length、Content-Range 及常见文件大小响应头。
- 下载记录不会再被缺少大小的普通遥测覆盖；暂停、失败、恢复和进程重投继续保留已知总大小。
- 下载中心进度条增至 6 dp，采用溢出安全的 0.1% 精度，文字明确显示百分比、已下载/总计、速度和剩余时间。
- 未知长度传输改为明确显示已下载字节及“总大小未知”，不再把不确定进度伪装成可计算进度。
- versionCode 升至 66、versionName 升至 `2.1.3`；包名和发布证书不变，可覆盖 2.1.2。

## 2.1.2

- 移除主页文字 Logo 原先固定的负字距，新增 `-3 px` 至 `+10 px` 全局字间距设置。
- 自定义 Logo 安全语法新增 `[space=0–24]`，并保留普通空格，可逐处精确控制字母间隙。
- 新增五种渐变方向；重做极光、日落、海洋配色并加入玫瑰金渐变预设。
- 字距与渐变方向纳入配置校验、完整备份恢复、一键重置和主页 HTML 回归测试。
- versionCode 升至 65、versionName 升至 `2.1.2`；包名和发布证书不变，可覆盖 2.1.1 且不清除浏览数据。

## 2.1.1

- 新增 Median、Google 官方配色、极光、日落、海洋渐变与自定义代码文字 Logo。
- 新增安全 Logo 标记解析器，支持逐字 `[color=#RRGGBB]` 和 2–4 色 `[gradient=#色1,#色2]`。
- Logo 编辑器内置语法说明、渐变示例和一键 Google 示例；错误标记不会保存。
- 所有自定义文字都经过 HTML 转义，颜色只接受严格十六进制值，不允许任意 HTML、CSS 或 JavaScript 注入。
- 新增 LogoMarkup 与主页 Google 配色生成回归测试。
- versionCode 升至 64、versionName 升至 `2.1.1`；包名和发布证书不变，可覆盖 2.1.0。

## 2.1.0

- 新增完整的本地个性化主页：自定义壁纸、Logo 图片、标题、副标题、强调色、布局、时钟和模块显隐。
- 壁纸支持遮罩、模糊、填充裁剪或完整显示；搜索框支持纯色、磨砂或隐藏。
- 快捷入口支持 3–5 列、三种形状及书签管理入口。
- 图片导入在后台执行 EXIF 方向修正、尺寸约束、压缩和原子写入；主页以受信内部资源流加载图片，不产生外部请求。
- 新增 HomePageConfig 边界自测、主页 HTML 组合自测及生成 JavaScript 语法门禁。
- versionCode 升至 63、versionName 升至 `2.1.0`；包名和发布证书不变，可覆盖 2.0.2。

## 2.0.2

- 修复主菜单及共享功能面板点击条目时出现巨大圆形波纹的问题。
- 全宽菜单行改用系统有边界点击反馈；工具栏小图标继续保留无边界反馈。
- 新增静态门禁，禁止全宽菜单行再次使用 `selectableItemBackgroundBorderless`。
- versionCode 升至 62、versionName 升至 `2.0.2`；包名和发布证书不变，可覆盖 2.0.1。

## 2.0.1

- 修复纯数字输入（如 `12`）被当成不完整 IP 并打开 `https://12` 的问题。
- 首页搜索框和顶部地址栏统一输入分类：关键词进入当前搜索引擎，网址直接打开。
- 只有完整 HTTP(S) 地址、有效域名、合法 IPv4/IPv6 或 localhost 才按网址处理；邮箱、非 HTTP 协议、无效 IP 与普通文本保持搜索。
- 新增 OmniboxInput 纯 Java 自测并纳入静态检查与 release 构建门禁。
- versionCode 升至 61、versionName 升至 `2.0.1`；包名和发布证书不变，可覆盖 2.0.0。

## 2.0.0

- 全面重构标签页资源调度：列表行回收、热 WebView 硬上限、冷状态分档上限、后台释放备用渲染器，并修复固定标签可绕过内存限制的问题。
- 延后过滤器启动任务只在前台执行；WebView 预热合并为单个可取消任务，脚本安装与更新不再临时创建裸线程。
- BrowserDataStore 增加会话等值判断和 dirty 写盘，避免页面完成、切标签及退后台反复保存相同快照。
- 用户脚本匹配、GM 值与外观过滤结果采用有界缓存；超大 CSS/过程规则不进入缓存，避免以速度换取不可控内存。
- 新增统一 WebView 安全策略，普通与隐私窗口共同关闭文件访问、跨文件来源访问和混合内容。
- 修复隐私窗口二次打开可能重复设置数据目录而失败、Cookie 清理回调缺失导致白屏、退后台仍运行及销毁不完整等问题。
- 标签页总览改为 BaseAdapter/ListView 回收；64 标签场景不再预先构造整页卡片树。
- 下载服务队列限制为 48 个等待任务，队列过载返回明确错误；进程被系统终止时可重投并从持久化游标恢复。
- 下载实时遥测在进程内更新，持久索引改为 5 秒合并写入，终态立即保存，减少大下载列表下的 JSON 序列化与锁竞争。
- 保留 1.7.3 的内部单连接下载和经典通用媒体发现，不恢复 YouTube 专用临时媒体逻辑。
- versionCode 升至 60、versionName 升至 `2.0.0`；包名和发布证书不变。

## 1.7.3

- 移除每个新任务开始前的 1 字节 Range 探测，直接用真实下载响应建立文件、元数据与断点状态。
- 断点请求从原始 URL 重新解析重定向；无效 Content-Range、资源校验值变化及服务器拒绝 Range 时安全从头下载。
- 暂停/取消会断开当前连接，修复网络阻塞时按钮长期无反馈；恢复操作立即更新页面状态并持续轮询。
- 重定向逐跳保存响应 Cookie，跨源目标只读取目标域 Cookie，提升登录/CDN 下载兼容性且不泄漏来源凭据。
- DownloadStore 使用锁保护的进程缓存，消除每秒全量 JSON 重解析；下载线程允许空闲回收。
- 前台服务被系统拒绝时记录明确失败状态；保持 1.7.2 的经典通用嗅探，不加入任何 YouTube 特判。
- versionCode 升至 53，包名与发布证书保持不变。

## 1.7.2

- 功能基线回退到 1.5.1 的通用媒体发现，移除后续所有 YouTube 专用嗅探、播放器数据解析、`videoplayback`、itag 和 SABR 试验代码。
- 保留并整理轻量下载中心：回收列表行、仅活动任务定时刷新、批量删除旧记录，失败任务统一提供普通重试。
- 下载器增加实时 Cookie 刷新、跨源 Origin Referer、重复任务抑制和可测试的 HTTP 重试策略。
- 暂停/取消现在可立即命中尚在队列中的任务；进程被系统结束后，遗留活动状态会转成可继续的暂停任务。
- 401/403/404/410/416 等明确错误快速失败；只对 408/425/429、5xx 和网络异常退避重试。
- applicationId 与发布证书不变，versionCode 升至 52，可直接覆盖此前 1.7.1 试验版。

## 1.5.1

- 禁用所有新任务的 Android DownloadManager 路径，统一使用 Median 内部下载器。
- 内部下载固定为单连接、64–128 KB 缓冲和最多 2 个任务，不再主动分段。
- 仅在中断续传时发送 Range；服务器不支持时自动从头重试。
- 下载中心移除“系统任务”“兼容兜底”和性能档选择，改为真实字节、速度、剩余时间和直接操作。
- 修复系统遗留记录始终显示 0 B：升级后未完成系统任务自动迁移，已完成任务读取真实大小并保持可打开。
- 保留最终响应文件名/MIME 修正、APK 结构识别、Cookie/UA/同源 Referer、OEM 公共目录失败保底。

## 1.5.0

- 普通文件改为 Android DownloadManager 优先，交由系统处理断点、网络切换、重试、通知和重启恢复。
- 修正公共下载目录参数，统一保存到 `Download/Median`；同名任务自动编号。
- 下载任务传递 User-Agent、Cookie 与同源 Referer，提升登录态和防盗链资源兼容性。
- 通用二进制链接改走最终响应元数据探测，并保留 APK 文件结构识别，避免 `.apk` 误存为 `.bin`。
- 兼容下载限制为最多 6 连接、16 MB 预算和 2 个工作任务；分段被拒绝时自动降级单连接。
- 系统任务失败后可在下载中心一键“兼容重试”，并保留原任务的请求上下文与网络约束。

## 1.4.3

- MediaStore 无法创建 APK 项目时，自动回退至应用下载区并返回可授权的只读 content URI。
- 新增严格限制路径的下载 ContentProvider，保证兜底文件可以打开、分享和交给系统安装器。

## 1.4.2

- 分段服务器返回非 206 或错误 Content-Range 时自动清空分段状态并降级单连接。
- 综合最终 URL、Content-Disposition、Content-Type 与 APK ZIP 结构修正文件名和 MIME。
- 下载中心打开/分享增加 URI 授权、明确反馈和 APK 安装来源授权入口。

## 1.4.1

- 恢复兼容版 applicationId `com.xinyv.median.compat`，版本号提升至 43。
- 建立新的稳定签名更新链；旧测试签名安装需备份数据后进行一次重装。

## 1.4.0
- 首次启动显示一次官方渠道提示；同一信息可在“浏览器设置 → 关于 Median”再次查看。

- 500 KB 定位修正为“兼容性预算”，不再追求 180 KB 极限数字。
- WebView 兼容层新增提供方版本缓存失效、异常分类、OEM 旧特征别名与 UI 线程检查。
- 增加严格来源规则验证；不支持安全 document-start 时继续默认拒绝高权限脚本。
- 关于页新增 WebView 兼容诊断、复制报告和无网络 document-start 顺序自测。
- 500 KiB 仅作为发行目标提示，超过时输出告警而不是把正常构建标记为失败。

## 1.4.0 lightweight optimization pass

- Moved filter compilation and subscription work off the first-render window.
- Lazily initializes downloads, offline pages, reader/TTS, script values, and the password vault.
- Keeps one renderer in standard mode on typical devices and limits WebView prewarming to performance mode.
- Reduced request-path allocations and locking in media sniffing and ad-block matching.
- Reuses rule-match scratch state, performs one cosmetic-rule assembly per page, and removes dark-mode reflection.
- Lets idle worker threads time out and tightens the 500 KiB R8/size gate.
- Centralized optional feature lifecycle in `BrowserServices`.

## 1.4.0 500 KB RC1

- 将用户脚本 document-start 路径改为聚焦版 AndroidX WebKit 兼容源码，保留 `WebViewCompat`、`WebViewFeature` 与 `ScriptHandler` API 形状。
- 按 AndroidX/Chromium support-library boundary 协议调用 System WebView，不打包无关 WebKit API、AndroidX Core、Lifecycle、VersionedParcelable 或 Kotlin 运行时。
- WebView provider 更新后按包名与版本重新建立兼容快照；不支持时继续 fail closed。
- 增加可重复的 `tools/build_500kb_apk.sh`，构建后报告 500 KiB 发行目标；超过目标只告警，不制造与运行无关的构建失败。
- versionCode 更新为 42，versionName 更新为 `1.4.0`。

## 1.4.0 release candidate

- 建立标准 Gradle/Android App Bundle/R8/Lint/JUnit/GitHub Actions 发布工程，目标 API 36。
- 发布签名改为环境变量注入，删除自动生成弱口令开发证书的路径。
- 用户脚本权限默认拒绝，增加 document-start 安全桥接、来源与权限复核、隐藏菜单回调和旧 WebView 安全降级。
- 用户脚本网络请求逐跳执行 `@connect`、协议、私网和响应体限制，阻止重定向绕过及凭据泄露。
- 下载器与过滤订阅改为手动重定向；跨源时剥离 Cookie 与认证头。
- 分段与断点续传验证 `Content-Range`，下载文件名清除控制字符和双向文本伪装字符。
- 摄像头、麦克风和定位权限绑定完整 HTTPS 来源，授权期间切页或端口变化会自动失效。
- 普通与隐私地址输入、备份导入统一拒绝畸形或带 URL 凭据的网络地址。
- 离线页面关闭 JavaScript 与网络访问，常规网页关闭 content 访问。
- 隐私窗口在无法建立独立 WebView 数据目录时直接拒绝启动。
- 增加 Android 15+ 前台下载超时安全暂停。
- 增加自适应图标、边到边窗口适配、动态版本显示和应用内隐私说明。
- 增加隐私、数据安全、发布、竞争验收、安全报告和第三方声明文档。

## 1.3.5 omni preview

- 移除地址栏右侧独立下载按钮，恢复更完整的地址栏空间。
- 下载中心保留在右下角“三个点”浏览器主菜单中，作为一级入口。
- 下载中心仍由独立 `DownloadCenterActivity` 承载，不属于页面工具，也不依赖当前网页。
- 版本号更新为 `1.3.5-omni-preview`，versionCode 更新为 38。

## 1.3.4 omni preview

- 从“页面工具”中移除旧的下载中心入口。
- 在浏览器主菜单增加一级“下载中心”入口。
- 保留地址栏独立下载按钮，下载中心继续由单独 Activity 承载。
