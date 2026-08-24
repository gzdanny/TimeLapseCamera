---
name: "android-production-teaching-project"
description: "Build high-quality, genuinely useful Android applications as complete teaching projects, not API demos. Use this skill when preparing an Android course project that should meet real-world development standards while preserving the development process, technical decisions, trade-offs, failures, and reasoning as teaching documentation. Follow a requirements-first, research-driven, iterative workflow from project definition through implementation, review, documentation, and release."
---

# Android 教学项目开发指南

基于真实项目开发经验提炼的方法论，覆盖从选题到发布的全流程。

## 适用场景

- 准备 Android 开发课程的实战项目
- 需要一个完整、可教学、代码质量过关的示范项目
- 项目要求结构清晰、模块解耦、可作为学生参考

## 全流程概览

```
1. 选题与需求   → 需求驱动，不是技术驱动
2. 架构设计     → 接口优先，模块边界清晰
3. 最佳实践调研 → 每个技术决策前查文档，不凭记忆
4. 最小实现     → 先做能跑的版本，不追求完美
5. 迭代优化     → 每步由真实问题驱动
6. 硬化加固     → 失败回退、输入校验、边界处理
7. UI 重构      → 功能分区、用户体验
8. 多轮审查     → 每轮关注不同层次
9. 发布审计     → 废弃资源清理、文档同步
10. 持续集成     → GitHub Actions 云端编译、发布
```

**调研是贯穿全程的习惯**：不只在做架构设计时查一次，而是每当遇到"该用 A 还是 B"、"这个 API 在新版本还推荐吗"、"有没有更现代的写法"时，都先查文档再写代码。

## 1. 选题与需求

### 选题标准

| 标准 | 说明 | 反例 |
|------|------|------|
| 真实需求驱动 | 解决一个真实问题，不是"为了教 X 而写 Demo" | "为了教 Camera2 写个相机" |
| 自然覆盖知识点 | 选题本身需要 Service、存储、通知、权限等 | 纯计算器 App 不涉及系统服务 |
| 有约束但不 trivial | 有真实取舍（功耗 vs 功能、兼容性 vs 现代化） | 无约束的"自由发挥"项目 |
| 可在课时内完成 | 核心逻辑 10-15 个文件，不超过 2000 行 | 企业级 App |

### 需求讨论先于编码

在写任何代码前，必须明确：
- 目标设备和 Android 版本（影响 API 选择和权限策略）
- 核心使用场景（用户打开 App 后做什么）
- 长期运行还是一次性（影响 Service 设计和保活策略）
- 失败时用户看到什么（影响错误处理设计）

**产出物**：一段需求描述 + 一份功能清单 + 约束条件列表。

## 2. 架构设计

### 接口优先原则

为"会被替换的边界"设计接口，不给不会替换的部分过度抽象：

| 边界类型 | 是否需要接口 | 判断依据 |
|---------|------------|---------|
| 相机实现（Camera2/CameraX） | 是 | 版本演进、厂商兼容 |
| 存储实现（本地/DCIM/云） | 是 | 用户选择、未来扩展 |
| 水印处理器 | 否 | 逻辑稳定，不会替换 |
| 配置管理 | 否 | 内部模块，调用方固定 |

### 模块职责单一

每个模块只做一件事，通过接口与其他模块交互：

```
config    → 纯数据，不依赖任何模块
camera    → 只依赖配置，接口对外
storage   → 只依赖配置，接口 + 工厂对外
watermark → 只依赖 Bitmap + 配置
service   → 通过接口编排各模块
ui        → 发指令给 Service，读配置显示状态
```

### 工厂模式

当同一接口有多个实现时，用工厂封装选择逻辑：
- 调用方只依赖接口，不知道具体实现
- 新增实现只改工厂，不改调用方
- 工厂可以检测运行时条件（如 SD 卡是否存在）

## 3. 最佳实践调研

### 为什么要调研

Android 最佳实践迭代很快，凭记忆写代码几乎一定会写出过时的方案：

| 领域 | 过时写法 | 现代写法 | 迁移时间 |
|------|---------|---------|---------|
| 相机 | Camera2 手写 | CameraX | 2020+ |
| 权限请求 | `requestPermissions()` | `ActivityResultContracts` | 2020+ |
| 存储 | `getExternalStorageDirectory` | Scoped Storage + MediaStore | 2020+ |
| 协程+Future | `.get()` 阻塞 | `.await()` 挂起 | 2019+ |
| 配置持久化 | SharedPreferences | DataStore | 2020+ |
| 导航 | FragmentTransaction | Navigation Component | 2018+ |
| 通知 | setLatestEventInfo | 前台服务 + NotificationChannel | 2018+ |

**教学项目尤其不能教过时方案**——学生学了过时写法，毕业后还要重新学，这是误导。

### 调研的时机

不是只查一次，而是在以下时刻都要查：

| 时机 | 典型问题 | 调研重点 |
|------|---------|---------|
| 架构设计时 | "用 Camera2 还是 CameraX？" | Google 官方推荐、兼容性、代码量 |
| 迭代优化时 | "AlarmManager 会不会被杀？" | 国产 ROM 保活策略、前台服务最佳实践 |
| 硬化加固时 | "DCIM 怎么写？" | Scoped Storage 规则、MediaStore API 版本差异 |
| UI 重构时 | "registerForActivityResult 什么时候能调？" | AndroidX 生命周期绑定机制 |
| 遇到不确定的 API | "ListenableFuture 该怎么等？" | 协程适配库、阻塞 vs 挂起 |

### 调研的方法

1. **查官方文档**：developer.android.com 是第一信源，优先看 Guide 而非 API Reference
2. **查 API 版本**：确认目标 API 在 minSdk 和 targetSdk 之间可用
3. **查替代方案**：如果某个 API 已废弃，官方文档通常会指向替代方案
4. **查边界条件**：不同 Android 版本、不同厂商 ROM 的行为差异
5. **做判断而非照搬**：文档给的是"推荐做法"，要结合项目约束（旧手机、教学价值）做取舍

### 核心原则：查是为了决策，不是选最新

**调研的目的是看清楚所有选项和它们的利弊，最终决策依据是项目需求，不是"哪个最新"。**

有时最合适的方案恰恰不是最新的。盲目追求新版本反而会引入不必要的复杂度或兼容性问题。

#### 本项目"选旧不选新"的真实案例

| 决策 | 最新方案 | 实际选择 | 为什么不选最新的 |
|------|---------|---------|----------------|
| 配置持久化 | DataStore | SharedPreferences | API 更简单，学生容易理解；DataStore 的优势（异步、类型安全）在这个场景不明显 |
| 后台调度 | WorkManager | 前台服务 + AlarmManager | WorkManager 对精确时间拍摄支持不好，且引入额外依赖；旧 API 更可控 |
| 最低 API 版本 | 最新 Android 14 | API 26 (Android 8.0) | 项目目标是旧手机，minSdk 越低覆盖越广 |
| DCIM 写入 (API 26-28) | MediaStore | File API + WRITE_EXTERNAL_STORAGE | MediaStore 的 RELATIVE_PATH 是 API 29+，低版本必须用 File API |

#### 决策框架

```
查文档 → 列出所有可选方案 → 逐个评估：

  1. 兼容性：目标设备支持吗？minSdk 够吗？
  2. 复杂度：引入这个方案要加多少代码/依赖？
  3. 教学价值：学生能从中学到什么？会不会因为太抽象而困惑？
  4. 维护成本：这个方案未来会废弃吗？厂商适配成本高吗？
  5. 项目约束：旧手机能跑吗？内存够吗？功耗达标吗？

→ 选最合适的，不是最新的
```

#### 什么时候选最新

当旧方案有**明确缺陷**时才选最新：
- 旧方案会导致崩溃或数据丢失 → 必须换
- 旧方案已废弃且未来不被维护 → 应该换
- 旧方案代码量显著更大且新版完全替代 → 值得换
- 旧方案在目标设备上不工作 → 必须换

**如果旧方案只是"不够现代"但功能完全正常，换不换取决于教学目标和项目约束。**

### 调研记录

每次调研后，在代码注释或 README 中记录：
- 选了什么方案
- 为什么选（而不是另一个）
- 这个方案的版本限制是什么

这样学生在看代码时能看到"为什么用 CameraX 而不是 Camera2"的理由，而不是只看到结果。

### 本项目的调研案例

| 决策 | 调研了什么 | 最终选择 | 理由 |
|------|-----------|---------|------|
| Camera2 vs CameraX | Google 推荐、代码量、预览复杂度 | CameraX | 代码少 40%、Preview 用例简化预览 |
| AlarmManager alone vs 前台服务 | 国产 ROM 杀进程策略 | 前台服务 + AlarmManager 备份 | 单一 AlarmManager 在国产 ROM 不可靠 |
| File API vs MediaStore (DCIM) | Android 11+ Scoped Storage | API 29+ MediaStore / API 26-28 File | 最佳实践，无需额外权限 |
| `.get()` vs `.await()` | 协程 + ListenableFuture 互操作 | `.await()` + `kotlinx-coroutines-guava` | `ProcessCameraProvider.getInstance()` 返回 `ListenableFuture`，`guava` 库提供 `await()` 扩展；`play-services` 只支持 `Task<T>`，类型不匹配 |
| `requestPermissions` vs ActivityResultContracts | AndroidX 推荐方式 | ActivityResultContracts | 官方推荐，无需手动管理 requestCode |

## 4. 最小实现（v1）

### 目标

能跑、能验证核心逻辑，不追求完美：
- 用最简单的 API 实现（Camera2 比 CameraX 代码多，但先跑起来）
- 用最简单的 UI（单页平铺，不用导航）
- 不做错误处理（先让 happy path 工作）
- 不做优化（先确认功能正确）

### 教学价值

v1 的粗糙恰好是教学起点——学生先看到"能跑但不优雅"的版本，再在后续迭代中理解每一步优化的必要性。

## 5. 迭代优化

### 每步由真实问题驱动

不要"为优化而优化"。每次优化必须能回答"解决什么问题"：

| 优化方向 | 驱动问题 | 教学知识点 |
|---------|---------|-----------|
| 保活策略 | "进程被杀怎么办？" | 前台服务、START_STICKY、AlarmManager |
| 内存优化 | "Bitmap 占多少内存？" | inMutable、ARGB_8888、责任链 |
| 失败回退 | "摄像头坏了怎么办？" | sealed class、异常处理、降级策略 |
| 生命周期 | "lateinit 何时初始化？" | Fragment 双重生命周期、单一数据源 |
| 存储扩展 | "怎么写到 DCIM？" | Scoped Storage、MediaStore、工厂模式 |
| 输入校验 | "用户乱输入怎么办？" | 防御性编程、用户反馈 |

### 迭代节奏

每轮迭代：讨论方案 → 调研最佳实践 → 确认方向 → 实现代码 → 审查同步文档。不要跳过讨论和调研直接编码。

### Git 提交时机

**以"功能点验收通过"为提交粒度，不是"改了几个字符就提交"。**

| 做法 | 说明 | 问题 |
|------|------|------|
| ❌ 改两行就提交 | commit 粒度太细，历史破碎 | 无法 revert 单个功能，review 困难 |
| ❌ 攒一大堆才提交 | commit 粒度太粗，混合多个功能 | 出 bug 时无法定位是哪个改动引入的 |
| ✅ 一个功能点验收通过后提交 | 功能完整、审查通过、可独立运行 | 历史清晰，可 revert，可 review |

**"功能点"的定义**：
- 一个完整的用户可感知行为（如"水印加电量信息"）
- 或一个完整的架构改动（如"Camera2 迁移到 CameraX"）
- 不是"加了两个 import"或"改了一个变量名"

**提交流程**：
```
实现功能 → 自查 → 独立审查 agent 审核 → 通过 → git commit
         ↑                                              ↓
         ←────────── 审查不通过，回到修改 ←──────────────
```

**不要在审查未通过时提交**——否则提交了又要改，历史里就有一堆"fix review comments"的噪音提交。

## 6. 硬化加固

### 失败回退设计

长期运行的 App，失败处理比成功路径更重要：

- 每个环节都要问"失败了怎么办"
- 失败要有用户可见的反馈（黑图占位比 Log 文件更直观）
- 失败不能导致崩溃（runCatching 兜底）
- 失败要释放资源（避免互锁）

### 输入校验（不是可选的）

所有用户输入必须校验，三层防御：
1. XML 层：maxLength、inputType 限制输入格式
2. 代码层：范围检查、格式检查、实际请求验证
3. 用户反馈：Toast 提示修正原因

**这是基础安全要求，必须在第一版就有，不能等"优化阶段"补。**

## 7. UI 设计

### 导航结构选择

| 结构 | 适用场景 | 教学价值 |
|------|---------|---------|
| 单页滚动 | 功能少（3个以内）、核心是查看状态 | 简单但不展示 Fragment |
| 底部导航 | 功能模块平级（3-5个）、需要分区 | Fragment + 导航的标准模式 |
| 汉堡菜单 | 功能多、有层级 | 入口隐蔽，学生可能找不到 |

推荐底部导航——Fragment + Navigation 是 Android 标准模式，教学价值最高。

### UI 与业务分离

```
❌ Fragment → 触发业务 → 直接控制
✅ Fragment → 发指令(Intent) → Service 自主执行
   Fragment → 读配置 → 显示状态
```

Fragment 是观察者，不是控制器。

## 8. 多轮审查

审查是多轮的，每轮关注不同层次：

| 轮次 | 审查重点 | 典型问题 |
|------|---------|---------|
| 第 1 轮 | 功能正确性 | 未使用依赖、错误信息丢失 |
| 第 2 轮 | 运行时安全 | OOM 风险、路径错误、线程泄漏 |
| 第 3 轮 | 架构一致性 | 接口依赖是否被破坏、命名是否清晰 |
| 第 4 轮 | 生命周期安全 | lateinit 时机、config/storage 同步 |
| 第 5 轮 | 废弃资源 | 未使用字符串、过时权限、冗余注释 |
| 第 6 轮 | 文档同步 | README 与代码是否一致、注释是否过时 |

**一轮审查不够。** 每次审查都会发现新问题，这是正常的。

### 独立审查原则：不要让开发者自己审自己

**正在开发的 AI agent 不能审查自己的代码。** 原因：

- 开发者会"自圆其说"——对自己写的代码有路径依赖，倾向于认为它是对的
- 写代码时的思维框架会影响审查视角，容易在自己的逻辑盲区里打转
- 独立的审查者没有"我当初为什么这样写"的包袱，更容易发现真正的问题

**正确做法**：开发完成后，另开一个**只读审查 agent**（Explore 类型，不修改代码），让它从零开始读代码、提意见。开发者根据审查意见决定是否修改。

这在实践中非常有效——本项目中大量问题（lateinit 崩溃、config/storage 不同步、废弃字符串等）都是独立审查发现的，而不是开发者自查发现的。

### 审查清单

每次审查时逐项检查：
- [ ] 所有 import 都在使用
- [ ] 所有字符串资源都有引用
- [ ] 所有权限都声明且需要
- [ ] 接口依赖没有被具体类破坏
- [ ] 注释中引用的类名仍然存在
- [ ] README 架构图与代码一致
- [ ] lateinit 在最早安全位置初始化
- [ ] 用户输入有校验
- [ ] 失败路径有兜底不崩溃
- [ ] 每个方法调用的参数类型与变量声明类型匹配（含可空性）
- [ ] 每个引用的 API 常量/方法在目标 SDK 版本确实存在
- [ ] 传入接口参数的对象确实实现了该接口（检查继承层次，不是假设）

### 瘦客户端环境下的类型审查：独立审查就是编译器

在瘦客户端（Chromebook、iPad）或无 Android Studio 的环境下，学生无法本地编译。此时**独立审查 agent 是唯一的编译器**——审查必须覆盖编译器会检查的类型问题：

| 编译器检查项 | 审查 agent 如何替代 |
|-------------|-------------------|
| 类型不匹配 | 查方法签名的参数类型，与传入变量声明类型对比 |
| 可空类型传给非空参数 | 检查变量是否有 `?`，传给的方法参数是否允许 null |
| API 常量不存在 | 查官方文档确认常量/方法在目标 SDK 中存在 |
| 类不实现接口 | 查类继承层次，确认满足方法签名要求 |
| import 的扩展函数适用类型错误 | 查扩展函数的 receiver 类型，与调用对象类型对比 |

**本项目真实案例**：5 个编译错误全部在 push 前通过类型审查就能发现，但当时跳过了独立审查直接 push，导致 6 轮 CI 构建才全部修复。

| 错误 | 编译器报错 | 审查 agent 如何提前发现 |
|------|-----------|----------------------|
| `tasks.await()` 用于 `ListenableFuture` | Unresolved reference | 查 `getInstance()` 返回 `ListenableFuture`，查 `tasks.await` 适用于 `Task<T>`，类型不匹配 |
| `PendingIntent?` 传给非空参数 | Type mismatch | `buildPendingIntent()` 返回 `PendingIntent?`，方法要求 `PendingIntent`，有 `?` |
| `BATTERY_PROPERTY_TEMPERATURE` 不存在 | Unresolved reference | 查 `BatteryManager` 文档，常量列表中无此项 |
| `ImageCapture?` 传给 `bindToLifecycle` | None of the following functions | 变量声明 `var ImageCapture?`，方法参数要求非空 `UseCase` |
| `LifecycleRegistry` 不是 `LifecycleOwner` | None of the following functions | 查类层次：`LifecycleRegistry` 继承 `Lifecycle`，不是 `LifecycleOwner` |

**结论：编译错误不需要编译才能发现。** 查 API 文档 + 检查类型声明 = 覆盖编译器的类型检查。在无编译环境时，这一步是必须的，不是可选的。

## 9. 文档编写

### README 结构

```
1. 项目简介（一句话说清楚做什么）
2. 核心特性（列点，不写废话）
3. UI 架构图（ASCII 图，直观）
4. 架构总览（模块关系图）
5. 目录结构（每个文件一行注释）
6. 关键设计决策（对比表 + 理由）
7. 失败回退机制（流程图 + 表格）
8. 深度专题（真实 bug 演变 > 规则灌输）
9. 构建与安装
10. 使用指南
11. 扩展方向（留给学生的练习题）
```

### 深度专题的价值

不要只写"应该怎么做"。写**真实的演变过程**：

```
阶段 1：原始写法（碰巧能工作）
  → 为什么不安全
阶段 2：重构暴露 bug
  → 为什么崩溃
阶段 3：正确模式
  → 为什么这样才对
```

学生看到犯错和修复的过程，比看到"正确答案"理解更深。

### 文档同步原则

**改代码的那次 Edit/Write，同时改对应的文档。** 不要攒着。文档过时都是在"改了代码没改注释"时发生的。

## 10. 持续集成与发布

### 为什么用 GitHub Actions 云端编译

教学项目可能限定学生在瘦客户端（Chromebook、iPad）上开发，本地没有 Android Studio 和 SDK。GitHub Actions 让学生在浏览器里写代码，push 后云端自动编译并产出 APK，实现 idea → product 的完整闭环。

### 配置前检查清单

写 workflow 前必须确认：

| 检查项 | 怎么查 | 不查的后果 |
|--------|--------|-----------|
| Gradle Wrapper 文件是否存在 | `ls gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar` | 用 `./gradlew` 会找不到文件 |
| `gradle-wrapper.properties` 指定的 Gradle 版本 | 读 `distributionUrl` | 不知道 Wrapper 会装哪个版本 |
| `build.gradle` 的 AGP 版本 | 读根 `build.gradle` 的 `plugins` 块 | AGP 和 Gradle 版本不兼容 |
| `app/build.gradle` 的 `compileSdk` | 读 `android` 块 | SDK packages 装错版本 |
| 每个 GitHub Action 的最新版本 | 查 actions 的 GitHub Releases 页面 | 用了废弃的 Node.js 20 版本 |

### Workflow 核心原则

**用 Gradle Wrapper，不强制安装 Gradle 版本。**

```yaml
# ❌ 不要这样——绕过了项目的 Wrapper，版本不受控
- name: Set up Gradle
  uses: gradle/actions/setup-gradle@v5
  with:
    gradle-version: '8.0'    # 强制指定版本，可能与 AGP 不匹配

- run: gradle assembleDebug   # 用系统安装的 gradle，不是 Wrapper

# ✅ 正确——让项目的 gradle-wrapper.properties 决定版本
- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v5    # 不带 gradle-version，只提供缓存

- name: Make gradlew executable
  run: chmod +x ./gradlew                   # Linux CI 需要执行权限

- run: ./gradlew assembleDebug              # 用 Wrapper，版本由项目控制
```

### 版本选择：查文档，不凭记忆

GitHub Actions 的 action 版本迭代很快。**凭记忆写版本号几乎一定会写出废弃版本。**

| Action | 凭记忆可能写的 | 查文档发现应该用的 | 废弃原因 |
|--------|-------------|----------------|---------|
| `actions/checkout` | @v4 | @v5 或更高 | v4 用 Node.js 20，2025-09 废弃 |
| `actions/setup-java` | @v4 | @v5 或更高 | 同上 |
| `actions/upload-artifact` | @v5 | @v6 或更高 | v5 用 Node.js 20 |
| `gradle/actions/setup-gradle` | @v4 | @v5 | v4 用 Node.js 20 |

**查的方法**：到该 action 的 GitHub 仓库 → Releases 页面 → 看最新版本号和 changelog。

**setup-gradle 的版本选择**：v6 把缓存组件改为闭源专有许可，v5 是最后一个 MIT 许可版本。开源项目优先选 v5，不选 v6。

### CI 失败时的调试 SOP

```
1. 立即定位详细日志
   └── 进入 https://github.com/{owner}/{repo}/actions
       → 点失败的 run → 展开失败的 step
       → 读完整错误信息，不要只看摘要

2. 分类错误
   ├── 环境问题（Node.js 废弃、缓存 400）→ 改 workflow
   ├── 编译问题（类型不匹配、API 不存在）→ 回本地改代码，不在 CI 上试错
   └── SDK 问题（platforms 不匹配）→ 对齐 compileSdk 和 packages

3. 编译错误：读全部错误，一次修完
   ├── 不只看第一个错误就 push
   ├── 同类问题一起查（如多个 nullable 参数问题）
   └── 找根因，不做表面修复
       例：报 ImageCapture? 不匹配 → 不只加 !!，要看变量为什么是 nullable
       例：报 bindToLifecycle 类型不匹配 → 不只改参数，要查 LifecycleRegistry 的继承层次

4. 修复后先自查再 push
   └── 按"瘦客户端类型审查"清单检查一遍
```

### 本项目的 CI 踩坑全记录

本项目从配置 CI 到首次成功构建，经历了 7 轮失败。每一轮的教训：

| 轮次 | 失败原因 | 教训 |
|------|---------|------|
| 1 | 多个 action 用 Node.js 20 废弃版本 | 配置前查每个 action 的最新版本，不凭记忆 |
| 2 | setup-gradle@v4 仍用 Node 20 | 升级要逐个验证，不能遗漏 |
| 3 | 5 个 Kotlin 编译错误 | push 前必须做独立类型审查 |
| 4 | 只修了报错行，漏了同类型错误 | 读全部错误，一次修完 |
| 5 | 表面修复（加 val），没发现根因（LifecycleRegistry 不是 LifecycleOwner） | 找根因，不做表面修复 |
| 6 | 漏看 warning（upload-artifact@v5 仍用 Node 20） | warning 也要修，不只看 error |

### 发布策略

```yaml
# 每次 push 到 main → 编译 + 上传 artifact（30 天临时）
# push tag v* → 编译 + 创建永久 GitHub Release
on:
  push:
    branches: [ main ]
    tags: [ 'v*' ]
```

**为什么不每次 push 都 release**：开发过程中有很多中间版本，不是每个都需要对外发布。tag 是明确的发布信号。

## 教学知识点覆盖清单

一个好的教学项目应该覆盖以下知识点（按重要性排序）：

### 必须覆盖
- [ ] Fragment 生命周期（onCreate → onViewCreated → onResume）
- [ ] Service（前台服务 + 通知 + START_STICKY）
- [ ] Coroutines（suspend、Dispatchers、Job、cancel）
- [ ] 权限（运行时权限 + ActivityResultContracts）
- [ ] 数据持久化（SharedPreferences / DataStore）
- [ ] ViewBinding
- [ ] 输入校验与防御性编程

### 建议覆盖
- [ ] 接口 + 工厂模式（模块解耦）
- [ ] sealed class + when（穷举处理）
- [ ] AlarmManager（精确闹钟 + 备份机制）
- [ ] CameraX（Preview + ImageCapture）
- [ ] Scoped Storage（MediaStore vs App 私有目录）
- [ ] Bitmap 内存管理（inMutable、责任链回收）

### 可选覆盖
- [ ] Navigation Component
- [ ] 图片加载库（Coil / Glide）
- [ ] 远程配置（HTTP 请求 + 超时处理）
- [ ] 电池/存储/温度监控
- [ ] ProGuard/R8 规则
- [ ] GitHub Actions CI/CD（云端编译 + 自动发布）

## 常见误区

| 误区 | 正确做法 |
|------|---------|
| 先选技术再找需求 | 先定需求，技术选择自然涌现 |
| 凭记忆写代码不查文档 | 每个技术决策前查官方文档，确认是否为当前最佳实践 |
| 第一版就追求完美 | 先做能跑的最小版本，再迭代优化 |
| 接口给所有模块做 | 只给"会替换的边界"做接口 |
| 审查一轮就发布 | 多轮审查，每轮关注不同层次 |
| 输入校验等"优化阶段"补 | 第一版就必须有 |
| 文档最后再写 | 随代码同步更新 |
| 只展示最终正确写法 | 展示从错误到正确的演变过程 |
| 用 `by lazy` 掩盖初始化问题 | `lateinit` + `onCreate()` 显式初始化 |
| 调研只做一次 | 贯穿全程，每个"该用 A 还是 B"的时刻都查 |
| 改几行就 git commit | 以功能点验收通过为提交粒度，不提交未通过审查的代码 |
| 开发者自己审自己 | 另开只读审查 agent，避免自圆其说 |
| 凭记忆写 CI workflow 版本号 | 查每个 action 的 GitHub Releases 页面，确认最新版本 |
| 没审查就 push 到 CI | 先做独立类型审查（查 API + 查类型），再 push |
| CI 失败只看第一个错误 | 读全部错误，同类问题一次修完 |
| 表面修复不改根因 | 查类型继承层次和 API 文档，找到真正的类型不匹配原因 |
| 用 CI 当编译器反复试错 | CI 是验证手段，不是调试工具；编译错误回本地改 |
| workflow 里强制 gradle-version | 用 Gradle Wrapper（`./gradlew`），让项目控制版本 |
| 只看 error 忽略 warning | warning 也要修（如 Node.js 废弃警告会变成 error） |
