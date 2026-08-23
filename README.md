# TimeLapseCamera · 延时相机

长期定时拍摄 Android App，专为旧手机设计。连续 1 年每小时拍照一张，记录植物生长或城市发展。

## 核心特性

- **长时间定期拍摄**：支持 15 秒 ~ 24 小时拍摄间隔，可连续运行数月
- **低功耗设计**：每次拍摄仅数秒，间隔期深度休眠，不持锁
- **三层保活**：前台服务 + START_STICKY + AlarmManager 备份闹钟
- **水印系统**：时间戳 + 自定义文字 + 电量/存储/温度（可开关）
- **失败回退**：镜头切换 + 黑图占位 + 写入不崩溃
- **三种存储位置**：App 私有目录 / 系统相册 (DCIM) / SD 卡
- **远程配置**：URL 下发拍摄间隔，三级回退（远程值 → 上次值 → 本地值）
- **输入校验**：间隔范围 15-86400 + URL 格式 + 实际抓取验证
- **底部导航 4 Tab**：状态 / 预览 / 相册 / 设置

## 技术栈

| 领域 | 技术 |
|------|------|
| 语言 | Kotlin |
| 异步 | Coroutines + kotlinx-coroutines-play-services |
| 相机 | CameraX（Preview + ImageCapture） |
| 导航 | Bottom Navigation + Fragment |
| 图片加载 | Coil |
| 持久化 | SharedPreferences |
| 后台调度 | 前台服务 + AlarmManager（备份） |
| 存储 | File API + MediaStore（DCIM） |
| 权限 | ActivityResultContracts |
| minSdk | 26 (Android 8.0) |
| targetSdk | 34 (Android 14) |

## 架构总览

```
┌──────────────────────────────────────────────────┐
│                    UI 层                          │
│  ┌──────────┐ ┌──────┐ ┌──────┐ ┌──────┐        │
│  │StatusFrag│ │Preview│ │Gallery│ │Settings│   │
│  └────┬─────┘ └──┬───┘ └──┬───┘ └──┬───┘       │
│       │           │        │        │            │
│       └─────┬─────┴────────┴────────┘           │
│             │ Intent / 读配置                      │
├─────────────┼────────────────────────────────────┤
│             ▼           业务层                     │
│  ┌──────────────────────────────────────────┐    │
│  │           CaptureService                  │    │
│  │  ┌──────────────────────────────────┐    │    │
│  │  │ captureLoop()                     │    │    │
│  │  │  1. 读 config (本地+远程)          │    │    │
│  │  │  2. 取下一拍摄间隔                 │    │    │
│  │  │  3. 唤醒等待                        │    │    │
│  │  │  4. 拍照 (ICameraController)       │    │    │
│  │  │  5. 加水印 (WatermarkProcessor)     │    │    │
│  │  │  6. 存盘 (IPhotoStorage)            │    │    │
│  │  │  7. 更新通知 + 设备份闹钟            │    │    │
│  │  │  8. 更新 config (captureCount 等)   │    │    │
│  │  └──────────────────────────────────┘    │    │
│  └──────────────────────────────────────────┘    │
├──────────────────────────────────────────────────┤
│                 模块层                             │
│  ┌────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐ │
│  │ Config │ │  Camera  │ │Watermark│ │Storage │ │
│  │        │ │  (接口)   │ │         │ │ (接口) │ │
│  │        │ │     ↓    │ │         │ │   ↓    │ │
│  │        │ │CameraX   │ │         │ │Factory │ │
│  │        │ │Controller│ │         │ │  ↓↓↓   │ │
│  │        │ │          │ │         │ │Local/  │ │
│  │        │ │          │ │         │ │DCIM/SD │ │
│  └────────┘ └──────────┘ └─────────┘ └────────┘ │
└──────────────────────────────────────────────────┘
```

## 目录结构

```
app/src/main/java/com/timelapse/camera/
├── MainActivity.kt              # 底部导航 + Fragment 切换
├── camera/                       # ── 相机模块 ──
│   ├── ICameraController.kt      #   接口：capture() / release()
│   └── CameraXController.kt     #   CameraX 实现，备用镜头 fallback
├── config/                       # ── 配置模块 ──
│   ├── CaptureConfig.kt         #   持久化 + StorageLocation 枚举
│   └── RemoteConfigFetcher.kt    #   URL 下发间隔，三级回退
├── model/
│   └── CaptureResult.kt          #   sealed class: Success / Failure
├── scheduler/                    # ── 调度模块（备份机制）──
│   ├── BootReceiver.kt           #   开机自启
│   ├── CaptureReceiver.kt       #   闹钟触发 → 重启 Service
│   └── CaptureScheduler.kt      #   AlarmManager 封装
├── service/
│   └── CaptureService.kt         # 前台服务：编排全流程
├── storage/                      # ── 存储模块 ──
│   ├── IPhotoStorage.kt          #   接口：save / getPhotoCount / ...
│   ├── LocalPhotoStorage.kt     #   App 私有目录 / SD 卡（File API）
│   ├── DcimPhotoStorage.kt      #   DCIM（API 29+ MediaStore / 26-28 File API）
│   └── PhotoStorageFactory.kt   #   工厂：根据配置选择实现
├── ui/
│   ├── status/StatusFragment.kt  # Tab1: 倒计时 + 缩略图 + 状态
│   ├── preview/PreviewFragment.kt # Tab2: 实时预览 + 立即拍一张
│   ├── gallery/GalleryFragment.kt # Tab3: 网格照片列表
│   └── settings/SettingsFragment.kt # Tab4: 拍摄/水印/权限/远程/关于
├── util/
│   ├── BatteryMonitor.kt         # 电量/存储/温度读取（无需权限）
│   └── PermissionChecker.kt      # 4 项权限检测 + 跳转设置
└── watermark/
    ├── WatermarkOptions.kt       # 水印配置（电量/存储/温度开关）
    └── WatermarkProcessor.kt     # Canvas 绘制 + createErrorBitmap()
```

## 关键设计决策

### Camera2 vs CameraX

| 对比项 | Camera2 | CameraX |
|--------|---------|---------|
| 代码量 | 多（手写 HandlerThread、会话管理） | 少 40%（Preview 用例一行） |
| 预览实现 | 手写 CaptureSession | PreviewView + Preview 用例 |
| 兼容性 | API 21+ | API 21+（底层封装 Camera2） |
| 教学价值 | 贴近底层 | 现代推荐写法 |

**选择 CameraX**：Google 官方推荐，代码简洁，预览和拍照用同一套体系，教学更统一。

### 纯 AlarmManager vs 前台服务

| 对比项 | 纯 AlarmManager | 前台服务 + AlarmManager 备份 |
|--------|----------------|-------------------------------|
| 国产 ROM 保活 | 不可靠（进程被杀 → 闹钟被取消） | 可靠（前台服务不被杀 + 闹钟备份） |
| 用户可见性 | 无 | 通知栏倒计时 |
| 功耗 | 低 | 低（间隔期不持锁） |

**选择前台服务 + AlarmManager 备份**：三层保活确保长期运行可靠。

### SharedPreferences vs DataStore

**选择 SharedPreferences**：API 更简单，学生容易理解；DataStore 的异步优势在此场景不明显。（可迁移到 DataStore 作为学生练习）

## 失败回退机制

四个关键环节的回退策略：

| 环节 | 失败场景 | 回退策略 |
|------|---------|---------|
| 唤醒 | 服务被杀 | AlarmManager 备份闹钟 → CaptureReceiver → 重启 Service |
| 拍照 | 主镜头失败 | 切换备用镜头重试 → 仍失败 → 生成黑图占位（水印标注"拍摄失败"） |
| 远程配置 | URL 失败/超时/值超范围 | 远程值 → 上次远程值 → 本地配置值（三级回退） |
| 写入 | 磁盘满/IO 错误 | runCatching 兜底，仅 Log 不崩溃，下一轮重试 |

运行时序：

```
深度休眠 → AlarmManager 唤醒 → 前台服务启动
  → 取配置（本地 + 远程）→ 拍照（主镜头 → 备用镜头 → 黑图）
  → 加水印 → 存盘 → 更新通知 + 设备份闹钟
  → 更新 config（captureCount + lastCaptureTime）→ 服务停止 → 深度休眠
```

## 深度专题 1：Bitmap 内存优化

### 优化前 vs 优化后

```
优化前：raw → 旋转 → copy → 水印 → compress → recycle
        ~8MB    ~8MB   ~8MB                （峰值 ~25MB）

优化后：raw → 旋转(同对象替换) → 直接绘制水印 → compress → recycle
        ~8MB   （短暂 2 张后立即回收）       （峰值 ~8MB）
```

### 关键改动

1. `CameraXController.decodeImage()`：`inMutable = true`，解码出可变 Bitmap
2. `WatermarkProcessor.apply()`：移除 `bitmap.copy()`，直接 Canvas 绘制到输入 Bitmap 上
3. 责任链：CameraXController 创建 → WatermarkProcessor 绘制 → LocalPhotoStorage 存盘后 recycle

### 责任链模式

```
CameraXController          WatermarkProcessor         LocalPhotoStorage
     │                          │                        │
  创建 Bitmap               在 Bitmap 上绘制            compress + recycle
     │                          │                        │
     └──────── 不 copy ─────────┴────── 不 copy ──────────┘
              （任何时刻内存中只有 1 个 Bitmap）
```

### GC 压力视角

单次拍摄产生 ~2MB 临时对象（JPEG bytes + BitmapFactory 内部缓冲）。在小时级间隔下，GC 压力可忽略不计。仅在秒级间隔 + 低内存设备上才需要进一步优化（如 inBitmap 复用）。

## 深度专题 2：Android 生命周期与初始化时机

### 问题根源

UI 可见性、业务执行、数据初始化三者混淆，导致 `lateinit` 在被消费时还未初始化。

### 真实 bug 演变

```
阶段 1：by lazy（碰巧能工作）
  storage by lazy { LocalPhotoStorage(ctx) }
  → 首次访问延迟到 onResume，碰巧 ctx 已可用
  → 但"碰巧"是隐式假设，代码里看不出来

阶段 2：重构改成 lateinit（暴露了 ordering bug）
  lateinit var storage
  → 在 onViewCreated 消费，但 onResume 才赋值
  → 崩溃：UninitializedPropertyAccessException

阶段 3：onCreate() 初始化 + reloadFromDisk()（正确模式）
  onCreate() → storage = create(load())     ← 保证首次初始化
  onResume() → reloadFromDisk()             ← 单一数据源刷新
  → config 和 storage 同源，不再依赖调用顺序
```

### 三条规则

1. **分清"需要什么"→ 决定"在哪里初始化"**
   - 只需要 Context → `onCreate()`
   - 需要视图 → `onViewCreated()`

2. **UI 是观察者，不是控制器**
   - Fragment → 发指令(Intent) → Service 自主执行
   - Fragment → 读 config → 显示状态

3. **单一数据源**
   - config 和由它派生的对象来自同一次 `CaptureConfig.load()` 调用

## 构建与安装

### 环境要求

- Android Studio Hedgehog (2023.1) 或更高
- JDK 17
- Android SDK 34

### 步骤

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成
3. 连接 Android 手机（开启 USB 调试）
4. 点击 Run 按钮

### 权限说明

| 权限 | 用途 | 何时需要 |
|------|------|---------|
| CAMERA | 拍照 | 使用相机时 |
| FOREGROUND_SERVICE | 前台服务保活 | 开始拍摄时 |
| FOREGROUND_SERVICE_CAMERA | 前台服务类型声明 | Android 14+ |
| RECEIVE_BOOT_COMPLETED | 开机自启 | 勾选自动启动后 |
| WRITE_EXTERNAL_STORAGE | 写 DCIM（API 26-28） | 仅选 DCIM 存储且 API ≤ 28 |
| POST_NOTIFICATIONS | 通知权限 | Android 13+ |

## 使用指南

1. **设置参数**：打开 App → 设置 Tab → 配置拍摄间隔、摄像头、水印、存储位置
2. **权限确认**：设置 Tab → 权限与保活 → 逐项授权
3. **构图对准**：预览 Tab → 实时预览 → 立即拍一张验证效果
4. **开始拍摄**：状态 Tab → 点击"开始" → 通知栏出现倒计时
5. **查看状态**：状态 Tab → 倒计时 + 最近照片缩略图 + 电量/存储
6. **浏览照片**：相册 Tab → 网格照片列表

## 存储路径说明

| 存储位置 | 路径 | 权限 | 卸载后 |
|---------|------|------|--------|
| App 私有目录 | `/Android/data/.../files/Pictures/TimeLapse/` | 无 | 删除 |
| 系统相册 (DCIM) | `/DCIM/TimeLapse/` | API 26-28 需权限 | 保留 |
| SD 卡 | SD 卡 App 私有目录 | 无 | 删除 |

## 扩展方向

- 迁移 SharedPreferences → DataStore（练习异步配置管理）
- 相册延时连播（按顺序快速播放照片序列）
- 云端备份（加密上传照片到云存储）
- FIFO 自动删旧（存储满时自动删除最早的照片）
- 国产 ROM 自启动跳转适配（按 Build.BRAND 分发 Intent）
- 深色模式适配
- 单元测试（CaptureConfig、WatermarkProcessor、PhotoStorageFactory）
- ProGuard/R8 规则（Release 构建混淆）
