# AI Agent Observability, Monitoring, and Tracing (2025--2026)

---

## 1. The Observability Challenge for AI Agents

Traditional microservice observability rests on deterministic request paths, stable latency profiles, and a well-understood failure surface. AI agents invert every one of those assumptions.

### 1.1 Why Agents Break Traditional Observability

| Traditional Microservice | AI Agent |
|---|---|
| Deterministic call graph (service A calls B calls C) | Non-deterministic paths: multi-turn reasoning loops with variable step count |
| Fixed latency budget per operation | Variable latency: a single "request" can spawn 3--30+ LLM calls, tool invocations, and retrieval round-trips |
| Failures are binary (timeout, 5xx) | Failures are semantic: hallucinations, incorrect tool arguments, reasoning collapse |
| Cost is compute-bound (CPU-seconds) | Cost is token-bound: each reasoning loop burns input + output tokens, with output tokens costing 3--5x more |
| Telemetry dimensions are fixed (endpoint, method, status) | Telemetry dimensions multiply combinatorially: model fingerprint, prompt version, tool selection, guardrail decisions, RAG chunk provenance |
| One trace per request | Hierarchical span tree spanning agent runs, LLM calls, tool executions, retrieval queries, guardrail checks, and human-in-the-loop pauses |

### 1.2 The Unique Telemetry Dimensions

An AI agent trace must capture seven distinct span categories that traditional APM has no concept of:

1. **Agent Traces** (`agent.run`): The top-level lifecycle of an agent task, encompassing the full ReAct loop from user input to final response. Spans 10ms to 10+ minutes.
2. **LLM Calls** (`llm.generate`): Each model inference, including prompt assembly, token streaming, and completion. Captures model name, temperature, token counts, and finish reason.
3. **Tool Executions** (`tool.execute`): Every function-call invocation -- file reads, API calls, database queries, code execution. Must capture tool name, arguments, return value, duration, and success/failure.
4. **RAG Retrieval** (`retrieval.query`): Embedding generation, vector search, re-ranking, and chunk fusion. Captures query vector, top-k, similarity scores, and retrieved document IDs.
5. **Guardrail Checks** (`guardrail.check`): Input/output content moderation, PII detection, prompt injection screening. Must record the guardrail rule that fired, the severity, and whether the request was blocked.
6. **Skill Invocations** (`skill.invoke`): Business-function-level abstractions that compose multiple tool + LLM calls (e.g., "check_order_status").
7. **Human-in-the-Loop Pauses** (`hitl.wait`): When an agent hands off to a human reviewer, the waiting period and reviewer decision must be captured as part of the trace.

### 1.3 The Observability Quadrant for Agents

Extending the classic "Three Pillars" (Metrics, Logs, Traces), agent observability demands a fourth pillar -- **Behavioral Signals** -- because correctness is no longer binary:

```
                    QUANTITATIVE                     QUALITATIVE
              ┌───────────────────────┬───────────────────────────┐
   REAL-TIME  │  Latency, Token Rate, │  Hallucination Score,      │
   (Online)   │  Error Rate, Cost/sec │  Guardrail Triggers,       │
              │  (Prometheus + Tempo) │  Toxicity Flag (LLM Judge) │
              ├───────────────────────┼───────────────────────────┤
   HISTORICAL │  P95 Latency Trend,   │  Drift Detection,          │
   (Offline)  │  Cost-per-Task Trend, │  Dataset Regression,       │
              │  Usage Heatmaps       │  Human Annotation Queue    │
              └───────────────────────┴───────────────────────────┘
```

---

## 2. OpenTelemetry GenAI Semantic Conventions

The OpenTelemetry GenAI Semantic Conventions (SemConv) provide a vendor-neutral standard for instrumenting AI agents. Stabilized across v1.36--v1.37 in 2025, with agent-specific conventions arriving mid-2025, they define the canonical span types, attributes, and metrics for all GenAI workloads.

### 2.1 Key Span Types

```yaml
# Canonical GenAI span kinds (v1.37+)
spans:
  gen_ai.agent.run:         # Top-level agent task lifecycle
    kind: INTERNAL
    description: "Represents a full agent ReAct loop from user input to final output"

  gen_ai.agent.task:        # A discrete reasoning step within a run
    kind: INTERNAL
    description: "A single think-act-observe cycle in a ReAct agent"

  gen_ai.llm.generate:      # A single LLM inference call
    kind: CLIENT
    description: "Covers prompt assembly, token generation, and completion"

  gen_ai.tool.execute:      # A tool/function call executed by the agent
    kind: CLIENT
    description: "Captures tool name, arguments, result, and duration"

  gen_ai.retrieval.query:   # A vector/lexical search operation
    kind: CLIENT
    description: "Embedding generation, index query, re-ranking"

  gen_ai.guardrail.check:   # A content safety or policy check
    kind: INTERNAL
    description: "Input/output moderation, PII detection, prompt injection screening"
```

### 2.2 Standard Attributes

| Attribute | Type | Span | Description |
|---|---|---|---|
| `gen_ai.system` | string | all | Provider: `openai`, `anthropic`, `google_vertexai`, `ollama` |
| `gen_ai.request.model` | string | llm.generate | Model name: `gpt-4.1`, `claude-sonnet-4-5` |
| `gen_ai.usage.input_tokens` | int | llm.generate | Tokens consumed by the prompt |
| `gen_ai.usage.output_tokens` | int | llm.generate | Tokens generated in the completion |
| `gen_ai.response.finish_reason` | string | llm.generate | `stop`, `length`, `tool_calls`, `content_filter` |
| `gen_ai.agent.name` | string | agent.run | Human-readable agent identifier |
| `gen_ai.agent.id` | string | agent.run | Stable UUID for the agent deployment |
| `gen_ai.tool.name` | string | tool.execute | The function name being called |
| `gen_ai.tool.description` | string | tool.execute | The tool's purpose (for debugging) |
| `gen_ai.guardrail.type` | string | guardrail.check | `input`, `output`, `pii`, `injection` |
| `gen_ai.guardrail.verdict` | string | guardrail.check | `pass`, `block`, `flag` |

### 2.3 Instrumentation Code Example

```python
# Python: instrumenting a ReAct agent with OTel GenAI SemConv
from opentelemetry import trace
from opentelemetry.semconv.ai import GenAISpanAttributes, GenAISystemValues

tracer = trace.get_tracer(__name__)

def run_agent(user_query: str) -> str:
    with tracer.start_as_current_span(
        "gen_ai.agent.run",
        attributes={
            GenAISpanAttributes.GEN_AI_SYSTEM: GenAISystemValues.ANTHROPIC.value,
            GenAISpanAttributes.GEN_AI_AGENT_NAME: "support-bot",
            GenAISpanAttributes.GEN_AI_AGENT_ID: "agent-4f2a8b",
        }
    ) as agent_span:

        # --- Step 1: LLM reasoning call ---
        with tracer.start_as_current_span(
            "gen_ai.llm.generate",
            attributes={
                GenAISpanAttributes.GEN_AI_REQUEST_MODEL: "claude-sonnet-4-5",
                GenAISpanAttributes.GEN_AI_USAGE_INPUT_TOKENS: 1240,
                GenAISpanAttributes.GEN_AI_USAGE_OUTPUT_TOKENS: 85,
            }
        ):
            reasoning = llm.generate(user_query)

        # --- Step 2: Tool execution ---
        with tracer.start_as_current_span(
            "gen_ai.tool.execute",
            attributes={
                GenAISpanAttributes.GEN_AI_TOOL_NAME: "get_order_status",
                GenAISpanAttributes.GEN_AI_TOOL_DESCRIPTION: "Fetch order by ID",
            }
        ) as tool_span:
            result = await get_order_status(order_id="ORD-12345")
            tool_span.set_attribute("gen_ai.tool.result", str(result)[:500])

        # --- Step 3: Guardrail check ---
        with tracer.start_as_current_span(
            "gen_ai.guardrail.check",
            attributes={
                GenAISpanAttributes.GEN_AI_GUARDRAIL_TYPE: "output",
                GenAISpanAttributes.GEN_AI_GUARDRAIL_VERDICT: "pass",
            }
        ):
            safety_score = guardrail.evaluate(final_answer)

        return final_answer
```

```typescript
// TypeScript/Node.js: equivalent instrumentation
import { trace, SpanStatusCode } from '@opentelemetry/api';
import { SemanticAttributes } from '@opentelemetry/semantic-conventions-gen-ai';

const tracer = trace.getTracer('agent-service');

async function runAgent(query: string): Promise<string> {
  return tracer.startActiveSpan('gen_ai.agent.run', async (span) => {
    span.setAttribute('gen_ai.agent.name', 'support-bot');
    span.setAttribute('gen_ai.agent.id', 'agent-4f2a8b');

    try {
      const reasoning = await tracer.startActiveSpan(
        'gen_ai.llm.generate', async (llmSpan) => {
          llmSpan.setAttribute(SemanticAttributes.GEN_AI_REQUEST_MODEL, 'gpt-4.1');
          const result = await callLLM(query);
          llmSpan.setAttribute('gen_ai.usage.input_tokens', result.usage.input_tokens);
          llmSpan.setAttribute('gen_ai.usage.output_tokens', result.usage.output_tokens);
          llmSpan.end();
          return result;
        });

      const toolResult = await tracer.startActiveSpan(
        'gen_ai.tool.execute', async (toolSpan) => {
          toolSpan.setAttribute('gen_ai.tool.name', 'search_knowledge_base');
          const result = await searchKB(reasoning.tool_call_args);
          toolSpan.end();
          return result;
        });

      span.setStatus({ code: SpanStatusCode.OK });
      return formatResponse(toolResult);
    } catch (err) {
      span.setStatus({ code: SpanStatusCode.ERROR, message: (err as Error).message });
      span.recordException(err as Error);
      throw err;
    } finally {
      span.end();
    }
  });
}
```

---

## 3. LoongSuite GenAI Semantic Conventions (Alibaba + Ant Group, May 2026)

LoongSuite is a jointly developed, open-sourced extension of the OpenTelemetry GenAI SemConv standard, created by Alibaba Cloud, Alibaba Holding, and Ant Group. Publicly released in May 2026 under the LoongSuite brand, it fills gaps in the upstream OTel spec for long-chain agent workflows, business-skill modeling, and token-level inference tracing. The project plans upstream contributions to OTel once conventions stabilize.

### 3.1 Entry/Step Span Hierarchy

Long-chain agent tasks (commonly 20--50+ steps in production e-commerce or customer service use cases) produce flat span trees that are impossible to navigate in standard trace UIs. LoongSuite introduces two new span levels:

```
Entry Span (gen_ai.agent.entry)
  │  Captures raw user input and final output, sanitized of system prompt noise
  │  Reconstructs conversation history cleanly for replay and debugging
  │
  ├── Step Span #1 (gen_ai.agent.step)
  │     ├── gen_ai.llm.generate   (reasoning call)
  │     ├── gen_ai.tool.execute   (tool call 1)
  │     └── gen_ai.tool.execute   (tool call 2)
  │
  ├── Step Span #2 (gen_ai.agent.step)
  │     ├── gen_ai.llm.generate   (reflection call)
  │     ├── gen_ai.retrieval.query
  │     └── gen_ai.tool.execute
  │
  └── Step Span #N ...
```

**Debugging workflow**: When a production issue is reported, operators first identify *which step* went wrong (Entry/Step), then drill down into specific LLM calls or tool executions within that step. This hierarchical navigation provides roughly a 10x improvement in problem isolation efficiency compared to flat span trees.

### 3.2 Skill Semantic Attributes (`gen_ai.skill.*`)

A "Skill" is an intermediate abstraction between a Tool and an Agent -- a reusable business-function unit that composes multiple LLM calls and tool invocations. For example, "check_order_status" is a Skill that calls `get_order_details()`, `query_shipping()`, and `format_response()`.

```python
# LoongSuite Skill span instrumentation
with handler.invoke_skill(
    skill_name="refund_processor",
    skill_version="v2.3.1",
    attributes={
        "gen_ai.skill.domain": "ecommerce.aftersales",
        "gen_ai.skill.p99_latency_ms": 3200,
        "gen_ai.skill.success_rate": 0.987,
    }
) as skill_span:
    # All child spans (llm.generate, tool.execute) inherit skill context
    results = await process_refund(order_id, reason)
```

Skill-level metrics enable per-skill P99 latency monitoring, success rate tracking, and call-frequency dashboards -- critical for business-aligned observability in production.

### 3.3 Token-Level Inference Deep Tracing ("Engine Microscope")

The most innovative LoongSuite contribution is per-token instrumentation of the inference engine itself, covering vLLM, SGLang, and TensorRT-LLM:

| Data | Captured Per Token |
|---|---|
| **Timing** | Scheduler queue time, actual GPU execution time, total perceived time |
| **Concurrency** | Batch size (number of concurrent requests sharing the same forward pass), inter-request interference visualization |
| **Probabilities** | Top-K candidate token probabilities, allowing diagnosis of "nonsensical answer" paths and temperature calibration |

**Production impact**: During Alibaba's peak sales events, the "Engine Microscope" reduced problem triage time by a factor of 10. When a model produces a gibberish response, operators can trace back to the exact token where probability distributions collapsed, identify whether the cause was batch interference or a bad sampling parameter, and remediate.

### 3.4 LoongSuite GenAI Utils

An engineering layer (`loongsuite-util-genai` in Python and JS) wraps the semantic conventions into ergonomic context-manager APIs:

```python
from loongsuite_util_genai import ExtendedTelemetryHandler

handler = ExtendedTelemetryHandler.get_instance()

# Agent invocation
with handler.invoke_agent(agent_name="shopping-assistant",
                          agent_id="agent-x7b2") as ctx:
    # LLM call -- auto-captures tokens, model, latency, errors
    with handler.invoke_llm(model="qwen3-max",
                            system="You are a helpful shopping assistant."):
        response = llm.generate(prompt)

    # Tool execution -- auto-captures name, args, result, duration
    with handler.execute_tool(tool_name="search_products",
                              tool_description="Search product catalog"):
        products = await search_products(query)

    # Retrieval
    with handler.invoke_retrieve(index_name="products_v3"):
        chunks = await vector_search(query_embedding, top_k=5)
```

The Utils layer ensures a single-upgrade propagation path: when the OTel GenAI SemConv spec evolves, only the Utils library needs updating, not every application.

---

## 4. Arize Phoenix

Arize Phoenix is an open-source (ELv2 license), OpenTelemetry-native AI observability and evaluation platform. It is framework-agnostic, self-hostable, and supports the full lifecycle from tracing to evaluation, dataset management, prompt engineering, and experimentation. Latest release: v15.7.0 (May 2026).

### 4.1 Architecture

```
┌── Agent Runtime ──────────────────────────────────────────────────┐
│  (LangGraph, CrewAI, LlamaIndex, Vercel AI SDK, Claude SDK, ...)  │
│           │                                                        │
│  OpenInference / OTel Auto-Instrumentation                         │
│           │                                                        │
│           ▼                                                        │
│  OTLP (HTTP :6006/v1/traces  or  gRPC :4317)                      │
└───────────┬────────────────────────────────────────────────────────┘
            │
┌───────────▼────────────────────────────────────────────────────────┐
│  Phoenix Server (Docker / K8s)                                     │
│  ┌───────────────┐  ┌───────────────┐  ┌────────────────────────┐ │
│  │ Span Ingestion │  │  PostgreSQL   │  │  Evaluation Engine     │ │
│  │ Queue (20k cap)│  │  (durable)    │  │  (LLM-as-Judge,        │ │
│  │ + backpressure │  │               │  │   embedding drift)     │ │
│  └───────────────┘  └───────────────┘  └────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

### 4.2 One-Line Instrumentation

```python
# Phoenix auto-instruments with zero config for supported frameworks
import phoenix as px

# Option 1: One-liner -- enables tracing for all supported frameworks
px.launch_app()

# Option 2: Register with Open Agent Spec agents (framework-agnostic)
from phoenix.trace.openagent import register
register(auto_instrument=True)  # Works with LangGraph, WayFlow, CrewAI, etc.

# Option 3: Explicit OTel setup for custom agents
from phoenix.otel import register
tracer_provider = register(
    project_name="my-agent-project",
    endpoint="http://localhost:6006/v1/traces",
)
```

### 4.3 LLM-as-Judge Evaluation

```python
from phoenix.evals import (
    HallucinationEvaluator,
    QAEvaluator,
    ToxicityEvaluator,
    run_evals,
)

# Evaluate traces against a labeled dataset
eval_results = run_evals(
    dataframe=traces_df,
    evaluators=[
        HallucinationEvaluator(model="gpt-4.1"),
        QAEvaluator(model="gpt-4.1"),
        ToxicityEvaluator(model="gpt-4.1"),
    ],
)
print(eval_results[["hallucination_score", "qa_correctness", "toxicity_score"]])

# Embedding drift detection (no labeled data required)
from phoenix.evals import EmbeddingDriftEvaluator
drift_report = EmbeddingDriftEvaluator().evaluate(
    reference_embeddings=baseline_embeddings,
    production_embeddings=current_embeddings,
)
```

### 4.4 ATIF Trajectory Support (April 2026)

Phoenix can ingest offline agent trajectories in ATIF (Agent Trajectory Interchange Format), converting them into queryable OpenTelemetry-compatible span trees:

```python
from phoenix.trace.atif import upload_atif_trajectories_as_spans

trajectories = load_atif_file("agent_runs.atif")
upload_atif_trajectories_as_spans(
    trajectories=trajectories,
    project_name="offline-eval-runs",
)
```

### 4.5 Key Strengths
- Genuinely open-source (ELv2), self-hostable with no usage limits
- Runtime-agnostic: identical instrumentation works across LangGraph, CrewAI, Claude SDK, OpenAI Agents SDK, Google ADK
- Strong RAG evaluation (embedding drift, retrieval relevance)
- Exploding integration ecosystem: 50+ framework instrumentors, TypeScript/Java subpackages, MCP server, CLI for AI coding agents

---

## 5. LangSmith (LangChain)

LangSmith is LangChain's proprietary observability platform purpose-built for the LangChain/LangGraph ecosystem. It has processed over 150 billion traces and evolved through three phases: Foundation (2023), Deepening (2024), and Ecosystem Expansion (2025--present).

### 5.1 Tracing Architecture

LangSmith implements a three-layer "Observability++" model:

```
Layer 1: Call-Chain Tracing
  ├── Distributed tracing with TraceIDs across LLM calls, tool executions,
  │   DB queries, and external APIs
  ├── Visualized as interactive tree diagrams with millisecond-precision timing
  └── Context metadata bound to every span: prompt templates, model config,
      environment variables, hardware accelerator type

Layer 2: Metrics Monitoring
  ├── Auto-collected: QPS, P99 latency, error-rate heatmaps
  ├── Custom business metrics: intent recognition accuracy, tool success rate
  └── Dynamic-baseline anomaly detection (alerts when error rate exceeds
      2x historical stddev for 3 consecutive collection points)

Layer 3: Log Analysis
  ├── Structured storage of full model I/O and intermediate states
  ├── Full-text search for rapid root-cause analysis
  └── Schema validation with Pydantic for data quality enforcement
```

### 5.2 LangSmith Setup

```python
# Zero-config tracing: one environment variable
# export LANGCHAIN_TRACING_V2=true
# export LANGCHAIN_API_KEY="ls_..."

from langchain.agents import create_react_agent
from langsmith import traceable

@traceable(run_type="agent", name="customer-support-bot")
def run_support_agent(user_query: str) -> str:
    agent = create_react_agent(llm, tools, prompt)
    result = agent.invoke({"input": user_query})
    return result["output"]


# LangGraph RemoteGraph distributed tracing
from langgraph_sdk import RemoteGraph

graph = RemoteGraph(
    "support-agent",
    distributed_tracing=True,  # Merges client + server traces
)

# All server-side spans become children of the client trace
# via traceparent header injection (langsmith-trace + traceparent)
result = await graph.ainvoke({"messages": [{"role": "user", "content": "..."}]})
```

### 5.3 Insights Agent & Polly Debugger

LangSmith's AI-powered debugging features:

- **Insights Agent**: LLM-based failure clustering that groups similar errors (e.g., "all cases where tool arguments were malformed JSON") into actionable insight cards.
- **Polly Debugger**: An interactive debugger that replays traces step-by-step, allowing developers to modify prompts, tool outputs, and model parameters at any point and re-execute the remainder of the trace.

### 5.4 Evaluation Framework

```python
from langsmith import Client, evaluate
from langsmith.evaluation import LangChainStringEvaluator

client = Client()

# Define evaluators
evaluators = [
    LangChainStringEvaluator("cot_qa"),              # Chain-of-thought QA scoring
    LangChainStringEvaluator("labeled_criteria",      # Custom rubric
        config={"criteria": "correctness"}),
]

# Run offline evaluation on a dataset
results = evaluate(
    lambda inputs: run_support_agent(inputs["question"]),
    data="support-tickets-dataset",   # Versioned dataset
    evaluators=evaluators,
    experiment_prefix="model-v3-eval",
)

# Online evaluation: patch a production trace with scores
client.update_run(
    run_id="trace-abc123",
    feedback={
        "user_satisfaction": 4,         # Human annotation
        "hallucination_score": 0.02,    # LLM-as-judge
    }
)
```

### 5.5 Key Strengths
- Near-zero setup for LangChain/LangGraph stacks (single env var)
- Mature evaluation engine: LLM-as-judge, human annotation queues, offline datasets, experiment tracking
- Insights Agent for AI-assisted failure clustering
- Support for hybrid cloud deployment (sensitive data on private cloud, compute on public cloud)

### 5.6 Key Limitation
LangSmith's deep integration comes at the cost of **framework lock-in**. Teams not using LangChain/LangGraph lose most of the zero-config advantages and must implement manual instrumentation.

---

## 6. Grafana Cloud AI Observability

Grafana Cloud AI Observability, launched in Q1 2026, combines the open-source OpenLIT instrumentation ecosystem with Grafana's managed LGTM stack (Loki, Grafana, Tempo, Mimir). It provides pre-built AI-native dashboards, zero-code Kubernetes injection, and vendor-neutral OpenTelemetry ingestion.

### 6.1 Zero-Code Injection via OpenLIT Operator

The OpenLIT Operator auto-injects OpenTelemetry instrumentation into Kubernetes pods without code changes or image rebuilds:

```yaml
# autoinstrumentation.yaml
apiVersion: openlit.io/v1alpha1
kind: AutoInstrumentation
metadata:
  name: ai-observability
  namespace: default
spec:
  selector:
    matchLabels:
      instrumentation: openlit
  python:
    instrumentation:
      enabled: true
      exporters:
        otlp:
          endpoint: "https://otlp-gateway-prod-us-central-0.grafana.net/otlp"
          headers:
            Authorization: "Basic ${GRAFANA_CLOUD_TOKEN_BASE64}"
  resource:
    attributes:
      deployment.environment: "production"
      service.namespace: "ai-agents"
```

```bash
# Deploy the operator and enable instrumentation
helm repo add openlit https://openlit.github.io/helm/
helm repo update
helm install openlit-operator openlit/openlit-operator

kubectl apply -f autoinstrumentation.yaml

# Restart pods -- instrumentation auto-injected via init containers
kubectl rollout restart deployment support-agent
```

### 6.2 Pre-Built Dashboards

| Dashboard | Monitors |
|---|---|
| **GenAI Overview** | Request rates, P95/P99 latency, token consumption, cost metrics across all model providers |
| **Agent Workflow** | Agent run lifecycle, step-by-step latency, tool call frequency, error breakdown by tool |
| **Evaluations** | Hallucination score, bias score, toxicity score, per-endpoint quality trends |
| **Vector DB** | Query latency, index size, recall@k, embedding throughput |
| **MCP Server** | Protocol health, tool performance, connection metrics |
| **GPU Monitoring** | DCGM metrics: utilization, memory, temperature, power draw |

### 6.3 TraceQL for Agent Trace Analysis

TraceQL is Grafana Tempo's query language for searching and analyzing traces. For AI agents:

```traceql
# Find all traces where an agent took more than 30 seconds
{ name = "gen_ai.agent.run" && duration > 30s }

# Find traces where a specific tool failed
{ name = "gen_ai.tool.execute" && gen_ai.tool.name = "search_knowledge_base"
  && status = error }

# Find high-cost traces (traces where total output tokens > 5000)
{ name = "gen_ai.llm.generate" && gen_ai.usage.output_tokens > 5000 }

# Correlate guardrail failures with LLM calls
{ name = "gen_ai.guardrail.check" && gen_ai.guardrail.verdict = "block" }
>> { name = "gen_ai.llm.generate" }
```

### 6.4 OpenAI Agents SDK Integration

Grafana provides a custom OTel span processor for the OpenAI Agents SDK:

```python
from agents import Agent, Runner
from openlit_otel import OpenLitSpanProcessor
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

# Setup OTel with OpenLIT processor
exporter = OTLPSpanExporter(
    endpoint="https://otlp-gateway-prod-us-central-0.grafana.net/otlp",
    headers={"Authorization": f"Basic {grafana_token_b64}"},
)
provider = TracerProvider()
provider.add_span_processor(OpenLitSpanProcessor())
provider.add_span_processor(BatchSpanProcessor(exporter))

# Agent runs are automatically traced
agent = Agent(
    name="SupportBot",
    instructions="You are a helpful support agent.",
    model="gpt-4.1",
)

result = await Runner.run(agent, "My order hasn't arrived")
# Full trace auto-emitted: agent.run → llm.generate → tool.execute → llm.generate
```

### 6.5 Key Strengths
- Zero-code Kubernetes instrumentation for 50+ GenAI frameworks
- Full-stack observability: infra metrics (CPU, GPU, memory) plus AI telemetry in one platform
- Vendor-neutral: OpenTelemetry-native, no framework lock-in
- Grafana Assistant (free AI agent) for natural-language querying of metrics and traces

---

## 7. Splunk AI Agent Monitoring (April 2026)

Splunk AI Agent Monitoring went GA in February 2026 as part of Splunk Observability Cloud, with major enhancements in March--April 2026. Cisco's acquisition of Galileo Technologies (April 2026) added real-time AI guardrail enforcement. Splunk provides three ingestion modes and integrates deeply with its broader security and APM portfolio.

### 7.1 Three Ingestion Modes

| Mode | How It Works | Best For |
|---|---|---|
| **Zero-Code** | Auto-instrumentation for LangChain, OpenAI, Anthropic, and other supported frameworks via `splunk-otel-python-contrib` | Quick onboarding for common stacks |
| **Code-Level** | Manual instrumentation with `splunk-otel-util-genai` wrapper APIs (Workflow, AgentInvocation, LLMInvocation, ToolInvocation) | Custom agents and frameworks not covered by zero-code |
| **Third-Party Translation** | Ingests traces from existing instrumentation libraries (OpenLIT, OpenLLMetry, LangSmith) by translating to Splunk's span format | Brownfield deployments with existing observability investment |

### 7.2 Code-Level Instrumentation

```python
from splunk_otel_util_genai import (
    WorkflowHandler,
    AgentInvocation,
    LLMInvocation,
    ToolInvocation,
)

handler = WorkflowHandler.get_instance()

# Define a workflow
with handler.workflow(name="customer_support", agent_type="react") as wf:

    # Agent invocation span
    agent_inv = AgentInvocation(
        agent_name="support-bot",
        agent_id="agent-4f2a8b",
        input_text=user_query,
    )
    with handler.invoke_agent(agent_inv):
        # LLM call
        llm_inv = LLMInvocation(
            model_name="claude-sonnet-4-5",
            provider="anthropic",
            input_messages=[{"role": "user", "content": user_query}],
        )
        with handler.invoke_llm(llm_inv) as llm_ctx:
            response = llm.generate(user_query)
            llm_ctx.set_output(response)

        # Tool execution
        tool_inv = ToolInvocation(
            tool_name="lookup_customer",
            tool_arguments={"customer_id": "CUST-789"},
        )
        with handler.execute_tool(tool_inv) as tool_ctx:
            result = await lookup_customer("CUST-789")
            tool_ctx.set_output(result)
```

### 7.3 DeepEval Integration for Quality Scoring

Splunk embeds DeepEval as its quality scoring engine, providing built-in evaluation dimensions:

```python
from splunk_otel_util_genai.evals import DeepEvalScorer
from deepeval.metrics import (
    HallucinationMetric,
    BiasMetric,
    ToxicityMetric,
    AnswerRelevancyMetric,
)

scorer = DeepEvalScorer(
    metrics=[
        HallucinationMetric(threshold=0.8),
        BiasMetric(threshold=0.9),
        ToxicityMetric(threshold=0.95),
        AnswerRelevancyMetric(threshold=0.7),
    ],
    llm_provider="gpt-4.1",  # The judge model
)

# Attach scores to traces in real-time
with handler.invoke_agent(agent_inv) as ctx:
    response = await run_agent(user_query)

    scores = await scorer.evaluate(
        input_text=user_query,
        output_text=response,
        context=retrieved_docs,  # Optional retrieval context
    )
    ctx.set_evaluation_scores(scores)  # Flows to Splunk APM as span attributes
```

### 7.4 Cisco AI Defense Integration (April 2026)

The Galileo acquisition adds real-time guardrail enforcement:

- **Prompt injection detection**: Monitors incoming prompts for jailbreak patterns, blocks before model processing.
- **PII/credential redaction**: Automatically masks sensitive data in both inputs and outputs.
- **Policy enforcement**: Blocks outputs that violate organizational content policies.
- **Session replay**: Reconstructs full agent-user interaction sequences for audit and compliance.

### 7.5 APM Trace Correlation

Because Splunk AI Agent Monitoring is part of Splunk Observability Cloud, AI-specific spans are first-class citizens in the APM trace view:

```
APM Trace Waterfall
├── HTTP Request (frontend → API gateway)        [  12ms]
├── Auth middleware                                [   3ms]
├── Agent Run: support-bot                        [2847ms]
│   ├── LLM Generate: claude-sonnet-4-5           [ 412ms]
│   │   ├── Prompt assembly                       [   8ms]
│   │   └── Token generation (1240 in, 85 out)    [ 404ms]
│   ├── Tool Execute: lookup_customer             [  89ms]
│   │   └── PostgreSQL Query                      [  78ms]
│   ├── LLM Generate: claude-sonnet-4-5           [ 365ms]
│   ├── Tool Execute: query_shipping              [ 142ms]
│   │   └── HTTP GET /shipping/v2/orders/...      [ 131ms]
│   └── Guardrail Check: output                   [  23ms]
├── Response formatting                            [   5ms]
│
├── Evaluation Scores (attached by DeepEval)
│   hallucination: 0.92  │  bias: 0.97  │  toxicity: 0.99  │  relevancy: 0.85
```

### 7.6 Key Strengths
- Three ingestion modes: zero-code, code-level, and third-party translation
- DeepEval integration for quality scoring across five dimensions
- Cisco AI Defense for real-time guardrail enforcement
- Unified platform: APM + AI observability + security (SIEM) in one
- MCP Server support for connecting AI agents to Splunk's full data corpus

### 7.7 Key Limitation
Enterprise-only pricing (no free tier, no self-serve). Typical mid-market contracts start around $78K/year; large deployments can exceed $2M/year. Complex setup (days-to-weeks). The AI Agent Monitoring component is relatively new (GA February 2026).

---

## 8. Pydantic Logfire

Pydantic Logfire is a Python-first observability platform purpose-built by the Pydantic team. It combines structured logging, OpenTelemetry-native distributed tracing, and LLM-specific monitoring with deep Pydantic integration and an SQL-based query interface (DataFusion). In 2025, the Pydantic AI Gateway was merged into Logfire, creating a unified platform for both routing LLM calls and observing them.

### 8.1 Architecture

```
┌─ Application ────────────────────────────────────────────────────┐
│                                                                   │
│  PydanticAI (agent framework)                                     │
│      │                                                            │
│  InstrumentedModel (wrapper -- auto-adds OTel spans)              │
│      │                                                            │
│  Logfire.instrument_pydantic()  /  .instrument_sqlite3()          │
│  Logfire.instrument_fastapi()    (auto-request tracing)           │
│      │                                                            │
│  OpenTelemetry SDK + OTLP Exporter                                │
│      │                                                            │
└──────┼────────────────────────────────────────────────────────────┘
       │
       ▼
┌─ Logfire Platform ────────────────────────────────────────────────┐
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────┐ │
│  │ Span Ingestion│  │  DataFusion   │  │  LLM Playground         │ │
│  │ (OTLP)       │  │  (SQL Engine) │  │  (prompt testing + trace)│ │
│  └──────────────┘  └──────────────┘  └─────────────────────────┘ │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ AI Gateway (merged into Logfire)                              │ │
│  │  • Routes LLM calls to multiple providers                     │ │
│  │  • Every request auto-traced with token counts and costs      │ │
│  │  • Single account for routing + observing                     │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ MCP Server                                                    │ │
│  │  • Coding agents (Claude, Cursor) query production logs       │ │
│  │  • Side-by-side: source code + production telemetry           │ │
│  └──────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
```

### 8.2 Instrumenting a PydanticAI Agent

```python
import logfire
from pydantic_ai import Agent

# One line enables full observability
logfire.configure(
    service_name="support-agent",
    send_to_logfire=True,  # or use OTLP exporter for self-hosted
)

logfire.instrument_pydantic()  # Auto-traces Pydantic validation
logfire.instrument_fastapi()   # Auto-traces HTTP requests

# Agents are auto-instrumented via InstrumentedModel wrapping
support_agent = Agent(
    "openai:gpt-4.1",
    system_prompt="You are a helpful support agent.",
    instrument=True,  # Enables OTel spans for every model call
)

# All tool calls, retries, and result validations are traced
@support_agent.tool
async def lookup_order(order_id: str) -> dict:
    """Look up an order by ID."""
    return await db.fetch_order(order_id)

# Run agent -- full trace auto-emitted
result = await support_agent.run("Where is my order #12345?")
print(f"Trace available at: {logfire.trace_url()}")

# Cost tracking is automatic via the genai-prices library
# Histograms: gen_ai.client.token.usage and operation.cost
```

### 8.3 Structured Logging

```python
import logfire

# Structured logging with automatic OTel correlation
with logfire.span("process_refund") as span:
    span.set_attribute("order_id", "ORD-12345")

    logfire.info(
        "refund_initiated",
        order_id="ORD-12345",
        amount=49.99,
        currency="USD",
        reason="damaged_item",
    )

    # Errors are automatically captured with full stack traces
    try:
        result = await payment_gateway.refund("ORD-12345", 49.99)
    except GatewayTimeoutError as e:
        logfire.error(
            "refund_failed",
            order_id="ORD-12345",
            error_type=type(e).__name__,
            retry_count=3,
        )
        raise
```

### 8.4 SQL-Based Monitoring

Logfire uses Apache DataFusion to enable standard SQL queries across traces and logs, eliminating the need for a proprietary query language:

```sql
-- Find all agent runs where hallucination score was low
SELECT trace_id,
       span_name,
       attributes['gen_ai.agent.name'] AS agent_name,
       attributes['hallucination_score'] AS score
FROM spans
WHERE span_name = 'gen_ai.agent.run'
  AND attributes['hallucination_score'] < 0.7
ORDER BY start_time DESC
LIMIT 50;

-- Cost per agent per day
SELECT date_trunc('day', start_time) AS day,
       attributes['gen_ai.agent.name'] AS agent,
       sum(attributes['operation.cost']) AS total_cost,
       sum(attributes['gen_ai.usage.output_tokens']) AS output_tokens
FROM spans
WHERE span_name = 'gen_ai.llm.generate'
GROUP BY day, agent
ORDER BY day DESC, total_cost DESC;
```

### 8.5 Key Strengths
- Deepest Pydantic integration in the market (native serialization, validation tracing)
- SQL-based querying via DataFusion (no proprietary query language)
- Low overhead (< 1ms per span in benchmarks)
- MCP server for AI-assisted debugging
- Self-hosted and cloud options; on-premises option used by enterprises like Sophos

---

## 9. Microsoft Agent Framework Observability

The Microsoft Agent Framework (MAF) provides first-class OpenTelemetry support with deep Azure Monitor and Application Insights integration. A dedicated **Agents (Preview)** view in Application Insights, launched Q1 2026, provides purpose-built dashboards for multi-agent systems.

### 9.1 Built-in OpenTelemetry Support

MAF automatically emits OpenTelemetry spans at two layers:

```csharp
// .NET: ChatClient-level instrumentation (model metrics)
using Microsoft.Extensions.AI;

var chatClient = new OpenAIChatClient("gpt-4.1")
    .AsBuilder()
    .UseOpenTelemetry(sourceName: "MyApp.Chat")
    .Build();

// .NET: Agent-level instrumentation (agent identity + lifecycle)
using Microsoft.Agents.AI;

var agent = new ChatClientAgent(chatClient)
    .AsBuilder()
    .UseOpenTelemetry(sourceName: "MyApp.Agent")  // Auto-wraps IChatClient
    .Build();

// All spans auto-emit:
//   gen_ai.agent.name, gen_ai.agent.id
//   gen_ai.request.model, gen_ai.usage.input_tokens, gen_ai.usage.output_tokens
//   Tool call spans with tool name, arguments, result
```

**Breaking change (May 2026)**: `OpenTelemetryAgent` now auto-wraps the inner `ChatClientAgent`'s `IChatClient` with `OpenTelemetryChatClient`, so chat-level telemetry flows automatically without explicit double-instrumentation. An opt-out flag (`autoWireChatClient: false`) is available for edge cases.

### 9.2 Azure Monitor + Application Insights Export

```csharp
// ASP.NET Core: Single-line Azure Monitor integration
builder.Services.AddOpenTelemetry()
    .UseAzureMonitor();  // Auto-discovers APPLICATIONINSIGHTS_CONNECTION_STRING

// Non-ASP.NET (e.g., WebJobs, console apps)
builder.Services.AddOpenTelemetry()
    .WithTracing(t => t
        .AddAzureMonitorTraceExporter()
        .AddSource("MyApp.Agent")
        .AddSource("MyApp.Chat"))
    .WithMetrics(m => m
        .AddAzureMonitorMetricExporter()
        .AddMeter("Microsoft.Agents.AI"));
```

### 9.3 Agents (Preview) View in Application Insights

The **Agents (Preview)** blade provides:

- **Agent dropdown filter**: Populated dynamically from `gen_ai.agent.name` span attributes across all monitored applications.
- **Token usage metrics**: Input/output token consumption broken down per agent, per model, per endpoint.
- **Workflow visualization**: End-to-end transaction waterfall showing which agents were invoked, tools called, model calls made, and duration at each step.
- **Operational metrics**: P50/P95/P99 latency, error rate, throughput per agent.
- **One-click Grafana export**: Export agent telemetry to Azure Managed Grafana for custom dashboarding.

### 9.4 Python / FastAPI Integration

```python
from azure.monitor.opentelemetry import configure_azure_monitor
from opentelemetry import trace
from agent_framework import Agent

# Must be called before any workflow.run() calls
configure_azure_monitor(
    connection_string="InstrumentationKey=..."
)

tracer = trace.get_tracer(__name__)

@tracer.start_as_current_span("gen_ai.agent.run")
async def run_agent(query: str) -> str:
    agent = Agent.load("support-agent")
    result = await agent.run(query)
    return result
```

### 9.5 Key Strengths
- Deepest Azure ecosystem integration: Application Insights, Azure Monitor, Managed Grafana, Log Analytics, Sentinel
- Auto-instrumentation at both ChatClient and Agent layers with zero manual span creation
- Agents (Preview) view purpose-built for multi-agent workflows
- Migration path from Prompt Flow (retiring April 2027) to MAF with OTel observability
- Azure Copilot observability agent for AI-assisted root cause analysis of production issues

---

## 10. Key Observability Metrics

### 10.1 Performance Metrics

| Metric | Description | Target (Interactive) | Target (Batch) |
|---|---|---|---|
| **TTFT** (Time to First Token) | Delay between prompt submission and first response token | P95 < 800ms | P95 < 5s |
| **TBT / TPOT** (Time Between Tokens) | Average inter-token latency during streaming | < 50ms | < 100ms |
| **End-to-End Latency** | Full request duration including all tool calls, retrieval, and generation | P95 < 10s | P95 < 60s |
| **Tokens per Second** | System throughput capacity | > 50 t/s | > 200 t/s |
| **Requests per Second** | Concurrency handling capacity | Per-deployment target | Per-deployment target |
| **Queue Depth** | Pending requests in the inference queue | < 5 | < 20 |

TTFT is the single most important user-perception metric for interactive agents. Glean's research shows every additional input token adds approximately 0.24ms to P95 TTFT. TTFT is increasingly used as a Kubernetes autoscaling signal via KEDA + PromQL instead of traditional CPU/memory metrics, since GPU KV-cache pre-allocation renders standard HPA metrics ineffective.

### 10.2 Cost Metrics

| Metric | Description | Alert Threshold |
|---|---|---|
| **Tokens per Request** | Total input + output tokens per agent run | Alert at 2x historical baseline |
| **Cost per Request** | Monetary cost per agent invocation | Alert at 3x historical baseline |
| **Cost per Session** | Cumulative cost across a multi-turn conversation | Circuit break at $5/session |
| **Cache Hit Rate** | Percentage of prompts served from semantic cache | Alert below 20% |
| **Cost by Provider** | Spend breakdown across OpenAI, Anthropic, Google, etc. | Per-budget thresholds |
| **Cost Velocity** | Rate of spending increase (early warning of runaway usage) | Alert on > 50% hour-over-hour |

**Critical insight**: Output tokens cost 3--5x more than input tokens across all major providers (e.g., Claude Sonnet 4.5: $3/M input vs $15/M output). Cost observability must separately track input and output token consumption to surface optimization opportunities.

### 10.3 Quality Metrics

| Metric | Definition | Measurement Method |
|---|---|---|
| **Hallucination Rate** | Percentage of responses containing fabricated claims | LLM-as-Judge (GPT-4.1) or human review |
| **Task Completion Rate** | Percentage of tasks resolved without human escalation | Automatic (agent self-reports final state) |
| **Tool Call Success Rate** | Percentage of tool invocations returning valid results | Automatic (parse tool output) |
| **Groundedness / Faithfulness** | Whether claims are supported by retrieved evidence | LLM-as-Judge with retrieval context |
| **Citation Accuracy** | Whether cited sources actually support the claim | Human annotation (spot-check) |
| **Relevance** | Whether the response addresses user intent | LLM-as-Judge |
| **Coherence** | Logical consistency of multi-paragraph outputs | LLM-as-Judge |
| **Retrieval Precision** | Fraction of retrieved chunks that are relevant | Automatic (labeled evaluation dataset) |

### 10.4 Safety Metrics

| Metric | Description |
|---|---|
| **Guardrail Trigger Rate** | Percentage of requests where any guardrail fires |
| **Block Rate** | Percentage of requests blocked by guardrails (input or output) |
| **PII Leak Events** | Instances where PII/credentials appear in model output |
| **Prompt Injection Detections** | Jailbreak/manipulation attempts identified |
| **Toxicity Score** | Continuous 0--1 score from DeepEval or similar |
| **Bias Score** | Continuous 0--1 score measuring demographic fairness |
| **Refusal Accuracy** | Whether the model appropriately refuses harmful requests without over-blocking |

### 10.5 Operational Metrics

| Metric | Description |
|---|---|
| **Agent Error Rate** | Percentage of agent runs resulting in unhandled errors |
| **Tool Timeout Rate** | Percentage of tool calls exceeding their timeout budget |
| **Retry Rate** | Average number of retries per agent step |
| **Loop Detection Rate** | Percentage of runs where the agent enters a recursive call pattern |
| **Human Escalation Rate** | Percentage of sessions escalated to human review |

---

## 11. Agent Evaluation in Observability

### 11.1 Online vs. Offline Evaluation

```
┌── Online Evaluation (Real-Time) ──────────────────────────────────┐
│                                                                     │
│  Production Trace ──► Sample (5--10%) ──► LLM-as-Judge Scorer      │
│       │                                      │                      │
│       │                              ┌───────┼───────┐              │
│       │                              ▼       ▼       ▼              │
│       │                          Halluc.  Toxicity  Relevancy       │
│       │                              │       │       │              │
│       │                              └───┬───┘       │              │
│       ▼                                  ▼           ▼              │
│  Attach scores to trace ──► Dashboard ──► Alert if score < threshold│
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌── Offline Evaluation (Historical) ─────────────────────────────────┐
│                                                                     │
│  Historical Traces ──► Labeled Dataset ──► Batch Evaluation         │
│       │                                      │                      │
│       │                              ┌───────┼───────┐              │
│       │                              ▼       ▼       ▼              │
│       │                          LLM Judge Human   Heuristic        │
│       │                              │       │       │              │
│       │                              └───┬───┘       │              │
│       ▼                                  ▼           ▼              │
│  Compare experiments ──► Regression detection ──► Model upgrade gate│
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 11.2 LLM-as-a-Judge: Scoring Dimensions

```python
# Complete LLM-as-Judge evaluation setup
from deepeval import evaluate
from deepeval.metrics import (
    HallucinationMetric,
    AnswerRelevancyMetric,
    ToxicityMetric,
    BiasMetric,
    FaithfulnessMetric,
)
from deepeval.test_case import LLMTestCase

# Define test cases from production traces
test_cases = [
    LLMTestCase(
        input="What's the refund policy for damaged items?",
        actual_output="Our refund policy allows returns within 30 days...",
        expected_output="Returns accepted within 30 days, full refund for damaged items...",
        context=["Refund Policy v3: 30-day return window, full refund for defects..."],
        retrieval_context=["doc-001: Refund Policy v3"],
    )
    for trace in sampled_production_traces
]

# Run multi-dimensional evaluation
results = evaluate(
    test_cases=test_cases,
    metrics=[
        HallucinationMetric(threshold=0.7, model="gpt-4.1"),
        AnswerRelevancyMetric(threshold=0.7, model="gpt-4.1"),
        FaithfulnessMetric(threshold=0.8, model="gpt-4.1"),
        ToxicityMetric(threshold=0.9, model="gpt-4.1"),
        BiasMetric(threshold=0.9, model="gpt-4.1"),
    ],
    run_async=True,   # Evaluate all dimensions in parallel
    verbose_mode=False,
)

# Gate: only deploy if all metrics pass
if all(r.score >= r.metric.threshold for r in results):
    promote_to_production(new_model_version)
```

### 11.3 LLM-as-Judge Prompt Template

```
You are evaluating a response from an AI support agent. Your task is to score
the response on HALLUCINATION.

GRADING SCALE:
  1.0 - No hallucinations. All claims are factually supported by the provided context.
  0.7 - Minor hallucination. One unsupported detail that does not affect correctness.
  0.4 - Moderate hallucination. Multiple unsupported claims or one significant fabrication.
  0.0 - Severe hallucination. The response is entirely fabricated or contradictory to context.

USER QUERY: {input}
RETRIEVED CONTEXT: {context}
AGENT RESPONSE: {output}

Respond with a JSON object:
{
  "score": <float 0.0-1.0>,
  "reasoning": "<detailed justification referencing specific claims and context>"
}
```

### 11.4 Agent-as-a-Judge (2026 Paradigm Shift)

A major shift is underway from single-pass LLM-as-Judge to **Agent-as-a-Judge**. Single-pass judges struggle with multi-step verification, evidence collection, and bias mitigation. Agentic evaluators address these limitations:

| Capability | LLM-as-Judge (2025) | Agent-as-a-Judge (2026) |
|---|---|---|
| Reasoning | Single-pass evaluation | Multi-step planning and decomposition |
| Verification | Language-only | Tool-augmented (code execution, web search, database lookup) |
| Bias Mitigation | Prompt engineering | Multi-agent debate and collaboration |
| Memory | None | Persistent intermediate state across evaluation steps |
| Grounding | Parametric knowledge only | Real-world observation and evidence collection |

Frameworks like **D3-Judge** (January 2026) implement courtroom-inspired architectures with advocates, judge, and jury agents. Budgeted stopping (automated debate termination) reduces token consumption by 40% without sacrificing accuracy. Reported accuracy of 86.3% on standard benchmarks -- a 12.6% improvement over single-judge baselines.

### 11.5 Human Annotation Workflows

```python
# LangSmith annotation queue example
from langsmith import Client

client = Client()

# Query traces that need human review
low_confidence_traces = client.list_runs(
    project_name="support-agent-prod",
    filter='eq(feedback.hallucination_score, None) or lt(feedback.hallucination_score, 0.6)',
    limit=100,
)

# Add to annotation queue
for trace in low_confidence_traces:
    client.create_feedback(
        run_id=trace.id,
        key="needs_human_review",
        score=1.0,
        comment="Low confidence hallucination score",
    )

# Annotators review in LangSmith UI and submit corrected scores
# Metrics: inter-annotator agreement (Cohen's kappa), review throughput
```

---

## 12. Production Observability Architecture

### 12.1 Complete Architecture Diagram

```
┌──── Agent Runtime (Kubernetes) ───────────────────────────────────┐
│                                                                     │
│  ┌───────────┐   ┌──────────┐   ┌───────────┐   ┌──────────────┐ │
│  │ LangGraph  │   │ CrewAI   │   │  Custom   │   │  OpenAI      │ │
│  │  Agent     │   │  Agent   │   │  Agent    │   │  Agents SDK  │ │
│  └─────┬─────┘   └────┬─────┘   └─────┬─────┘   └──────┬───────┘ │
│        │              │              │                   │         │
│  ┌─────┴──────────────┴──────────────┴───────────────────┴───────┐ │
│  │  OpenTelemetry SDK + Auto-Instrumentation                     │ │
│  │  (OTel GenAI SemConv spans, metrics, logs)                    │ │
│  └──────────────────────────┬────────────────────────────────────┘ │
│                             │                                      │
└─────────────────────────────┼──────────────────────────────────────┘
                              │ OTLP (gRPC :4317 / HTTP :4318)
                              ▼
┌──── OTel Collector Gateway ────────────────────────────────────────┐
│                                                                     │
│  ┌──────────────────────┐  ┌──────────────────────┐                │
│  │ Tail Sampling         │  │ Data Redaction        │                │
│  │  • Errors: 100%       │  │  • Strip gen_ai.input │                │
│  │  • LLM calls: 100%    │  │    .messages content  │                │
│  │  • Healthy: 10%       │  │  • Hash sensitive attrs│               │
│  │  • High-cost: 100%    │  │  • Delete PII fields   │                │
│  └──────────────────────┘  └──────────────────────┘                │
│                             │                                      │
│                     ┌───────┼───────┐                              │
│                     ▼       ▼       ▼                              │
│                   OTLP Exporters (fan-out)                          │
└─────────────────────┬───────┬───────┬──────────────────────────────┘
                      │       │       │
          ┌───────────┘       │       └───────────┐
          ▼                   ▼                   ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
│  Tempo/Jaeger │  │  Prometheus   │  │  Elasticsearch    │
│  (Traces)     │  │  (Metrics)    │  │  + Kibana (Logs)  │
│               │  │               │  │                   │
│  • TraceQL    │  │  • PromQL     │  │  • Full-text      │
│  • Span search│  │  • Recording  │  │    search          │
│  • Service    │  │    rules      │  │  • Structured     │
│    graphs     │  │  • Alertmanager│  │    log parsing    │
└──────┬───────┘  └──────┬───────┘  └────────┬─────────┘
       │                 │                    │
       └─────────┬───────┴────────────────────┘
                 │
                 ▼
┌──── Grafana Unified Visualization ─────────────────────────────────┐
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
│  │ Agent Health  │  │ Cost         │  │ Quality Scorecard         │ │
│  │ Dashboard     │  │ Dashboard    │  │ Dashboard                 │ │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘ │
│                                                                     │
│  Alerts → Slack / PagerDuty / Opsgenie / Webhook                    │
└─────────────────────────────────────────────────────────────────────┘
```

### 12.2 OTel Collector Configuration

```yaml
# otelcol-config.yaml -- Production-grade collector for AI agents
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  # Tail sampling: keep 100% of errors and LLM calls, sample healthy traces
  tail_sampling:
    decision_wait: 30s               # Wait for full trace before deciding
    num_traces: 50000                # In-memory cache size
    policies:
      # Keep ALL traces with errors
      - name: errors
        type: status_code
        status_code:
          status_codes: [ERROR]

      # Keep ALL traces containing LLM calls (cost tracking)
      - name: llm-calls
        type: string_attribute
        string_attribute:
          key: gen_ai.system
          values: [".*"]
          enabled_regex_matching: true

      # Keep ALL traces where cost exceeds threshold
      - name: high-cost
        type: numeric_attribute
        numeric_attribute:
          key: gen_ai.usage.output_tokens
          min_value: 5000

      # Sample remaining healthy traces at 10%
      - name: healthy-sampling
        type: probabilistic
        probabilistic:
          sampling_percentage: 10

  # Redact sensitive data before export
  attributes:
    actions:
      - key: gen_ai.input.messages
        action: delete
      - key: gen_ai.output.messages
        action: delete
      - key: user.email
        action: hash
      - key: user.phone
        action: delete

  # Batch before export to reduce network overhead
  batch:
    timeout: 5s
    send_batch_size: 512

exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true

  prometheusremotewrite:
    endpoint: "http://prometheus:9090/api/v1/write"

  otlphttp/elastic:
    endpoint: "http://elastic-apm:8200"

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [tail_sampling, attributes, batch]
      exporters: [otlp/tempo]
    metrics:
      receivers: [otlp]
      processors: [attributes, batch]
      exporters: [prometheusremotewrite]
```

### 12.3 Prometheus Recording Rules for AI Agents

```yaml
# prometheus-rules.yaml
groups:
  - name: ai_agent_alerts
    rules:
      # Cost: alert when hourly spend exceeds budget
      - alert: HighAgentCost
        expr: |
          sum by (agent_name) (
            rate(gen_ai_client_operation_cost_total[5m]) * 3600
          ) > 100
        for: 15m
        labels:
          severity: warning
        annotations:
          summary: "Agent {{ $labels.agent_name }} cost exceeds $100/hour"
          description: "Current rate: {{ $value | humanize }}/hour"

      # Quality: alert when hallucination rate spikes
      - alert: HighHallucinationRate
        expr: |
          avg by (agent_name) (
            agent_hallucination_score
          ) < 0.6
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Agent {{ $labels.agent_name }} hallucination score below 0.6"

      # Performance: alert on TTFT degradation
      - alert: HighTTFT
        expr: |
          histogram_quantile(0.95,
            rate(gen_ai_server_time_to_first_token_bucket[5m])
          ) > 1.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "P95 TTFT exceeds 1 second"

      # Safety: alert on guardrail trigger rate spike
      - alert: HighGuardrailTriggerRate
        expr: |
          rate(gen_ai_guardrail_trigger_total[5m]) /
          rate(gen_ai_agent_run_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Guardrail trigger rate exceeds 10% of requests"

      # Operational: alert on recursive agent loops
      - alert: AgentLoopDetected
        expr: |
          rate(agent_step_count[5m]) > 15
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Agent {{ $labels.agent_name }} averaging >15 steps per run"
```

### 12.4 Sampling Strategies for High-Volume Agent Traces

| Strategy | When to Use | Recommended Rate |
|---|---|---|
| **Error-based** | Production monitoring | 100% of traces with `status=ERROR` |
| **Cost-based** | Cost monitoring | 100% of traces with `output_tokens > 5000` or `cost > $1` |
| **LLM-call-based** | Model performance monitoring | 100% of traces containing `gen_ai.llm.generate` spans |
| **Probabilistic** | Healthy path telemetry | 5--10% for online evaluation scoring |
| **New-model-based** | Model rollout validation | 100% for first 24 hours after model version change |
| **User-segment-based** | VIP / beta monitoring | 100% for specific user segments or feature flags |

**Rule of thumb**: Even at 10% sampling, ensure error traces, high-cost traces, and safety violations are always captured at 100%. The OTel Collector's tail sampling processor makes this possible -- it evaluates the complete trace before deciding whether to keep it.

### 12.5 Compliance Audit Trail (EU AI Act + ISO 42001)

The EU AI Act (full enforcement August 2026) and ISO 42001 demand auditable, time-stamped records of AI system behavior. For agentic systems, this means trace-level audit logging:

```
Compliance Requirements Mapping:

EU AI Act Art. 12 (Record-Keeping)
  ├── Automatic logging of events during operation
  │   └── Implemented via: OTel spans with agent ID, model version, input hash
  ├── 6-month minimum retention for high-risk systems
  │   └── Implemented via: Tiered storage (hot: 7d SSD, warm: 30d HDD, cold: 6mo object store)
  └── Logging must be proportionate to risk level
      └── Implemented via: Tail sampling adjusted by risk classification

ISO 42001 §6.1 (AI System Inventory)
  ├── Centralized register of every AI tool/model
  │   └── Implemented via: Resource attributes (service.name, gen_ai.agent.id) auto-indexed
  ├── Per-deployment documentation: intended purpose, limitations
  │   └── Implemented via: Agent metadata spans (gen_ai.agent.description, model cards)
  └── Living, exportable evidence
      └── Implemented via: OTLP export to long-term compliant storage (immutable, signed)

Attributability (Multi-Agent Audit)
  ├── Every output traceable to specific agent ID + model version + prompt hash
  │   └── Implemented via: Span attributes linked to every output span
  ├── Human oversight decisions logged with reviewer identity + timestamp
  │   └── Implemented via: HITL spans (hitl.wait → hitl.decision) with reviewer metadata
  └── Immutable audit trails with tamper detection
      └── Implemented via: WORM storage + hash-chain verification on trace archives
```

```python
# Compliance-enriched trace attributes
from opentelemetry.sdk.resources import Resource

resource = Resource.create({
    "service.name": "refund-agent",
    "service.version": "v2.3.1",
    "ai.system.risk_category": "limited",           # EU AI Act classification
    "ai.system.iso42001_certified": "true",
    "ai.system.retention_period_days": "180",        # 6-month minimum
    "ai.system.human_oversight_required": "true",
    "ai.system.transparency_log_enabled": "true",
})

# Attach compliance metadata to every span
with tracer.start_as_current_span(
    "gen_ai.agent.run",
    attributes={
        "gen_ai.agent.id": "refund-agent-v2-3-1",
        "gen_ai.request.model": "claude-sonnet-4-5",
        "ai.input.hash": sha256(user_input),      # Immutable input fingerprint
        "ai.human_oversight.status": "pending",
        "ai.compliance.framework": "eu_ai_act,iso_42001",
    }
) as span:
    # ... agent execution ...
    span.set_attribute("ai.output.hash", sha256(final_output))
    span.set_attribute("ai.human_oversight.status", "approved")
    span.set_attribute("ai.human_oversight.reviewer_id", "agent-auditor-7")
```

---

## 13. Tool Comparison Table

| Dimension | **Arize Phoenix** | **LangSmith** | **Grafana Cloud AI Obs.** | **Splunk AI Agent Mon.** | **Pydantic Logfire** |
|---|---|---|---|---|---|
| **License** | Open-source (ELv2) | Proprietary SaaS | OSS platform + Managed Cloud | Enterprise SaaS | Proprietary + OSS SDK |
| **Self-Hosted** | Yes (Docker) | Enterprise only | Yes (Grafana OSS) | No | Yes (on-prem option) |
| **OTel Native** | Yes (best-in-class) | Partial (OTLP export) | Yes | Yes | Yes |
| **GenAI SemConv** | Full support | Supported | Full via OpenLIT | Full + AGNTCY | Full |
| **Auto-Instrument** | 50+ frameworks | LangChain/LangGraph only | 50+ frameworks via OpenLIT | 30+ frameworks | PydanticAI native + OTel |
| **Zero-Code K8s** | No | No | Yes (OpenLIT Operator) | Partial | No |
| **Evaluation Engine** | LLM-as-Judge, embedding drift | LLM-as-Judge, human annotation | LLM-as-Judge, heuristics | DeepEval (5 dimensions) | LLM-as-Judge |
| **Agent-as-Judge** | No | No | No | No | No |
| **Guardrail Enforcement** | No | No | No | Yes (Cisco AI Defense) | No |
| **Issue Discovery** | Manual | Insights Agent (AI clustering) | Manual (alerting only) | ML-based anomaly detection | Manual |
| **Human Annotation UI** | Limited | Mature | No | No | No |
| **Experiment Tracking** | Yes | Yes | No | No | No |
| **Prompt Management** | Yes (versioned) | Yes (versioned) | No | No | No |
| **SIEM Integration** | No | No | No (but reuses Grafana) | Yes (native Splunk ES) | No |
| **Compliance Features** | Audit log export | Audit log export | Audit log export | Full compliance suite | Audit log export |
| **Free Tier** | Fully free (self-hosted) | 5K traces/month | 10K series, 50GB logs/traces | None | Free dev tier |
| **Entry Pricing** | Free (self-hosted) | $39/seat/month | $19/month base + usage | Enterprise (contact sales) | Free dev / Pro from $49/mo |
| **Enterprise Pricing** | $50K--100K/year (AX SaaS) | Custom (per-seat + overage) | $25K+/year committed | $78K--2.5M/year | Custom |
| **Best For** | OTel-native, open-source, RAG eval | LangChain/LangGraph ecosystem | Existing Grafana users, Kubernetes | Enterprise SOC, regulated industries | Python shops, PydanticAI users |

---

## 14. Best Practices Checklist for AI Agent Observability in Production

### Instrumentation

- [ ] **Adopt OpenTelemetry GenAI Semantic Conventions** as your telemetry standard. Avoid vendor-proprietary span formats to prevent lock-in.
- [ ] **Instrument at both the agent level and the chat/model level.** Agent spans capture business context; model spans capture token economics.
- [ ] **Use auto-instrumentation for common frameworks** (OpenLIT for K8s, Phoenix for Python, MAF's `UseOpenTelemetry()` for .NET) to reduce boilerplate.
- [ ] **Instrument guardrails as first-class spans** (`gen_ai.guardrail.check`) -- do not treat safety as an afterthought.

### Tracing

- [ ] **Implement tail sampling, not head sampling** for agent traces. You need the full trace to decide whether it is valuable to keep.
- [ ] **Keep 100% of error traces, high-cost traces, and safety violations.** Sample healthy traces at 5--10%.
- [ ] **Use hierarchical span structures** (Entry/Step or agent.run/task/tool) for long multi-step agent runs.
- [ ] **Redact PII and sensitive content at the collector level** before traces leave your network.

### Metrics

- [ ] **Track a balanced multi-dimensional scorecard**, not a single metric. At minimum: Performance (TTFT P95), Cost (cost per session), Quality (hallucination rate), and Safety (guardrail trigger rate).
- [ ] **Set dynamic-baseline alerts** (2x historical stddev, 3 consecutive collection points) to avoid alert fatigue from LLM non-determinism.
- [ ] **Implement circuit breakers** for cost (`cost_per_session > $5`), recursive loops (`steps > 20`), and quality degradation (`hallucination_score < 0.6 for > 10 min`).
- [ ] **Separate input tokens from output tokens** in cost tracking. Output tokens cost 3--5x more; optimization strategies differ.

### Evaluation

- [ ] **Run online evaluation on sampled production traffic** (LLM-as-Judge, 5--10% of traces). Do not rely on offline evaluation alone.
- [ ] **Run offline regression evaluation nightly** against a labeled dataset. Use this as a gate for model version upgrades.
- [ ] **Maintain a human annotation queue** for low-confidence LLM-as-Judge scores. Use inter-annotator agreement (Cohen's kappa) to calibrate human reviewers.
- [ ] **Evaluate at the observation level** (individual LLM calls, retrievals, tool executions), not just at the trace level. This enables compositional quality scoring.

### Compliance

- [ ] **Classify every AI agent by EU AI Act risk category** (prohibited, high, limited, minimal) and encode this as a resource attribute.
- [ ] **Maintain immutable audit trails** for at least 6 months (high-risk systems) with input/output hashing and tamper detection.
- [ ] **Log every human oversight decision** with reviewer identity and timestamp as trace spans.
- [ ] **Build an AI system inventory** (ISO 42001 §6.1) that auto-populates from deployed agent metadata.

### Operations

- [ ] **Start with the smallest possible observability stack** and expand based on actual pain points. Do not deploy the full architecture on day one.
- [ ] **Co-locate AI telemetry with infrastructure telemetry** in a unified platform to enable correlation (e.g., "TTFT degradation coincided with GPU thermal throttling").
- [ ] **Budget for observability costs separately from inference costs.** Observability infrastructure (storage, compute, bandwidth) can reach 10--20% of total AI spend.
- [ ] **Review observability ROI quarterly.** Which dashboards are actually used? Which alerts led to real incidents? Which metrics drive decisions? Cut the rest.
