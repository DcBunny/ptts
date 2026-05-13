# 项目结构与研发流程规范

## 1. 目录组织
- `app/src/main/java/com/example/ptts/core/`：跨功能公共能力。
  - `app/`：Compose 应用入口。
  - `navigation/`：路由常量与导航图。
  - `designsystem/` 或 `ui/theme/`：主题、颜色、字体、通用组件。
- `app/src/main/java/com/example/ptts/features/<feature>/`：按功能模块组织代码。
  - `ui/`：页面与 feature 内组件。
  - `presentation/`：UI 状态、事件、ViewModel 或 UI-only mock state。
  - `domain/`：可选，稳定业务规则与用例。
  - `data/`：可选，仓储、外部依赖、持久化与协议转换。
- `app/src/test/`：本地单元测试。
- `app/src/androidTest/`：仪器测试与 Compose UI 测试。
- `docs/guidelines/`：项目规范文档。

## 2. 分层职责
- `ui` 负责渲染、收集用户动作并触发事件，不直接实现复杂业务流程。
- `presentation` 负责页面状态、交互编排、一次性 UI 事件与 ViewModel。
- `domain` 只表达业务规则，不依赖 Android framework。
- `data` 负责外部能力、平台 API、持久化与数据映射。
- 不满足复用条件时，不提前抽到 `core`；优先保持在 feature 内演进。

## 3. 开发命令
- 编译调试包：`./gradlew :app:assembleDebug`
- 本地单元测试：`./gradlew :app:testDebugUnitTest`
- 仪器测试：`./gradlew :app:connectedDebugAndroidTest`
- 查看 Gradle 任务：`./gradlew :app:tasks`

## 4. 功能开发流程
1. 明确功能边界、输入输出、验收标准和是否需要平台能力。
2. 在 `features/` 下新增模块骨架，再按需补充 `ui/presentation/domain/data`。
3. 先跑通最小可见闭环，再补异常状态、边界状态与可访问性标识。
4. 完成后执行编译和测试命令。
5. 若新增公共约束或目录约定，同步更新本规范。
