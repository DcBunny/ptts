# 架构与运行时规范

## 1. UI 与状态
- 默认采用 Compose + MVVM 分层。
- 页面只做渲染和事件触发；真实业务状态由 ViewModel 暴露为不可变 UI state。
- UI-only 原型可以使用 `remember` 管理本地状态，但不得混入相机、存储、网络、识别等真实业务副作用。
- 有计时器、流、回调或平台资源时，必须绑定生命周期并在销毁时释放。

## 2. 导航规范
- 路由统一维护在 `core/navigation/`。
- 页面之间通过事件回调触发导航，feature 页面不直接持有全局 `NavController`。
- 路由名称使用语义化小写字符串，例如 `session`、`parent_camera`。

## 3. 资源与文案
- 用户可见文案维护在 Android resources，并通过 `stringResource` 使用。
- 颜色、字体和尺寸优先收敛到主题或 feature 内常量，避免跨文件复制魔法值。
- 图标优先使用 Compose Material Icons；需要自定义图形时优先用 Canvas 或矢量资源。

## 4. 平台能力
- 相机、媒体库、传感器、权限、文件系统等平台能力必须集中在 data 或 platform-facing 层。
- UI 层不得直接申请权限或操作外部资源；应通过 ViewModel 或平台服务包装。
- 外部依赖错误需要映射成稳定 UI state，不把底层异常直接展示给用户。

## 5. 测试策略
- 纯函数、状态转换、格式化逻辑写本地单元测试。
- Compose 页面主流程、导航、关键按钮与动态状态写仪器测试。
- 新增平台能力时补充权限拒绝、设备不可用、资源释放等异常场景验证。

## 6. 跳绳识别与计数（parent_camera）
数据链路固定为：`CameraX ImageAnalysis → PoseFrameAnalyzer(ML Kit) → JumpCounter → ParentCameraViewModel → UI`。
改动这条链路时遵守以下约束：

### 6.1 采样率是输入的一部分
- 姿态推理与录像并行时，实际帧率通常在 8~20fps，且随设备波动。**任何毫秒级窗口都不能写成固定常量**：丢帧容错、Rising/Airborne/Landing/跳跃总时长、最小滞空时间都由 `frameIntervalBudget()` / `computeMax*Ms()` 按观测帧间隔推导，常量只作为下界。
- 新增时间窗口时，用同一套 `compute*` 帮助函数，不要直接比较固定毫秒数。

### 6.2 计数只增不减
- `count = confirmedCount + estimatedCount` 是展示给用户的成绩，**任何状态重置（姿态丢失、恢复窗口超时、基线重建）都不得回退它**。
- 节奏估算（`estimatedEventTimes`）只能通过"认领为确认计数"来转换，不能被清零。

### 6.3 基线、尺度与校准
- 站立基线来自 `calibrate()`，且只在连续静止窗口内才被认定为有效；校准期间检测到移动会重新开始采样。
- `estimateScale()` 的输出是阈值归一化基准，只用躯干长度等刚性量，不要用随跳跃变化的髋-脚距离。
- **已知局限**：连续跳绳时孩子处于永久半蹲状态，相对"站立基线"存在恒定下沉量，会被 `coerceAtLeast(0f)` 截断，导致小幅度跳跃被低估。彻底解决需要改为周期性信号（带通 + 峰值/自相关）方案，属于后续工作。

### 6.4 相机运动
- `CameraMotionEstimator` 仅用于判断画面是否在移动，**不得用它平移关键点**：平移会抵消掉孩子真实的竖直位移。
- 恢复窗口必须能自行结束（`computeMaxRecoveryMs()`），否则手持拍摄会让计数永久停摆。

### 6.5 阈值学习
- `adaptivePeakLift` 从**所有**通过最低幅度门槛的跳跃候选学习（不只是计数成功的），否则低于初始门限的孩子永远无法让计数器学会接受他的幅度。
- 学习值只用于收敛门限，`landing` 门限保持固定并夹在 `(ground, airborne)` 之间，保证相位机可达。

### 6.6 真实数据闭环
- Debug 构建会在每次录制结束时把逐帧诊断写入 `cacheDir/jump_diagnostics/`（`JumpSessionDiagnosticRecorder.exportForSession`），保留最近 20 次。
- 诊断格式为按行制表符分隔，字段见 `JumpSessionDiagnosticRecorder.exportTo`，包含原始/平滑 lift、峰值、拒绝原因、帧间隔统计、学习到的幅度、录制阶段、关键点坐标/置信度、相机运动与当前身体/脚部信号来源。
- **调整任何阈值前，先用这些真实数据回放验证**，不要只依赖合成信号的单元测试。

### 6.7 夜间自动增强
- `FrameLightMetricsCalculator` 只读取分析帧 Y 平面，使用最近可靠的人体区域；区域过期后回退中央区域，并在采样时处理旋转、裁剪和行步长。
- `AutoLowLightStrategy` 是无 Android 依赖的状态机：暗光持续约 1 秒才发起调整，每档观察约 2 秒，环境恢复约 3 秒后恢复原曝光。所有窗口按分析帧时间戳推进。
- `JumpCameraController` 串行执行 CameraX 低光增强、曝光补偿和 AE 区域测光请求；系统低光增强未实际进入 ACTIVE 或异步失败时，自动回退到设备支持的曝光档位，二者不叠加。
- 增强状态与计数状态独立展示。Debug 诊断同时记录亮度、区域、实际增强状态、曝光档位和回退原因，便于用真实夜间会话回放调参。
