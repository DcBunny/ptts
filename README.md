# tiaosheng

`tiaosheng` 是一个面向亲子跳绳训练场景的 Flutter 应用，当前聚焦“训练前准备 + 家长辅助拍摄 + 训练结果保存”的最小可运行闭环。项目希望先把跳绳训练流程跑通，再逐步扩展自动计数、动作识别和更多训练数据能力。

## 项目简介

应用围绕“小朋友跳绳训练记录”这一核心场景设计。用户先在首页选择本次训练时长，随后进入家长拍摄页面完成取景、倒计时和录制；训练结束后，应用会生成本次训练结果，并支持保存本地视频与历史记录，方便后续回看和对比成绩。

目前项目处于第一阶段实现，重点是验证产品流程、架构拆分和端侧能力接入，优先保证页面、状态、存储与拍摄链路可以稳定协作。

## 核心内容

### 1. 跳绳训练会话

- 支持训练时长选择，当前以 10 秒起步、30 秒为步进调整。
- 首页展示最佳记录，帮助用户快速了解最近训练表现。
- 会话状态由 Riverpod 统一管理，为后续扩展训练中状态机和自动计数预留空间。

### 2. 家长辅助拍摄

- 提供独立的家长拍摄页，引导家长将孩子置于取景框内。
- 支持开始前 3 秒倒计时，随后自动进入录制流程。
- 当前可通过点击方式手动累计跳绳次数，便于先完成训练流程验证。

### 3. 训练结果与视频处理

- 训练结束后展示本次成绩摘要，包括次数、时间和视频状态。
- 已接入本地视频录制与保存到系统相册的能力。
- 为训练视频叠加节奏提示和训练信息预留了处理链路，方便后续增强回放体验。

### 4. 本地数据沉淀

- 训练记录通过本地存储持久化保存。
- 支持读取历史记录并在首页展示最佳成绩。
- 当前数据层已经拆分为模型、仓储和存储封装，便于后续替换实现方案。

## 技术实现

- 框架：Flutter
- 状态管理：Riverpod
- 路由：GoRouter
- 国际化：自定义 i18n 入口
- 本地存储：SharedPreferences 封装
- 拍摄能力：`camera`
- 数据建模：Freezed / JSON Serializable

项目按照 `feature` 维度组织代码，核心业务位于 `lib/features/`，通用能力沉淀在 `lib/core/`，当前已经形成“首页训练配置 + 家长拍摄页 + 本地存储”的基本架构骨架。

## 目录结构

```text
lib/
├── core/
│   ├── app/
│   ├── i18n/
│   ├── router/
│   └── storage/
└── features/
    ├── jump_session/
    │   ├── data/
    │   ├── view/
    │   └── view_model/
    └── parent_camera/
        ├── data/
        ├── view/
        └── view_model/
```

## 开发规范

- 规范入口：`docs/guidelines/开发规范索引.md`
- 建议阅读顺序：
  - `docs/guidelines/project_structure_and_workflow.md`
  - `docs/guidelines/code_style_and_quality.md`
  - `docs/guidelines/architecture_and_runtime.md`
  - `docs/guidelines/ai_collaboration_and_skills.md`
  - `docs/guidelines/contribution_and_pr.md`

## 本地开发

```bash
flutter pub get
flutter run
```

提交前建议依次执行：

```bash
dart format .
flutter analyze
flutter test
```
