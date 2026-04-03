# 项目结构与研发流程规范

## 1. 目录组织
- `lib/core/`：跨业务公共能力（主题、路由、网络、存储、日志等）。
- `lib/features/<feature_name>/`：按功能模块组织业务代码。
- `lib/features/<feature_name>/` 建议结构：
  - `view/`：页面与组件。
  - `view_model/`：状态编排与交互逻辑。
  - `data/`：数据层（接口、仓储、模型）。
- `test/`：按模块镜像组织测试代码。

## 2. 分层职责
- `view` 负责渲染与交互触发，不直接编排复杂业务流程。
- `view_model` 负责状态转换、异常处理、业务编排。
- `data` 负责外部依赖调用、协议转换、持久化细节。
- 不满足复用条件时，不提前抽到 `core`；优先保持在 feature 内演进。

## 3. 开发命令
- 依赖安装：`flutter pub get`
- 代码生成（如使用 Freezed/Riverpod/JsonSerializable）：`flutter pub run build_runner build --delete-conflicting-outputs`
- 静态检查：`flutter analyze`
- 单元测试：`flutter test`
- 本地运行：`flutter run`

## 4. 提交前检查
- 必须通过：`flutter analyze`。
- 若存在测试用例，必须通过：`flutter test`。
- 涉及代码生成规则变更时，需重新生成并提交派生文件。

## 5. 功能开发流程
1. 明确功能边界、输入输出、验收标准。
2. 在 `features/` 下新增模块骨架，再补充 `view/view_model/data`。
3. 先跑通最小闭环，再补异常状态与边界处理。
4. 完成功能后执行检查命令并补充必要测试。
5. 提交 PR 时同步更新文档（如新增公共约束）。
