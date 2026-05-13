# 代码风格与质量规范

## 1. Kotlin 基础风格
- 遵循 Kotlin 官方风格与 Android Studio 默认格式化规则。
- 类型与 Composable 使用 `PascalCase`；变量、方法、参数使用 `camelCase`。
- 常量使用 `PascalCase` 或语义化对象收敛，例如 `JumpSessionDefaults.MinDurationSeconds`。
- 命名优先表达业务语义，避免 `data1`、`tmp` 等低语义命名。

## 2. Compose 风格
- 页面级 Composable 命名为 `<Feature>Screen`，尽量保持参数显式，例如 `onExit`、`onStart`。
- 可复用 UI 组件优先保持在 feature 内；跨 feature 复用达到稳定后再上移到 `core` 或设计系统。
- 页面状态应从 `presentation` 层输入；临时 UI-only mock state 可以保留在页面内，但必须避免接入真实业务副作用。
- 需要测试或无障碍访问的按钮、图标按钮和动态文本必须提供 `contentDescription` 或稳定语义。

## 3. 注释规范
- 注释优先说明“为什么”，不复述代码字面含义。
- 复杂兼容逻辑、平台限制、临时 mock 行为需要补充上下文。
- 临时方案统一格式：`TODO(责任人/日期): 说明与移除条件`。
- 修改逻辑时必须同步更新注释，注释与实现不一致视为缺陷。

## 4. 复杂度控制
- 单个非 UI 函数建议不超过 50 行。
- 单文件建议不超过 500 行；接近上限时按页面、组件或状态职责拆分。
- 嵌套层级建议不超过 4 层，必要时拆成私有 Composable 或纯函数。

## 5. 质量底线
- 禁止在业务代码直接使用 `println`；日志应统一封装入口后再使用。
- 用户可见文案禁止散落硬编码，优先维护在 `res/values/strings.xml`。
- 新增逻辑默认考虑可测试性，纯业务规则优先写成本地单元测试。
