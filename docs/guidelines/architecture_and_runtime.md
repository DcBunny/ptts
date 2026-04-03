# 架构与运行时规范

## 1. 状态管理
- 默认采用 MVVM 分层（`view + view_model + data`）。
- 状态管理优先使用 Riverpod（建议使用 `riverpod_generator` 生成 Provider）。
- 页面只做渲染与事件触发：
  - `ref.watch(...)` 用于渲染状态。
  - `ref.read(...notifier)` 用于触发动作。
  - 一次性副作用（Toast/弹窗/导航）使用 `ref.listen(...)`。
- 有订阅、定时器、流、取消令牌时，必须在销毁时释放。

## 2. 路由规范
- 路由统一维护在 `lib/core/router/`。
- 页面跳转由 `view` 层触发，`view_model` 不直接依赖 `BuildContext`。
- 路由命名使用语义化小写路径（如 `/login`、`/home`）。

## 3. 国际化规范
- 用户可见文案禁止硬编码在业务代码中。
- i18n 资源统一维护，页面通过统一入口读取文案。
- 新增文案时同步补齐 key、生成代码与引用点。

## 4. 本地存储规范
- 本地 key-value 读写统一通过 `core` 层封装入口。
- 业务层禁止直接散落调用底层存储 API。
- 敏感信息不得写入明文轻量存储。

## 5. 运行时错误处理
- 异步流程必须显式处理 `loading/success/error`。
- 对外部依赖错误（网络、插件、解析）统一做错误码或错误类型映射。
- 全局异常建议在 `main.dart` 统一拦截并上报。
