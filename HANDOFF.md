# 片匠（B站切片）Android 项目交接文档

> 给接手者的完整说明。读完这份就能独立改代码、编译、测试、发布。
> 项目不是从零开始的玩具——它已经迭代了 6.19 个版本，踩过一堆 Android 媒体栈的坑，
> 下面「历史坑」那一节请务必先看，很多看起来多余的保护都是血换来的。

---

## 一、这个项目到底是什么

一个**把 B站 / 抖音 / 快手 的视频下下来、压到指定体积、切成若干段、存进片库、一键发微信**的
Android 原生应用（APK 只有 125 KB，不依赖 Gradle / AndroidX / ffmpeg）。

用户的真实用途：短剧出海、内容搬运。他要的是「每段不超过 9 MB，能直接微信发出去」。
所以这个 App 的核心不是播放器，而是**一条可靠的转码/切分流水线**，加一个能随手抓链接的入口。

- 应用名：片匠
- 包名：`com.zhuanz.bilislicer`
- 当前版本：`versionName=6.19` / `versionCode=70`
- minSdk 29（Android 10），targetSdk 34
- 语言/工具链：纯 Java 11，无第三方依赖，手写 `build.sh` 调 aapt2/javac/d8/apksigner

---

## 二、环境与构建

### 构建

```bash
cd bili-app
bash build.sh          # 产出 clipsmith.apk（约 125 KB）
```

`build.sh` 的 6 步：aapt2 compile → aapt2 link（生成 R.java）→ javac → d8 →
用 `adddex.py` 把 dex 塞进 apk → zipalign → apksigner 签名。

依赖：
- Android SDK（默认 `/d/AndroidSdk`，可用 `ANDROID_SDK_ROOT` 覆盖）
- build-tools 34.0.0
- JDK 11（优先 `JAVA_HOME`，否则用 SDK 自带的 `jdk/`）

签名用的是仓库里的 `debug.jks`（pass: `android`，alias: `androiddebugkey`）。
**这个 keystore 必须保留**——换签名会导致用户无法覆盖安装。

### 目标设备（唯一真机）

- 小米 `23049RAD8C`（Redmi Note 12 Turbo 级），Android 13 / MIUI 14
- 硬件编码器：`c2.qti.avc.encoder`
- adb 序列号：`74f8be63`

**这台机器有两个硬性特征，直接影响架构：**
1. **Surface 直通必失败**（`IllegalStateException`），只有 YUV 路径能用 → 转码是 CPU 瓶颈
2. **无障碍服务会随进程消失**：`force-stop`、用户在多任务里划掉、`am kill`
   都会让系统关闭它的无障碍服务，必须重新授权

---

## 三、模块地图

```
src/com/zhuanz/bilislicer/
├── MainActivity.java      2669 行  主界面：输入框、chips、设置弹窗、片库渲染、选择模式、分享
├── Pipeline.java          1156 行  【核心】一条视频的完整流水线
├── ClipService.java       1133 行  剪贴板监听：前台轮询 + 悬浮面板 + 悬浮球
├── Source.java             699 行  三平台解析（B站/抖音/快手），产出 Media 对象
├── PartsActivity.java      540 行  多P / 合集选择界面
├── TranscoderYuv.java      473 行  【主力】YUV 软通道转码（本机唯一能跑的路径）
├── BiliApi.java            419 行  B站 API（含 WBI 签名）
├── Transcoder.java         390 行  编码器选择 + Surface 直通 + 失败回退 YUV
├── Splitter.java           377 行  【核心】按体积/时间切分、时长探测
├── AudioFix.java           335 行  音轨重编码、换壳 remux
├── HistoryActivity.java    311 行  处理记录界面
├── Jobs.java               310 行  任务队列、工作线程、取消、前台服务保活
├── Thumbs.java             290 行  缩略图生成与缓存
├── Wbi.java                217 行  B站 WBI 签名算法
├── History.java            207 行  处理记录存储（含失败记录）
├── Library.java            188 行  片库扫描与分组
├── WorkService.java        162 行  前台服务（防止后台被杀）
├── Store.java              137 行  写入 MediaStore（Download/B站切片/<视频名>/）
├── Muxer.java              128 行  DASH 音视频流合成（纯 remux）
├── Downloader.java               分片下载、断点、多候选域名
├── Diag.java                      【诊断】环形日志缓冲 400 行 + 一键复制
├── VideoProvider.java             FileProvider（分享用）
├── ClipWatchService.java          无障碍服务：监听「复制」点击
├── ClipProbeActivity.java         透明 Activity：抢焦点读剪贴板
└── CancelReceiver.java            通知栏「取消」按钮
```

---

## 四、一条视频的完整数据流

```
[入口] 用户复制链接
   ├─ 前台：MainActivity.checkClipboard()  （只在自己 app 回到前台时读）
   ├─ 后台：ClipWatchService 收到「复制」点击 → ClipProbeActivity 抢焦点读剪贴板
   └─ 手动：粘贴按钮 / 输入框
        ↓
   enqueueAll(text)  ── 解析出 URL 列表（一个文本里可能有多条链接）
        ↓            ── 与 History 比对：只有「成功过」的记录才算重复（失败记录不拦）
   Jobs.enqueue(url, ...)  → 单工作线程串行消费
        ↓
   Pipeline.run(ctx, job)  ← 下面这一整段是核心
        ├─ ① Source.resolve()      解析出直链、标题、分P、合集
        ├─ ② 下载（Downloader）    多候选域名、断点、进度 0-50%
        ├─ ③ 时间戳归一化         【见历史坑 #1】
        ├─ ④ 判定要不要压 / 切几段
        ├─ ⑤ 转码（分段）          【见历史坑 #2 #3】
        ├─ ⑥ 切分（Splitter）
        └─ ⑦ 落库（Store → MediaStore）
        ↓
   片库（MainActivity 底部列表）→ 点「微信」发出去
```

### 关键中间产物

- 下载完成的原片：`work/raw.mp4`
- 预裁剪（可选）：`work/cut.mp4`
- 归一化（时间戳坏了才会有）：`work/norm.mp4`
- 转码输出：`work/enc.mp4` 或 `work/out/01_xxx_1ofN.mp4`
- 切分临时段：`work/seg/`、`work/sub*/`
- 最终落库：`/sdcard/Download/B站切片/<视频标题>/01_<标题>_1ofN.mp4`

---

## 五、核心算法与参数（改这里之前先读懂）

### 5.1 体积/码率/段数的推导（`Pipeline.finish`）

```
partBytes   = (limitMb - 0.4) * 1e6          // 用户的「每段上限」，默认 9 MB → 实际 8.6 MB
srcKbps     = rawSize * 8 / seconds / 1000
nBySize     = ceil(totalBytes / partBytes)   // 按体积至少要切几段
capKbps     = partBytes * 8 / partSec / 1000 // 每段时长下能容纳的码率
heavy       = srcKbps > capKbps * 1.15 || byCap || needCompress4Total
```

**需要压缩时**（heavy）走「分段转码」：先按时间切段，再逐段压。
段长计算（`segUs`）有一个专门修正过的地方：

```java
int outKbps = videoKbps + max(48, AudioFix.audioKbpsMeasured(raw));  // 成品码率
double segSec = partBytes * 8.0 / (outKbps * 1000.0) * 0.92;         // 注意单位
segSec = min(240, max(8, segSec));                                    // 不超用户设的时长上限
```

> ⚠️ 这里的坑：**必须用「成品码率」而不是「源码率」算段长**。
> 源 1.9 Mbps 压完只有 0.98 Mbps，按源码率算会切出 30 秒的碎段，每段才 3 MB，
> 明明 9 MB 的额度只用了一半。用成品码率算，180 秒视频应该是 3 段、每段 60 秒。

### 5.2 切分（`Splitter.split`）

- 切点 = 均分边界，再**吸附到关键帧**（`SEEK_TO_PREVIOUS_SYNC` 同理）
- 只在目标点 ±25% 范围内找关键帧；找不到就**放弃这个切点**（宁可少切，也不从非关键帧开头）
- `minPart = max(3s, stepUs/3)`：防止切出极短段
- 每段用 `extractRange()` 纯拷样本，不解码，很快
- **只保留第一条视频轨 + 第一条音频轨**（多轨源每段都整份复制会让产物比原片还大）

### 5.3 转码（`Transcoder` / `TranscoderYuv`）

- `Transcoder.transcode()` 先试 Surface 直通，失败回退 YUV
- **本机 Surface 必失败**，所以实际全走 YUV（`TranscoderYuv`）
- YUV 路径要点：
  - 时间戳**完全按帧号重算**（`outFrameIdx * frameUs`），不信源 PTS
  - 音频 PTS 按 `aFrameIdx * (1024/sampleRate)` 重算
  - 写视频样本前保证 PTS 单调递增（编码器偶发乱序）
- 实测速度：`848x576` 约 **0.12 s/秒素材**（180 s → 22 s）；1080p 60 s → 5.9 s

### 5.4 音轨

- 默认**原样拷贝**（passthrough），不重编码
- `AudioFix.audioKbpsMeasured()` 用「样本总字节 ÷ 时长」实测音轨码率
  （容器里写的 KEY_BIT_RATE 常常是假的，标 128k 实际 1 Mbps）
- 体积超目标且音轨 >192k 时，`AudioFix.reencode()` 重编码到 128k + `remuxWithAudio()` 换壳

---

## 六、历史坑（绝对不要回退，这些是真实踩出来的）

### #1 源时间戳损坏 → MediaMuxer 写不出 moov

症状：`读不到视频时长，无法切分：enc.mp4（21MB）`，但源文件时长读得到。

根因：某些下载源（抖音尤其）的样本 PTS 大量重复/回退（实测 4096 帧异常）。
编码器输出时间轴错乱 → MediaMuxer 收尾失败 → 产物只有 mdat、没有 moov
（`ffprobe` 报 `moov atom not found`，文件头 16 字节全 0）。

修法：`Pipeline.timestampsLookBroken()` 采样 800 帧，异常率 >5% 就调
`normalizeTimestamps()` **不改画质地重写时间轴**（按样本序号重建 PTS）。

### #2 整片转码产物一大就必坏

60 MB 量级的转码产物在这台机器上 muxer 收尾必失败。
所以 heavy 时**不要整片转码再切**，改成「按时间切段 → 逐段转码」，
每段十来兆，收尾稳定。

### #3 音轨里混进了视频帧（体积失控的头号元凶）

```java
// 错误写法
ex.selectTrack(aIdx);            // 以为"只选音频"
while (...) { ex.readSampleData(...); mux.writeSampleData(aTrack, ...); ex.advance(); }
```

`MediaExtractor` 的选轨是**追加**，不是替换。前面解码视频时视频轨还选着，
于是 `readSampleData` 在两条轨之间交替返回样本——一大半视频帧被当成音频写进音轨。

实测：一段 24 秒的音轨写出 6.6 MB（应该 0.39 MB），整段 9 MB，怎么调视频码率都压不下来。

修法：拷音轨前 `unselectTrack()` 所有轨道，再单独 `selectTrack(aIdx)`。

> 这个 bug 极隐蔽：不报错、不崩溃，只是数据默默胖十倍。任何操作 MediaExtractor
> 换轨的地方都要检查这一点（`Splitter.extractRange` 用的是 trackMap 白名单，安全）。

### #4 Surface 直通失败会清空后面写好的文件（「第一段总是没有」）

`Transcoder.transcode()` 先试 Surface，失败后回退 YUV 写**同一个输出文件**。
Surface 那次创建过的 MediaMuxer 没释放干净，它的后台 writer 会在十几秒后
把 YUV 已经写好的内容截断成 **0 字节**。

现象：日志里「段产出 1/6 = 4.02 MB」，18 秒后「待入库 1/6 = 0.00 MB」，入库时被丢弃
→ 用户看到的就是「没有第一段」。

修法：Surface 直通写到 `out + ".sf.tmp"`，成功才 `renameTo(out)`，失败直接删除临时文件。

> **这条修复目前只做了代码，尚未在真机验证。接手后第一件事就是验证它。**

### #5 分段转码后 `return` 跳过了入库

一次重构里在分段转码成功后直接 `return`，绕过了「存入片库」。
界面显示「完成 6 段 17MB」，片库里却什么都没有。

铁律：**任何分支都不能跳过「⑤ 存入片库」**。

### #6 空段入库 / 首段切点太近

- 0 字节文件入库会在片库留空条目 → 入库前统一过滤 `length() <= 0`
- 首段切点紧贴片头会切出几帧甚至 0 字节 → `minPart` 保护

### #7 失败记录被当成「已经处理过」

`History.addFail()` 把失败也写进历史，而重复判断没区分成功/失败
→ 点「重试」弹「这个视频之前已经切过了」，看着像没反应。

修法：`if (r != null && !r.fail)` 才算重复；重试按钮走 `doEnqueue(urls, true)` 跳过确认。

### #8 无障碍服务会「凭空消失」

`force-stop`、用户在多任务划掉 app、`am kill` 都会让系统关闭该应用的无障碍服务。
这是「复制链接时好时坏」的根本原因，不是代码问题。

应对：
- 主界面顶部有黄色提示条（`refreshAccBanner()`），检测不到就提示用户去开
- 状态判断**不能读** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`（Android 10+ 普通应用读到 null），
  必须走 `AccessibilityManager.getEnabledAccessibilityServiceList()`
- 不要用 `am force-stop` 测试冷启动，会把自己的无障碍干掉

### #9 剪贴板残留失效链接会每 30 秒弹一次面板

去重窗口是 30 秒，过期就重试。剪贴板里留着一条坏链接时会无限打扰。
修法：记录失败签名（prefs `clipFailSig` + 静态变量），同内容不再自动重试，直到剪贴板真的变了。

---

## 七、当前未解决 / 待验证（接手后的第一批任务）

| # | 问题 | 现状 | 建议方向 |
|---|---|---|---|
| 1 | **首段被清空** | 已改成 Surface 写临时文件，**未验证** | 真机跑一遍，看「待入库 01」是否 >0 |
| 2 | **段长偏短**（每段 30s / 3MB，额度只用一半） | 已改成按成品码率算段长，**未验证** | 同上，180s 应该切成 3 段 × 60s |
| 3 | 转码速度 | YUV 路径 0.12 s/s | 见下节 |
| 4 | 多P / 合集 | 只做了「识别 + 选择」，批量体验未打磨 | 见下节 |
| 5 | 码率不精准 | 编码器会超发（855k 实际 ~2.4Mbps），已加「超发重压 + 降分辨率」闭环兜底 | 可考虑用 `KEY_QUALITY` 或换编码器策略 |

### 验证方法（必须做）

```bash
# 1) 看诊断时间线（App 内：设置 → 复制诊断日志）
adb logcat -s ClipSmithDiag

# 2) 检查产出体积与时长
adb shell 'd=$(ls -dt "/sdcard/Download/B站切片/"*关键词* | head -1); ls -la "$d"'
adb pull "<某一段>" /tmp/a.mp4 && ffprobe -v error -show_entries format=duration,size \
  -show_entries stream=codec_type,bit_rate -of default=nw=1 /tmp/a.mp4

# 3) 内容完整性：各段时长相加应等于源时长
# 4) 画面检查：ffmpeg -ss 5 -i a.mp4 -frames:v 1 out.jpg，肉眼确认不是绿屏/花屏
```

---

## 八、性能现状与提速方向

### 实测基线（这台设备）

| 环节 | 耗时 | 备注 |
|---|---|---|
| 解析（B站/抖音） | 1–5 s | 含网络 |
| 下载 | 取决于网速 | 用户网络经常只有几十 KB/s，是最大不确定性 |
| 时间戳归一化 | 1–3 s | 纯拷样本 |
| 转码 848×576 | **0.12 s / 素材秒** | 180 s → ~22 s，CPU 瓶颈 |
| 转码 1080p | **0.10 s / 素材秒** | 60 s → 5.9 s |
| 切分 | <1 s | 纯拷样本 |
| 落库 | <1 s | MediaStore 拷贝 |

### 提速可做的事（按收益排序）

1. **让 Surface 直通真正可用**。理论上比 YUV 快一倍以上。
   当前失败原因只看到 `IllegalStateException`，没有深挖。
   排查方向：`configure()` 的 color-format 支持列表、`createInputSurface()` 的调用时机、
   编码器实例是否被上一轮占用。做成后 0.12 → 0.05 s/s。
2. **复用一个 MediaCodec 实例跨段**。现在每段都新建解码器+编码器，
   而本机同时只能开约 2 个硬编实例，连续分段容易 `start failed`（已加 80ms 间隔 + 重试）。
3. **段间并行**。目前串行；若能做到「解码一次、喂多个编码器」或至少流水线化，可省掉重复解码。
4. **下载预热**：边下边转（先下前 N 秒就开工），对慢网收益最大。
5. **跳过不必要的归一化**：只对异常率 >5% 的源做。

---

## 九、多P / 合集 / UP主 现状

`Source.Media` 已经带了这些字段：

```java
int page, pageCount;            // 分P
boolean explicitPage;           // 链接是否显式指定 ?p=
List<BiliApi.Page> pages;       // 分P 列表
List<BiliApi.Episode> season;   // 合集
String seasonTitle;
```

- 解析层已能识别多P 和合集（`PartsActivity` 用来让用户勾选）
- `Pipeline.NeedConfirm`（`kind` / `pages` / `seasonList` / `pageList`）用于「等用户拍板」
- 现状：**选择→逐个入队**，体验粗糙；批量时没有总进度、没有断点续传、失败不聚合

### 建议做的（用户明确提过）

1. **多P 批量**：一次勾选全部 → 队列化 → 统一进度「第 2/8 个」→ 单个失败不影响整体
2. **合集/番剧**：按集号排序、自动命名 `EP01_xxx`、跳过已处理的集
3. **UP 主空间**：翻页拉全部投稿、按播放量/时间排序、勾选范围
4. **断点续传**：下载中断后能从 `work/` 里恢复，而不是重下
5. **失败聚合**：全部跑完后给一份「3 个成功、1 个失败（链接失效）」的汇总

---

## 十、界面与交互（MainActivity）要点

- 顶部：标题「片匠」+ 历史按钮 📋 + 设置 ⚙
- 输入框 + 「开始」按钮；支持一次粘贴多条链接
- chips 行：`自动识别`（剪贴板监听开关）、`自动挂载`、`粘贴`、`↻ 重试`、`选本地视频`、`⏹ 停止`
- 状态行、进度条、日志区
- 无障碍失效提示条（黄色，`refreshAccBanner()`）
- 片库：按视频分组，每组有 `⋯` 菜单（播放/发整组/发微信/复制文件名/标已发/删除整组）
  - `选择` 进入多选模式（全选/标已发/删除/取消）
  - `只看未发` 筛选
- 设置弹窗（`settingsDialog()`）：
  | 项 | prefs key | 默认 |
  |---|---|---|
  | 每段上限 MB | `limit` | 9 |
  | 段数（0=自动） | `parts` | 0 |
  | 每段最短分钟 | `partMin` | 0 |
  | 码率上限 kbps | `maxKbps` | 900 |
  | 画质优先 | `hq` | false |
  | 跳过开头秒 | `skipSec` | 0 |
  | 去掉结尾秒 | `tailSec` | 0 |
  | 整条目标 MB | `totalMb` | 0 |
  | 自动识别 | `monitor` | — |
  | 自动挂载 | `attach` | — |
  | 只看未发 | `onlyUnsent` | — |
  | 排序 | `sort` | — |
  | 视频方向 | `orient` | — |
  - 底部：`复制诊断日志`、`清理空文件夹`、`恢复默认设置`

---

## 十一、诊断与调试工具（已经内置，直接用）

### Diag（`Diag.java`）

- 环形缓冲 400 行，`Diag.line(stage, detail)` 带任务号 `#n`
- 关键节点：检测来源 → 入队 → 开始 → 解析成功 → 时间戳异常 → 素材就绪（含音轨实测码率）
  → 段长 → 切段待压 → 段源/段产出 → 待入库 → 丢空段 → 落库 → 完成/失败
- App 内「设置 → 复制诊断日志」把全文塞进剪贴板，用户可以直接发出来
- adb 侧：`adb logcat -s ClipSmithDiag`

### 其它有用的 tag

```
adb logcat -s BiliSlicer        # 转码细节：encoder=、yuv transcode done size/loops/frames/audioBytes
adb logcat | grep MPEG4Writer   # muxer 收尾是否正常（有没有 fsync / Stop 报错）
```

---

## 十二、发布流程

```bash
cd bili-app
# 1) 改版本号（AndroidManifest.xml 里 versionCode/versionName）
# 2) 构建
bash build.sh
# 3) 装机验证
adb install -r clipsmith.apk
# 4) 提交
git add -A && git commit -m "..." && git push
```

- GitHub：`gugujiao953-ship-it/clipsmith`（网络不稳，push 常失败，重试几次）
- 蓝奏云：用户自己上传，链接不固定
- 文档：`docs/DEVLOG.md` 每次发版追加一节，写清「改了什么、为什么、怎么验证的」

---

## 十三、红线（动了会出事）

1. **不要回退上面「历史坑」里的任何保护**，每一条都是真实故障换来的
2. **不要删 `debug.jks`**，换签名用户就无法覆盖安装
3. **不要在测试时用 `am force-stop`** 冷启动，会清掉无障碍（用 `am start`）
4. **不要跳过「存入片库」**（历史坑 #5）
5. **不要用 `Settings.Secure` 读无障碍状态**（历史坑 #8）
6. **操作 MediaExtractor 换轨前必须 `unselectTrack`**（历史坑 #3）
7. 用户片库里是真实文件，**任何删除/覆盖逻辑都要先在小样本上验证**
8. 这是**给真人用**的工具：宁可多留一层兜底，也不要在用户手机上「悄悄产出坏文件」

---

## 十四、给接手者的一句话总结

这项目的难点不在功能，而在 **Android 媒体栈的隐性行为**：
MediaMuxer 会在时间戳异常时静默丢弃 moov、MediaExtractor 选轨是追加不是替换、
硬编码器会给码率设一个「质量下限」、Surface 直通失败会污染输出文件。
所有「莫名其妙」的现象，第一反应应该是**把中间产物拉出来用 ffprobe 看**，而不是猜代码。

先把「首段被清空」和「段长」两条验证掉，跑通一条完整的 180 秒视频
（期望：3 段、每段约 6–7 MB、时长相加 = 180 秒、画面正常），再谈提速和多P。
