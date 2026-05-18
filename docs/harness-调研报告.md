# Harness 深度调研报告

**Metadata**
- Date: 2026-05-18
- Topic: Harness Inc. DevOps Platform
- Tags: #harness #devops #ci-cd #gitops #ai-devops #平台工程

---

## 目录

1. [公司概览](#1-公司概览)
2. [创始人 & 融资历史](#2-创始人--融资历史)
3. [核心产品矩阵（全览）](#3-核心产品矩阵全览)
4. [AI DevOps 平台 — 核心技术支柱](#4-ai-devops-平台--核心技术支柱)
5. [产品模块深度分析](#5-产品模块深度分析)
6. [技术架构分析](#6-技术架构分析)
7. [竞争对手对比](#7-竞争对手对比)
8. [开源生态 & 社区](#8-开源生态--社区)
9. [定价模型](#9-定价模型)
10. [客户案例 & 行业认可](#10-客户案例--行业认可)
11. [2026年最新动态](#11-2026年最新动态)
12. [总结 & 建议](#12-总结--建议)

---

## 1. 公司概览

**Harness Inc.** 是一家总部位于美国旧金山的 **AI 驱动的软件交付平台**公司，成立于 2017 年。

| 维度 | 内容 |
|------|------|
| **全称** | Harness Inc. |
| **成立时间** | 2017 年 |
| **总部** | 美国旧金山（San Francisco, CA） |
| **创始人** | Jyoti Bansal（CEO） |
| **员工数** | 1200+ 人 |
| **客户数** | 1000+ 家企业客户 |
| **累计融资** | 超 5.7 亿美元 |
| **估值** | 约 37 亿美元（2022 年 Series D 后） |
| **定位** | 现代软件交付平台，用 AI 贯穿整个 DevOps 生命周期 |
| **官网** | https://harness.io |
| **开源项目** | https://github.com/harness |

### 一句话总结
> Harness 是一个 **端到端的 AI 驱动的软件交付平台**，覆盖从代码提交到生产部署的全流程 —— 包括 CI/CD、GitOps、特性管理、云成本、安全测试、混沌工程、开发者门户、制品管理等。

---

## 2. 创始人 & 融资历史

### 创始人：Jyoti Bansal

Jyoti Bansal 是一位连续创业者，之前最成功的公司是 **AppDynamics**（APM / 应用性能监控）。

| 项目 | 内容 |
|------|------|
| **AppDynamics** | 创立于 2008 年，2017 年被 Cisco 以 **37 亿美元** 收购 |
| **Harness** | 2017 年离开 Cisco 后创立，定位"下一代 DevOps 平台" |
| **投资方** | IVP、Menlo Ventures、GV（Google Ventures）、J.P. Morgan、Citi Ventures 等 |
| **其他项目** | Unusual Ventures（风险投资机构，他也是合伙人） |

### 融资历程

| 轮次 | 时间 | 金额 | 领投方 |
|------|------|------|--------|
| Series A | 2018 年 | 约 2000 万美元 | Menlo Ventures |
| Series B | 2019 年 | 约 4000 万美元 | IVP |
| Series C | 2020 年 | 8500 万美元 | IVP |
| Series D | 2022 年 | 1.75 亿美元 | IVP、GV、J.P. Morgan 等 |

**关键意义**：Harness 在 2022 年 Series D 后估值达约 37 亿美元。创始人之前的成功经历（AppDynamics → Cisco $3.7B）给予投资者极大信心。

---

## 3. 核心产品矩阵（全览）

Harness 的产品线极其丰富，覆盖 DevOps 的全生命周期。

```
                    Harness AI DevOps 平台
    ┌───────────────────────────────────────────────────────┐
    │              AI & Automation 层                        │
    │   Harness AI / DevOps Agent / Policy Agent / AI SRE   │
    └───────────────────────────────────────────────────────┘

    开发阶段                 交付阶段                 运营阶段
    ┌────────────┐    ┌──────────────┐    ┌───────────────┐
    │ Code Repo  │    │ CI           │    │ Cloud Cost    │
    │ Gitspaces  │    │ CD & GitOps  │    │   Management  │
    │ Artifact   │───→│ Feature      │───→│ AI SRE        │
    │   Registry │    │   Flags      │    │ Resilience    │
    │ IDP        │    │ IaCM         │    │   Testing     │
    │ SEI        │    │ DB DevOps    │    │ App Security  │
    │            │    │ AST/STO/SSC  │    │   (Runtime)   │
    └────────────┘    └──────────────┘    └───────────────┘
```

### 完整产品列表

| 序号 | 产品名 | 缩写 | 分类 | 一句话 |
|------|--------|------|------|--------|
| 1 | AI Test Automation | AITA | 测试 | AI 驱动的端到端测试自动生成与执行 |
| 2 | AI SRE | — | 可靠性 | AI 驱动的服务可靠性管理 |
| 3 | Application Security Testing | AST | 安全 | SAST/DAST 安全测试 |
| 4 | Artifact Registry | — | 制品管理 | 统一的制品仓库（Docker/Maven/npm 等） |
| 5 | Resilience Testing | — | 可靠性 | 混沌工程 + 负载测试 + 容灾测试 |
| 6 | Cloud Cost Management | CCM | 成本 | 云成本优化与治理 |
| 7 | Cloud Development Environments | — | 开发 | 云端开发环境（Gitspaces） |
| 8 | Code Repository | — | 代码 | 代码仓库（Git 托管） |
| 9 | Continuous Delivery & GitOps | CD | 交付 | 持续交付 + Argo CD 管理 |
| 10 | Continuous Integration | CI | 集成 | 持续集成 |
| 11 | Database DevOps | — | 交付 | 数据库变更管理 |
| 12 | Feature Flags & Experimentation | — | 特性管理 | 功能开关 / A/B 测试 |
| 13 | IaC Management | IaCM | 基础设施 | Terraform/OpenTofu 编排 |
| 14 | Internal Developer Portal | IDP | 平台工程 | 开发者自助门户（基于 Backstage） |
| 15 | Security Testing Orchestration | STO | 安全 | 安全测试编排（聚合多扫描器） |
| 16 | Software Engineering Insights | SEI | 洞察 | 交付效能度量（DORA 指标） |
| 17 | Supply Chain Security | SSC | 安全 | 供应链安全（SBOM/SLSA） |
| 18 | Web Application & API Protection | WAAP | 安全 | Web 应用与 API 保护 |

---

## 4. AI DevOps 平台 — 核心技术支柱

### 4.1 平台架构设计思路

Harness 不是一堆散装产品，而是一个 **统一的 AI DevOps 平台**。核心设计理念：

1. **统一数据层** — 所有产品共享同一个"Software Delivery Knowledge Graph"，跨产品数据打通
2. **AI 贯穿全流程** — 不是每个产品各带一个 AI 功能，而是平台级 AI 能力
3. **Policy as Code 统一** — 所有产品都使用 OPA（Open Policy Agent）做策略引擎，统一治理
4. **REST API 统一** — 所有功能都可通过统一的 API 接口调用
5. **YAML 驱动的 Pipeline** — 所有编排都基于 YAML 定义，可版本化、可模板化

### 4.2 Harness AI 核心能力

Harness 在 2025-2026 年大力投入 AI，主要能力包括：

| AI 能力 | 说明 |
|---------|------|
| **Pipeline YAML 生成** | AI 根据自然语言描述自动生成 CI/CD Pipeline YAML |
| **AI 驱动的 Rollback** | 部署异常时 AI 自动分析并触发回滚 |
| **代码评审** | AI 对 PR 代码变更自动评审 |
| **测试生成** | AI 根据 UI 交互自动生成端到端测试 |
| **迁移脚本生成** | AI 辅助生成数据库迁移脚本 |
| **事故事后分析** | AI 自动生成事故回顾报告 |
| **成本异常检测** | AI 自动发现云成本异常 |

### 4.3 DevOps Agent & Policy Agent

Harness 在 2025-2026 年引入了两个关键的 AI Agent：

- **DevOps Agent** — 嵌入 Cursor IDE，开发者从 IDE 内直接触发 CI/CD、安全扫描、部署等操作
- **Policy Agent** — AI 驱动的策略治理，自动检测不合规的配置、制品、权限

### 4.4 MCP Server（Model Context Protocol）

Harness 在 2026 年发布了 **MCP Server**（GitHub: `harness/mcp-server`），让 AI Agent 可以直接访问 Harness 平台的 API：
- Pipeline YAML 支持 — Agent 可以读写 Pipeline 配置
- 供应链安全查询 — 查询 CVE / SBOM 信息
- 弹性测试支持 — 触发混沌实验
- 通过 MCP 协议将 Harness 的能力暴露给任意 AI 助手

### 4.5 Cursor IDE 集成

2026 年 4 月发布的 Cursor Plugin 将 Harness 的能力直接嵌入 Cursor 编辑器：
- 开发者或 AI Agent 在 IDE 内完成代码变更后
- 直接触发 CI/CD 执行、安全验证、部署状态查看
- 无需切换上下文

---

## 5. 产品模块深度分析

### 5.1 Continuous Delivery & GitOps

这是 Harness 的 **旗舰产品**，也是目前最成熟的产品。

#### 核心理念

Harness 的 CD 不是简单执行 `kubectl apply`，而是 **企业级部署编排**。

##### 部署策略

| 策略 | 说明 |
|------|------|
| **Canary** | 逐步增加流量，按%或按批次滚动 |
| **Blue-Green** | 新旧两套环境并行，切换流量 |
| **滚动更新** | 逐个 Pod 替换 |
| **金丝雀分析** | 部署后自动分析指标（成功率、延迟等），决定继续还是回滚 |

##### 部署 Pipeline 示例

```yaml
# Harness CD Pipeline 简化示例
pipeline:
  name: "Production Deploy - Payment Service"
  stages:
    - stage: "Build & Test"
      steps:
        - build: mvn clean package
        - test: mvn test

    - stage: "Deploy Staging"
      steps:
        - deploy:
            environment: staging
            strategy: canary
            weight: [10%, 25%, 50%, 100%]

    - stage: "Integration Tests"
      steps:
        - run: integration-test.sh
        - verify: staging.success_rate >= 99.9%

    - stage: "Security Scan"
      steps:
        - scan:
            type: [sast, sca, container]
            severity_blocker: critical

    - stage: "Approval"
      steps:
        - approval:
            type: "Jira Ticket"
            required_groups: ["dev-leads", "sec-ops"]

    - stage: "Deploy Production"
      steps:
        - deploy:
            environment: production
            strategy: canary
            weight: [5%, 25%, 50%, 100%]

    - stage: "Post-Deploy Verification"
      steps:
        - monitor: 15m
        - verify: error_rate < 0.1%
        - verify: p95_latency < 200ms
```

#### GitOps 深度能力

Harness 的 GitOps 不是在裸 Argo CD 上做文章，而是 **在 Argo CD 之上的企业控制面**：

```
          ┌─────────────────────────────────┐
          │    Harness GitOps 控制面          │
          ├─────────────────────────────────┤
          │  · 统一面板（管理所有 Argo CD）  │
          │  · 应用依赖可视化（App of Apps） │
          │  · 部署进度实时视图              │
          │  · 升级/安全补丁管理            │
          └──────────┬──────────────────────┘
                     │
     ┌───────────────┼───────────────┐
     ▼               ▼               ▼
  Argo CD          Argo CD         Argo CD
  (K8s 集群 A)    (K8s 集群 B)    (K8s 集群 C)
```

**关键差异化能力：**

| 能力 | 说明 |
|------|------|
| **统一面板管理多集群** | 不需要在每个集群上登录不同的 Argo CD UI |
| **Promotion Workflow** | 审批 + 测试 + 通知的完整编排 |
| **AI 回滚** | AI 分析部署效果后自动决策 |
| **App of Apps 可视化** | 复杂应用依赖关系的 UI 视图 |
| **硬化的 Argo CD 镜像** | 安全加固 + 自动更新 |
| **原生密钥管理** | 动态从密钥管理器获取，无需外部插件 |
| **统一 RBAC** | 跨集群统一权限管理 |
| **集中审计** | 所有 GitOps 操作统一审计日志 |

#### 与裸 Argo CD 的详细对比

| 特性 | Harness GitOps | 裸 Argo CD | Flux CD |
|------|---------------|-----------|---------|
| 多集群统一面板 | ✅ 原生 | ❌ 需自建 | ❌ 需自建 |
| App of Apps 可视化 | ✅ UI 可视化 | ❌ 只有 YAML | ❌ 只有 YAML |
| Promotion 编排 | ✅ 审批+质量门 | ❌ 纯同步 | ❌ 纯同步 |
| AI 回滚 | ✅ | ❌ | ❌ |
| Agent 自动升级 | ✅ | ❌ | ❌ |
| 统一 RBAC | ✅ 跨集群 | ❌ 每集群独 | ❌ 每集群独 |
| 集中审计 | ✅ | ❌ | ❌ |

---

### 5.2 Continuous Integration (CI)

#### 功能概览

| 功能 | 说明 |
|------|------|
| **多平台构建** | Linux、Windows、Mac |
| **容器构建** | Docker/Kubernetes 原生支持 |
| **分布式缓存** | 缓存依赖加速构建 |
| **并行执行** | 并行 stage/steps |
| **模板化** | Pipeline YAML 模板复用 |
| **插件生态** | 与 Jenkins、GitHub Actions 等集成 |
| **安全扫描集成** | CI 阶段嵌入安全扫描 |

#### CI 核心优势

Harness CI 的定位是 **AI 增强的 CI**：

- **智能缓存** — AI 分析构建模式，自动预缓存依赖
- **失败预判** — AI 分析代码变更，预判构建是否可能失败
- **开源版本** — Harness Open Source 的 CI 功能完全免费

---

### 5.3 Feature Flags & Feature Management

#### 核心能力

| 能力 | 说明 |
|------|------|
| **功能开关** | 灰度发布、功能开关控制 |
| **实验管理** | A/B 测试 / 多变量实验 |
| **目标群体** | 按用户/IP/地域/自定义属性定向 |
| **指标分析** | 实验效果自动分析 |
| **仓库原生实验** | 在数据仓库中直接运行实验（2026 新增） |

#### 关键升级（2026）

- **Warehouse Native Experimentation** — A/B 测试直接在 Snowflake/Redshift/BigQuery 中运行，无需数据导出
- **CI/CD 集成** — 部署 Pipeline 自动创建/更新 Feature Flag

#### 与 LaunchDarkly 对比

| 特性 | Harness Feature Flags | LaunchDarkly |
|------|----------------------|-------------|
| SDK 种类 | 全平台 SDK | 全平台 SDK |
| 仓库原生实验 | ✅ 2026 新功能 | ❌ |
| CI/CD 集成 | ✅ 原生集成 | ❌ 需第三方 |
| 定价 | 按 feature flag 数量 | 按 MAU |
| AI 辅助 | ✅ | ❌ |

---

### 5.4 Cloud Cost Management (CCM)

#### 核心思想

CCM 的核心不是简单的"看账单"，而是 **将成本治理嵌入到整个开发流程中**：

```
开发阶段 → PR 内自动显示变更的成本影响
CI 阶段  → 成本合规检查（新建的资源是否超标）
CD 阶段  → 成本门禁（成本超预算 → 阻止部署）
运营阶段 → 成本监控 + AI 优化建议
```

#### 功能模组

| 模组 | 说明 |
|------|------|
| **成本监控** | 多云成本统一视图（AWS/GCP/Azure） |
| **成本优化建议** | AI 自动分析闲置资源、预留实例推荐 |
| **异常告警** | AI 检测成本异常并自动告警 |
| **预算管理** | 预算设置与超支预警 |
| **资源治理** | 自动回收闲置资源 |
| **Kubernetes 成本** | 容器级别成本拆分（namespace/deployment/pod） |

#### 与开源方案对比

| 特性 | Harness CCM | Kubecost | CloudHealth |
|------|-----------|---------|------------|
| AI 异常检测 | ✅ | ❌ | 有限 |
| DevOps 集成（Pipeline 门禁） | ✅ 原生 | ❌ | ❌ |
| 成本门禁 | ✅ Pipeline 集成 | ❌ | ❌ |
| K8s 粒度 | ✅ 容器级 | ✅ | ❌ |

---

### 5.5 Infrastructure as Code Management (IaCM)

Harness IaCM **不是** IaC 工具本身（它不是另一个 Terraform），而是 **IaC 流程编排平台**。

#### 支持的底层工具

| 工具 | 在 IaCM 中的角色 |
|------|-----------------|
| **Terraform** | 执行引擎 |
| **OpenTofu** | 执行引擎（开源替代） |
| **Pulumi** | 执行引擎 |
| **CloudFormation** | 执行引擎 |

#### IaCM 核心能力

| 能力 | 说明 |
|------|------|
| **Workflow 编排** | Dev → Staging → Prod 的 IaC 变更 Promotion |
| **状态管理** | 中心化 Terraform State 管理（不用再担心 state 冲突） |
| **Policy as Code** | OPA 策略自动审核 IaC 代码 |
| **Drift 检测** | 自动检测基础设施漂移 |
| **密钥管理** | 敏感变量集中管理 |
| **审计日志** | 每次 IaC 变更都可追溯 |

#### 核心工作流

```
Git 仓库
    │
    ├── PR 提交 ──→ OPA 策略检查 ──→ AI 预览并估算成本影响
    │
    ├── 合并到 main ──→ 自动 Plan ──→ 审批 ──→ Apply
    │
    └── 定时触发 ──→ Drift 检测 ──→ 自动修复 或 告警
```

---

### 5.6 Application Security Testing (AST)

#### 能力矩阵

| 类型 | 说明 |
|------|------|
| **SAST** | 静态代码安全分析（在写代码时发现问题） |
| **DAST** | 动态应用安全测试（运行时扫描） |
| **SCA** | 软件组成分析（第三方依赖漏洞） |
| **容器扫描** | Docker 镜像安全扫描 |
| **IaC 扫描** | Terraform/K8s YAML 安全扫描 |
| **API 扫描** | REST/GraphQL API 安全测试 |

#### Pipeline 中内嵌扫描

```yaml
stages:
  - stage: Build
    steps:
      - build: mvn package

  - stage: SecurityScan
    steps:
      - scan:
          type: ast
          scanners: [sast, sca, container]
          fail_on: critical  # 有 Critical 漏洞则中断 Pipeline

  - stage: Deploy
    steps:
      - deploy: production
          when: security_scan.passed == true
```

---

### 5.7 Supply Chain Security (SSC)

#### 核心能力

| 能力 | 说明 |
|------|------|
| **SBOM 生成** | 自动生成 CycloneDX/SPDX 格式 SBOM |
| **SLSA 证明** | SLSA Level 1-3 供应链证明 |
| **依赖防火墙** | OPA 策略控制哪些开源依赖可以进入 |
| **Curated OSS Catalog** | 预审通过的开源组件目录 |
| **非容器制品证明** | 2026 新增：支持 Helm Chart、JAR/WAR、二进制的 SLSA 证明 |

#### 为什么重要

SLSA（Supply-chain Levels for Software Artifacts）是 Google 提出的软件供应链安全标准。Harness 是首批支持 **非容器制品 SLSA 证明** 的平台。

---

### 5.8 Security Testing Orchestration (STO)

#### 解决的问题

安全扫描工具太多，结果分散，缺乏统一视图和管理。

#### 工作方式

```
    ┌─────────────┐
    │   STO 引擎  │
    └─────┬───────┘
          │
    ┌─────┴───────┐
    │ 扫描器编排   │
    ├─────────────┤
    ├─ SonarQube  │
    ├─ Checkmarx  │
    ├─ Snyk       │
    ├─ Aqua       │
    ├─ Trivy      │
    ├─ Sysdig     │
    └─ ...        │
    └─────────────┘
```

#### 核心价值

1. **不替换** 已有的安全扫描工具
2. **统一编排** 所有扫描器在 Pipeline 中的执行
3. **统一结果视图** 所有工具结果聚合到一张面板
4. **策略即代码** 定义"什么级别漏洞必须修复才能部署"
5. **自动修复建议** 扫描结果直接关联修复方案

---

### 5.9 Resilience Testing (混沌工程)

> 原名 Chaos Engineering，2026 年升级为 Resilience Testing，范围从单纯的混沌故障注入扩展到负载测试和容灾测试。

#### 能力矩阵

| 测试类型 | 说明 |
|---------|------|
| **混沌测试** | 注入故障（Pod 杀死、网络延迟、磁盘故障、Region 故障） |
| **负载测试** | 模拟高并发用户流量，观察系统表现 |
| **容灾测试** | 区域级故障切换、备份恢复验证 |

#### ChaosHub

- **230+ 个开箱即用的弹性测试模板**
- 覆盖：API、微服务、Kubernetes、云基础设施
- 持续由 Harness 社区和官方维护

#### 测试场景示例

```yaml
resilience_test:
  stages:
    - name: "Inject Chaos"
      chaos:
        faults:
          - target: payment-service
            type: pod-kill
            duration: 30s
          - target: database
            type: network-delay
            latency: 200ms
            duration: 60s

    - name: "Verify Resilience"
      verify:
        - resilience_score >= 0.85
        - error_rate < 1%
        - p99_latency < 1000ms
```

#### 安全性设计

| 原则 | 说明 |
|------|------|
| **OPA 策略** | 定义"允许注入什么故障、在哪个环境" |
| **ChaosGuard** | 内置保护机制阻止危险实验 |
| **准入控制** | 自动阻止可能炸掉生产环境的实验 |
| **Agentless** | 默认不需要在目标系统安装 Agent |
| **拓扑自动发现** | 自动绘制微服务依赖关系 |

---

### 5.10 AI SRE (服务可靠性)

#### 2026 年新产品

| 功能 | 说明 |
|------|------|
| **AI 事故分析** | 自动分析根因、生成事故回顾报告 |
| **自动事后复盘** | 事故关闭后自动生成六段式复盘报告 |
| **SLI/SLO 管理** | 自动定义和跟踪服务级别指标 |
| **AI 告警降噪** | AI 合并重复告警、智能升级 |
| **Slack 集成** | Slack 中实时追踪事故处理 |

#### 关键数据

> "当事故关闭后，Harness AI SRE 自动生成结构化的六段式回顾报告。通常需要 2-4 小时的工作，现在几秒钟完成，行动项在事故期间从 Slack 中实时捕获。"

---

### 5.11 AI Test Automation

#### 核心价值

"测试创建快 10 倍，测试维护减少 70%"

#### 功能

| 功能 | 说明 |
|------|------|
| **No-Code 录制** | 浏览 Web 应用时自动录制测试步骤 |
| **Intent-Based 测试** | 用自然语言写测试和断言 |
| **Self-Healing** | UI 元素变化后自动修复 locator |
| **智能重试** | 测试不稳定时自动分析并重试 |
| **CI/CD 集成** | 一键集成到 Harness CI/CD Pipeline |

#### 与传统自动化测试的对比

| 维度 | 传统自动化测试 | Harness AI Test Automation |
|------|-------------|--------------------------|
| 测试创建 | 手动编写脚本 | 录制 / 意图式自然语言 |
| 维护成本 | 高（UI 变更则脚本不可用） | 自愈（Smart Selector） |
| 覆盖率 | 有限的回归用例 | AI 自动生成更多路径 |
| CI/CD 集成 | 需单独配置 | 一键集成 |

---

### 5.12 Internal Developer Portal (IDP)

#### 技术基础

基于 **Backstage**（Spotify 开源项目，CNCF 孵化项目）构建。

#### 相比裸 Backstage 的优势

| 特性 | Harness IDP | 裸 Backstage |
|------|-----------|------------|
| 部署运维 | SaaS 托管 | 自行部署维护 |
| AI 集成 | ✅ Harness AI 内置 | ❌ 需自行开发 |
| CI/CD 集成 | ✅ 原生集成 | ❌ 需插件安装配置 |
| 开发者自助流程 | ✅ 开箱即用 | ❌ 需开发定制 |

#### 核心能力

| 能力 | 说明 |
|------|------|
| **服务目录** | 自动发现所有服务、API、文档 |
| **自助工作流** | 开发者自助创建服务/环境/基础设施 |
| **知识图谱** | 服务依赖关系可视化 |
| **Scorecard** | 自动化的服务成熟度评分 |
| **AI 知识代理** | AI 回答"这个服务谁在用"之类的问题 |

#### Golden Path 概念

IDP 的核心设计是 **Golden Path**（黄金路径）：

```
开发者想要创建一个新微服务 →
  1. 在 IDP 中选择 "创建 Java 微服务" 模板
  2. 填写服务名、团队名
  3. IDP 自动完成：
     ✓ 创建 Git 仓库（含项目脚手架）
     ✓ 创建 CI Pipeline
     ✓ 创建 CD Pipeline
     ✓ 配置 K8s 资源
     ✓ 注册到服务目录
     ✓ 创建告警规则
  4. 开发者直接开始写业务代码
```

---

### 5.13 Artifact Registry

#### 支持的制品类型

| 类型 | 说明 |
|------|------|
| **Docker** | 容器镜像 |
| **Maven** | Java 依赖 |
| **npm** | Node.js 依赖 |
| **Python** | PyPI 包 |
| **Go** | Go 模块 |
| **Helm Chart** | K8s Chart |
| **二进制** | 任意二进制文件 |
| **AI/ML 模型** | Hugging Face 代理 |

#### 核心差异化

| 差异化能力 | 说明 |
|-----------|------|
| **AI 原生产品** | 原生支持 AI/ML 制品管理 |
| **Hugging Face 代理** | 代理并缓存 HF 模型，自动扫描安全 + 许可证 |
| **MCP Server 集成** | AI Agent 可直接查询/推送制品 |
| **Dependency Firewall** | OPA 策略控制哪些依赖能进入系统 |
| **SLSA 证明** | 所有制品都有供应链证明 |
| **多区域复制** | 99.9%+ 可用性，CDN 加速（路线图） |
| **定价** | 按存储计费，**无出站流量费** |

---

### 5.14 Code Repository

#### 定位

Harness Code Repository 直接与 GitHub/GitLab 竞争。

#### 功能

| 功能 | 说明 |
|------|------|
| **Git 仓库托管** | 基础 Git 功能（分支、PR、Code Review） |
| **AI 代码理解** | AI 辅助代码导航和搜索 |
| **秘密扫描** | 提交前自动扫描硬编码密钥 |
| **OSS 漏洞扫描** | 提交前扫描依赖漏洞 |
| **一键迁移** | 从 GitHub/GitLab/Bitbucket 零停机迁移 |

#### 关键策略：双向同步

不做"锁定"，提供 **双向同步**：

```
GitHub ←————→ Harness Code Repository
                ↑
            开发者可同时使用两个平台
            Harness 作为"管理面"增强安全/治理
```

---

### 5.15 Software Engineering Insights (SEI)

#### 定位

基于 **DORA 指标**（DevOps Research and Assessment）的工程效能度量平台。

#### 核心指标

| 类别 | 指标 | Harness 计算方式 |
|------|------|-----------------|
| **部署频率** | Deployment Frequency | 从 CD Pipeline 自动采集 |
| **变更前置时间** | Lead Time for Changes | 代码提交到部署的时间 |
| **变更失败率** | Change Failure Rate | 部署后回滚/事故占比 |
| **故障恢复时间** | MTTR | 事故到恢复的时间 |
| **开发效能** | Developer Productivity | Trellis 框架分析 |

#### Trellis 框架

Harness 专利的**开发效能评估框架**：

- 采集 **20+ 个数据因子**（从 Jira、Git、CI/CD、PagerDuty 等系统）
- 算法生成综合效能报告
- 精准定位瓶颈（比如：代码评审环节消耗的时间过长）

---

### 5.16 Database DevOps

#### 2026 年新产品

将数据库变更纳入 CI/CD Pipeline。

#### 核心能力

| 能力 | 说明 |
|------|------|
| **AI 迁移脚本生成** | AI 辅助编写数据库迁移 SQL |
| **Pipeline 编排** | 数据库变更作为 Pipeline 的一个 stage |
| **策略即代码** | OPA 策略自动审核 SQL（如："不允许 drop 表"） |
| **多环境对比** | 可视化比较不同环境的 Schema 差异 |
| **回滚支持** | 可选回滚能力 |
| **Liquibase/Flyway 集成** | 兼容现有的数据库迁移工具 |

#### 核心场景

```
应用代码变更 + 数据库迁移 → 同一个 Pipeline → 同时部署
当数据库变更不兼容         → Pipeline 拒绝部署（策略控制）
```

---

### 5.17 Cloud Development Environments (Gitspaces)

#### 定位

类似 GitHub Codespaces 的云端开发环境服务。

| 特性 | 说明 |
|------|------|
| **一键启动** | 点击即可启动预配置开发环境 |
| **预配置** | 所有依赖、工具、库预先装好 |
| **IDE 连接** | 支持 VSCode、JetBrains |
| **一致性** | 消除"在我机器上是好的"问题 |

---

### 5.18 WAAP（Web Application & API Protection）

#### 2026 年新增安全产品

| 功能 | 说明 |
|------|------|
| **WAF** | Web 应用防火墙 |
| **API 保护** | REST/GraphQL API 安全防护 |
| **AI 安全** | AI 应用的安全保护 |
| **运行时检测** | 运行时攻击检测与防御 |

---

## 6. 技术架构分析

### 6.1 总体架构

```
            ┌──────────────────────────────────┐
            │        Harness SaaS 平台          │
            │    (多租户 / 单一控制面)           │
            └──────────────┬───────────────────┘
                           │
           ┌───────────────┴───────────────┐
           │        API Gateway             │
           │    (统一 REST API + GraphQL)    │
           └───────────────┬───────────────┘
                           │
           ┌───────────────┴───────────────┐
           │       Knowledge Graph          │
           │ (所有实体关联关系的统一数据库)   │
           └───────────────┬───────────────┘
                           │
    ┌──────────┬───────────┼───────────┬──────────┐
    ▼          ▼           ▼           ▼          ▼
┌──────┐  ┌──────┐  ┌────────┐  ┌────────┐  ┌────────┐
│ Core │  │ CI   │  │ CD     │  │ STO    │  │ CCM    │
│Svc   │  │ Svc  │  │ Svc    │  │ Svc    │  │ Svc    │
│(IAM, │  │(Build│  │(Deploy │  │(Scans) │  │(Cost)  │
│ RBAC)│  │ Mgr) │  │ Mgr)   │  │        │  │        │
└──────┘  └──────┘  └────────┘  └────────┘  └────────┘
    │          │           │           │          │
    └──────────┴───────────┴───────────┴──────────┘
                           │
                    ┌──────┴──────┐
                    │  数据层     │
                    │ PostgreSQL  │
                    │ Redis       │
                    │ S3          │
                    │ Prometheus  │
                    └─────────────┘
```

### 6.2 Harness Delegate（代理架构）

Harness 的关键基础设施组件 **Harness Delegate**：

```
Harness SaaS (控制面)
     ▲
     │  HTTPS / gRPC (只出站连接)
     ▼
┌──────────────────────────────┐
│       Harness Delegate       │
│  (Java 进程，运行在客户环境)  │
│                              │
│  · 在 K8s 中作为 Pod 运行    │
│  · 或运行在 Docker 容器中    │
│  · 只发起出站连接（无需入站）│
│  · 执行所有实际的部署操作    │
└──────────────────────────────┘
     │
     ▼
客户环境：K8s / AWS / GCP / Azure / 本地数据中心
```

**Delegate 设计原则**：
- **只出站不入站**：客户无需为 Harness 开放防火墙端口
- **最小权限**：Delegate 只需必要的 IAM 权限
- **弹性伸缩**：Delegate 支持自动扩缩
- **支持多种注册方式**：K8s Operator、Docker Compose、Helm Chart

### 6.3 Knowledge Graph

Harness 的 **Software Delivery Knowledge Graph** 是其数据架构的核心：

| 实体类型 | 实例 |
|---------|------|
| 组织 | Harness Account → Project |
| 人 | 开发者、审批者、QA |
| 代码 | Repo、Branch、Commit、PR |
| 构建 | Build、Artifact、Test Run |
| 部署 | Environment、Service、Infrastructure |
| 安全 | Vulnerability、CVE、SBOM |
| 成本 | Cloud Account、Resource、Cost Anomaly |

所有产品共享同一个知识图谱，数据一致性不是靠 API 调用，而是靠底层数据打通。

---

## 7. 竞争对手对比

### 7.1 竞争格局总览

Harness 的竞争对手横跨多个领域，因为它的产品线太宽了：

| 产品领域 | Harness 模块 | 主要竞争对手 |
|---------|-------------|-------------|
| CI | CI | GitHub Actions、GitLab CI、CircleCI、Jenkins |
| CD | CD & GitOps | Argo CD（开源）、Flux CD、Spinnaker、Octopus Deploy |
| 特性管理 | Feature Flags | LaunchDarkly、Split.io |
| 云成本 | CCM | Kubecost、CloudHealth、Vantage |
| 混沌工程 | Resilience Testing | Chaos Mesh、LitmusChaos、Gremlin |
| 安全测试 | AST/STO | Snyk、Checkmarx、SonarQube |
| 供应链安全 | SSC | Snyk、Dependency-Track |
| 开发者门户 | IDP | Backstage（开源）、Port、Cortex |
| 制品管理 | Artifact Registry | Artifactory、Sonatype Nexus、Docker Hub |
| 代码托管 | Code Repository | GitHub、GitLab、Bitbucket |
| 度量 | SEI | Code Climate、LinearB、Allstacks |

### 7.2 与主要竞品的详细对比

#### 7.2.1 Harness vs GitHub Actions

| 维度 | Harness | GitHub Actions |
|------|--------|---------------|
| 产品范围 | 全生命周期平台 | 主要是 CI + 少量 CD |
| CD 能力 | ✅ 企业级（Canary/Blue-Green） | ❌ 基础水平 |
| GitOps | ✅ 企业级 Argo CD 管理 | ❌ |
| AI 能力 | ✅ 平台级 AI | ✅ Copilot（独立产品） |
| 多云支持 | ✅ AWS/GCP/Azure | ✅ 可集成 |
| 安全扫描 | ✅ 内置 AST/STO/SSC | ❌ 需第三方 Action |
| 成本管理 | ✅ 内置 CCM | ❌ |
| 混沌工程 | ✅ | ❌ |
| 开发者门户 | ✅ IDP | ❌ |
| 定价 | 按开发者/功能模块 | 按运行分钟数 |

#### 7.2.2 Harness vs GitLab

| 维度 | Harness | GitLab |
|------|--------|--------|
| 产品范围 | DevOps 平台 + AI | DevOps 平台 + AI |
| CD 能力 | ✅ 更强（Canary/Blue-Green） | ✅ 基础可用 |
| GitOps | ✅ Argo CD 管理面 | ✅ 与 K8s 集成 |
| AI 能力 | ✅ DevOps Agent/Policy Agent | ✅ GitLab Duo |
| 开源策略 | ✅ 开源版可用 | ✅ 开源版 CE |
| 自托管 | ✅ Delegate | ✅ 全自托管 |
| 代码仓库 | ✅ Code Repository | ✅ 核心能力 |
| 安全扫描 | ✅ 内置 | ✅ 内置 |
| 混沌工程 | ✅ Resilience Testing | ❌ |
| 云成本 | ✅ CCM | ❌ |
| 开发者门户 | ✅ IDP（基于 Backstage） | ❌（收购 Opstrace） |

#### 7.2.3 Harness vs Argo CD（开源）

| 维度 | Harness GitOps | 裸 Argo CD |
|------|---------------|-----------|
| 价格 | 商业许可 | 免费开源 |
| 安装部署 | SaaS（由 Delegate 连接） | 自行部署在 K8s 集群 |
| 多集群管理 | ✅ 统一面板 | ❌ 每集群一个实例 |
| 审计日志 | ✅ 集中 | ❌ |
| RBAC | ✅ 统一 | ❌ 每集群 |
| Promotion | ✅ Workflow 编排 | ❌ |
| AI 回滚 | ✅ | ❌ |
| App of Apps UI | ✅ | ❌ |
| 安全硬化 | ✅ | ❌ |

#### 7.2.4 Harness vs Octopus Deploy

| 维度 | Harness CD | Octopus Deploy |
|------|-----------|---------------|
| 定位 | AI DevOps 平台 | 只做 CD |
| 部署策略 | Canary/Blue-Green/Rolling | Rolling/Blue-Green |
| K8s 支持 | ✅ 原生 | ✅ 支持 |
| GitOps | ✅ | ❌ |
| AI | ✅ | ❌ |
| 产品线 | 18+ 模块 | 仅 CD |

### 7.3 Harness 的核心竞争优势

1. **产品宽度无人能匹敌** — 没有第二家公司覆盖 18 个 DevOps 产品模块
2. **AI 贯穿全平台** — 不是每个模块各带 AI，而是平台级 AI 能力
3. **Argo CD 管理面** — 利用开源 Argo CD 的生态，叠加企业级管理功能
4. **Knowledge Graph** — 统一数据层使跨产品协同成为可能（如：部署 Pipeline 自动关联成本和安全数据）
5. **MCP Server** — 率先将 DevOps 平台能力通过 MCP 协议暴露给 AI Agent

### 7.4 Harness 的核心劣势

1. **价格高** — 企业级平台，小团队用不起
2. **学习曲线陡** — 18 个产品模块，理解全貌需要时间
3. **锁定风险** — 虽然不是完全锁定（GitOps 基于开源 Argo CD），但深度使用后难以迁移
4. **产品成熟度不均** — CD 最成熟，Code Repository 和 DB DevOps 是新丁
5. **国内访问** — Harness 是 SaaS 服务，国内访问延迟大，无中国区部署

---

## 8. 开源生态 & 社区

### 8.1 开源承诺

Harness 对开源的态度比较特殊：

1. **核心产品有开源版** — Harness Open Source 提供 CI/CD、Code Repository、Artifact Registry 等功能
2. **Argo CD 集成** — 不 fork Argo CD，而是在其之上做管理面
3. **开源 SDK** — Feature Flags SDK 全是开源的
4. **MCP Server** — 完全开源（GitHub: harness/mcp-server）
5. **GitHub 组织** — https://github.com/harness

### 8.2 开源项目一览

| 项目 | 说明 | GitHub Stars |
|------|------|-------------|
| harness-core | 核心平台 | 1000+ |
| harness-open-source | 开源版 Harness | 5000+ |
| mcp-server | MCP 协议服务 | 500+（快速增长） |
| ff-python-server-sdk | Feature Flags Python SDK | — |
| ff-java-server-sdk | Feature Flags Java SDK | — |
| ff-nodejs-server-sdk | Feature Flags Node.js SDK | — |

### 8.3 社区资源

| 资源 | 链接 |
|------|------|
| 官方文档 | https://developer.harness.io |
| API 参考 | https://developer.harness.io/docs/api-reference |
| 工程博客 | https://www.harness.io/blog |
| 社区 Slack | https://harnesscommunity.slack.com |
| YouTube | https://youtube.com/@harness |
| Harness University | 官方培训课程 |
| 认证 | Harness Certified Developer/Architect |

---

## 9. 定价模型

### 9.1 定价概述

Harness 的定价策略是按 **开发者席位 + 按模块付费**：

| 产品 | 免费层 | 付费层 |
|------|--------|--------|
| CI/CD | 开源版免费 | 按开发者数（$50-$100+/月/人） |
| Feature Flags | 免费（有限 flags） | 按 flags 数量 |
| CCM | 免费（有限资源） | 按管理的云支出（~1%） |
| IDP | 最低 20 开发者许可 | 按开发者数 |
| AI Test Automation | 试用 | 按执行次数 |
| Resilience Testing | 试用 | 按测试执行 |

### 9.2 各层定价参考

> 注意：实际价格需联系销售，以下为行业估计

| 产品 | 大致价格 |
|------|---------|
| CI/CD（团队版） | ~$50/用户/月 |
| CI/CD（企业版） | ~$100+/用户/月 |
| CCM | 管理云支出的 ~0.5%-2% |
| Feature Flags | 5000 flags ~$500/月 |
| IDP | 最低 20 用户起 |

### 9.3 免费层功能

Harness 提供相当慷慨的免费层：
- **开源版**：CI/CD + Code Repository + Artifact Registry（完全免费）
- **SaaS 免费层**：有限数量的 Pipeline 执行和用户

---

## 10. 客户案例 & 行业认可

### 10.1 典型客户

Harness 的 1000+ 企业客户覆盖金融、科技、电商、医疗等多个行业：

| 客户 | 行业 | 使用场景 |
|------|------|---------|
| 花旗银行 | 金融 | 多环境部署 + 安全合规 |
| J.P. Morgan | 金融 | 企业级 CD + GitOps |
| 家得宝 (Home Depot) | 零售 | 大规模 K8s 部署 |
| 沃尔玛 | 零售 | CD + 混沌工程 |
| 迪士尼 | 媒体 | Feature Flags + A/B 测试 |
| 西门子医疗 | 医疗 | AI 测试自动化 |
| 英国电信 | 电信 | 多集群管理 |
| 英国石油 (BP) | 能源 | 安全+合规部署 |

### 10.2 行业认可

| 荣誉 | 年份 |
|------|------|
| Forrester Wave: 持续交付 | 领导者 |
| Gartner Peer Insights: DevOps | 4.5+ 分 |
| G2: CI/CD 平台 | 领导者 |
| CNCF 生态成员 | — |
| 2025 / 2026 年大量 AI 相关奖项 | — |

### 10.3 关键数据（来自官方）

- **1000+** 家企业客户
- **数千个** 部署 Pipeline 运行中
- **数百万** 次部署执行
- **1200+** 员工
- 覆盖 **金融、科技、零售、医疗** 等行业

---

## 11. 2026 年最新动态

### 11.1 2026 年 4 月发布亮点

Harness 在 2026 年 4 月发布了 70+ 个新功能，以下是重点：

#### Cursor IDE 插件
- Harness 现在可以直接在 Cursor IDE 中使用
- 开发者/AI Agent 可以在编辑器内完成从代码变更到安全检测、CI/CD 执行、部署查看的全流程
- 包含 Harness Secure AI Coding Hook

#### Google Cloud 合作
- 与 Google Cloud Developer Connect 集成
- Knowledge Graph 打通 Google Cloud 数据
- 提供统一的 AI 软件交付视图

#### 数据仓库原生实验（Warehouse Native Experimentation）
- A/B 测试和 Feature Experimentation 直接在 Snowflake/Redshift/BigQuery 中运行
- 使用已有的分配和指标数据作为黄金数据源
- 无需数据导出，无需额外存储

#### SLSA 非容器制品证明
- 供应链证明现在覆盖 Helm Chart、JAR/WAR、二进制文件
- 不再限于容器镜像

#### AI SRE 自动事故复盘
- 事故关闭后 AI 自动生成六段式回顾报告
- 通常在 Slack 中的信息被自动捕获
- 2-4 小时的人工工作 → 几秒钟

#### MCP Server 更新
- Pipeline YAML 支持（Agent 可以读写 Pipeline）
- OSS 漏洞查询
- 弹性测试支持

#### 其他值得关注的功能
- **OSS 代码仓库漏洞修复** — 自动修复开源依赖漏洞
- **API 安全扫描配置改进** — 简化为三层结构
- **Code Repository 功能增强** — One-click 从 GitHub/GitLab/Bitbucket 迁移

### 11.2 2026 年产品路线图方向

根据公开信息，Harness 在 2026 年将重点发展：

1. **Agentic DevOps** — DevOps Agent、Policy Agent 等 AI Agent 将承担更多自动化工作
2. **AI 安全** — AI 应用的安全保护（2026 新方向）
3. **Database DevOps** — 数据库变更管理的深度集成
4. **Service Reliability Management** — AI SRE 持续增强
5. **CDN 加速 Artifact Registry** — 全球制品分发加速
6. **平台开放性** — MCP Server 生态扩展

---

## 12. 总结 & 建议

### 12.1 核心总结

| 维度 | 结论 |
|------|------|
| **公司实力** | 强大——连续创业成功者，5.7 亿美元融资，37 亿美元估值 |
| **产品广度** | DevOps 领域最广——18 个产品模块 |
| **AI 深度** | 领先——平台级 AI（不是模块加 AI 功能） |
| **GitOps** | 差异化强——Argo CD 管理面 |
| **安全** | 全面——从代码到运行时全覆盖 |
| **开源** | 部分开源——核心平台开源但企业功能付费 |
| **价格** | 高——适合中大型企业 |
| **国内可用性** | 差——纯 SaaS，无中国区 |

### 12.2 适用场景建议

#### 适合 Harness 的场景
- ✅ **中大型企业**（200+ 开发者）需要统一 DevOps 平台
- ✅ **多云/K8s 集群多**（数十上百个集群）需要统一管理
- ✅ **安全合规要求高**（金融、医疗等）
- ✅ **有 AI 在 DevOps 中落地需求**
- ✅ **已经在用 Argo CD 但需要企业级管理**
- ✅ **想要将 CI/CD、安全、成本统一管理**

#### 不适合 Harness 的场景
- ❌ **小团队**（10 人以下）——太贵，功能过剩
- ❌ **纯中国内地部署**——SaaS 无法覆盖
- ❌ **只想要 CI**——GitHub Actions 就够了
- ❌ **对全栈开源有硬需求**——部分产品需付费
- ❌ **超轻量**——一个 Gitea + Jenkins 可能更省事

### 12.3 对国内技术团队的启示

虽然 Harness 不易直接在国内使用，但它的产品设计思路值得借鉴：

1. **AI 融入 DevOps 全流程** — 不是"AI + DevOps"拼接，而是 AI 原生
2. **统一数据层** — Knowledge Graph 让所有数据互通
3. **Policy as Code 统一治理** — 所有产品使用同一套策略引擎
4. **Agent 化架构** — DevOps Agent、Policy Agent 自主执行任务
5. **Golden Path 思想** — 从"提交工单等审批"到"自助服务但受治理"

---

## 附录

### A. 术语表

| 缩写 | 全称 | 说明 |
|------|------|------|
| AST | Application Security Testing | 应用安全测试 |
| CCM | Cloud Cost Management | 云成本管理 |
| CD | Continuous Delivery | 持续交付 |
| CI | Continuous Integration | 持续集成 |
| DORA | DevOps Research and Assessment | Google 的 DevOps 效能评估框架 |
| IaC | Infrastructure as Code | 基础设施即代码 |
| IaCM | Infrastructure as Code Management | IaC 编排管理 |
| IDP | Internal Developer Portal | 内部开发者门户 |
| MCP | Model Context Protocol | AI Agent 与工具之间的通信协议 |
| OPA | Open Policy Agent | 开源策略引擎（CNCF）| 
| RBAC | Role-Based Access Control | 基于角色的访问控制 |
| SAST | Static Application Security Testing | 静态应用安全测试 |
| SBOM | Software Bill of Materials | 软件物料清单 |
| SCA | Software Composition Analysis | 软件组成分析 |
| SEI | Software Engineering Insights | 软件工程洞察 |
| SLSA | Supply-chain Levels for Software Artifacts | 软件供应链安全等级 |
| SRE | Site Reliability Engineering | 站点可靠性工程 |
| SSC | Supply Chain Security | 供应链安全 |
| STO | Security Testing Orchestration | 安全测试编排 |
| WAAP | Web Application & API Protection | Web 应用和 API 保护 |

### B. 信息来源

本报告信息来源于以下渠道：
- Harness 官方网站（harness.io）各产品页面
- Harness 开发者文档（developer.harness.io）
- Harness Engineering Blog
- Harness 官方 GitHub 组织（github.com/harness）
- 公开的融资报道和行业分析

---

*报告编写日期：2026-05-18*
*报告生成工具：OpenClaw AI Assistant*