# LangChain + LangGraph (2026) — Technical Deep-Dive

---

## 1. LangGraph Architecture

### 1.1 StateGraph: The Core Abstraction

LangGraph models agent workflows as **directed graphs** where computation flows through discrete nodes connected by edges. The central class is `StateGraph`, which represents a graph with a shared, typed state object that every node can read and write.

```
                        + - - - - - - - - - - - - - - - - - - - - +
                        '        STATE (shared, typed schema)        '
                        '  messages | context | intermediate_results '
                        + - - - - - - - - - - - - - - - - - - - - - +
                                      |                  ^
                                      v                  |
+--------+   edge    +---------+   edge    +----------+  |
| START  | --------> | node_A  | --------> | node_B   | -+
+--------+           +---------+           +-----+----+
                                                  |
                                   conditional edge|
                                                  v
                                      +----------+------+
                                      | True     | False |
                                      v          v       v
                                   +----+    +----+  +-----+
                                   | C  |    | D  |  | END |
                                   +----+    +----+  +-----+
```

Every node is a Python callable (sync or async) with the signature:

```python
def my_node(state: MyState, config: RunnableConfig) -> dict:
    # Read from shared state, perform logic, return partial updates
    return {"key": new_value}
```

Edges define control flow:

| Edge Type | Behavior | Example Use-Case |
|---|---|---|
| **Normal Edge** (`add_edge`) | Unconditional transition from A to B | Linear pipeline steps |
| **Conditional Edge** (`add_conditional_edges`) | Routes to one of several nodes based on a predicate | Branching on tool-call vs. final answer |
| **Entry Point** (`add_edge(START, ...)`) | Designates the first node to execute | Graph initialization |
| **Terminal Edge** (`add_edge(..., END)`) | Designates graph completion | Clean exit |
| **Parallel Fan-out** (multiple edges from one node) | All target nodes execute concurrently in the same superstep | Parallel tool calls, multi-perspective analysis |

### 1.2 How It Differs from LangChain Chains

LangChain's older **Chain** abstraction (LLMChain, SequentialChain, RouterChain) was a linear, DAG-only model. Each chain was a fixed sequence. Loops and dynamic branching were impossible without manual orchestration. LangGraph replaces this with a **cyclic graph** model:

| Dimension | LangChain Chains | LangGraph StateGraph |
|---|---|---|
| **Topology** | DAG-only (no cycles) | Arbitrary directed graphs (cycles supported) |
| **State model** | Implicit, passed step-to-step | Explicit, typed schema with reducers |
| **Persistence** | None built-in | Full checkpointing at every superstep |
| **Human-in-the-loop** | Not supported | First-class support via `interrupt()` |
| **Streaming** | Token-level only | 7 streaming modes (values, updates, messages, custom, debug, checkpoints, tasks) |
| **Multi-agent** | Manual orchestration | Native patterns (supervisor, swarm, hierarchical) |
| **Recovery** | None | Resume from any checkpoint via `thread_id` |

### 1.3 The Compilation and Execution Model

When you call `graph.compile()`, the framework performs several transformations:

```
StateGraph  ──compile()──>  CompiledStateGraph
    │                              │
    │  (1) Validate schema         │  .invoke(input, config)
    │  (2) Validate topology       │  .stream(input, config, stream_mode=...)
    │  (3) Flatten sub-graphs      │  .astream(input, config, stream_mode=...)
    │  (4) Resolve parallel        │  .get_state(config)
    │     execution groups         │  .get_state_history(config)
    │  (5) Wire checkpointer       │  .update_state(config, values)
    │  (6) Attach middleware        │
    v                              v
```

Execution follows a **Pregel-inspired** model (from Google's graph processing framework). Each **superstep** processes all ready nodes concurrently:

```
Superstep N:                     Superstep N+1:
+----------+  +----------+       +----------+
| node_A   |  | node_B   |  -->  | node_C   |
| (runs)   |  | (runs)   |       | (runs)   |
+----------+  +----------+       +----------+
     \            /                    |
      +--update--+                     v
           |                     Checkpoint written
           v                     (if checkpointer set)
     State merged via
     per-key reducers
```

Nodes within the same superstep run in parallel (using `asyncio.gather` or thread pool based on whether they are sync/async). After all parallel nodes return, their partial state updates are merged using **per-key reducer functions**, then the next superstep begins.

### 1.4 Sub-Graphs

LangGraph supports composing graphs hierarchically using **sub-graphs**. A sub-graph appears as a single node to the parent graph but contains its own internal nodes, edges, and state:

```python
# Define a sub-graph
subgraph_builder = StateGraph(SubState)
subgraph_builder.add_node("analyze", analyze_node)
subgraph_builder.add_node("summarize", summarize_node)
subgraph_builder.add_edge(START, "analyze")
subgraph_builder.add_edge("analyze", "summarize")
subgraph_builder.add_edge("summarize", END)
compiled_subgraph = subgraph_builder.compile()

# Compose into parent
parent_builder = StateGraph(ParentState)
parent_builder.add_node("preprocess", preprocess_node)
parent_builder.add_node("deep_analysis", compiled_subgraph)  # sub-graph as node
parent_builder.add_node("report", report_node)
parent_builder.add_edge(START, "preprocess")
parent_builder.add_edge("preprocess", "deep_analysis")
parent_builder.add_edge("deep_analysis", "report")
parent_builder.add_edge("report", END)
```

**State namespacing** allows sub-graphs to operate on a scoped portion of the parent state, using a key prefix. This prevents key collisions between sub-graph and parent state.

---

## 2. State Management

### 2.1 Typed State Schemas

LangGraph supports three schema definition styles:

**TypedDict** (most common, lightest weight):

```python
from typing import TypedDict, Annotated
from langgraph.graph.message import add_messages

class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    query: str
    search_results: Annotated[list[Document], operator.add]
    final_answer: str
    iteration_count: int
```

**Pydantic BaseModel** (validation, defaults, serialization):

```python
from pydantic import BaseModel, Field

class AgentState(BaseModel):
    messages: Annotated[list[BaseMessage], add_messages] = Field(default_factory=list)
    query: str = ""
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    metadata: dict = Field(default_factory=dict)
```

**Dataclass** (intermediate weight):

```python
from dataclasses import dataclass, field

@dataclass
class AgentState:
    messages: Annotated[list[BaseMessage], add_messages] = field(default_factory=list)
    status: str = "pending"
    error_count: int = 0
```

### 2.2 Reducer Functions

When multiple nodes write to the same state key (e.g., in parallel supersteps), the **reducer** dictates how values merge. The signature is `(existing_value, new_value) -> merged_value`.

| Reducer | Behavior | Use-Case |
|---|---|---|
| **Default (overwrite)** | New value replaces old value | Status flags, current step |
| `operator.add` | Concatenate / numeric addition | Accumulating search results, counters |
| `add_messages` | Smart message merge (same-ID messages replace) | Chat history (the most commonly used) |
| **Custom reducer** | Arbitrary merge logic | Domain-specific aggregation |

**Custom reducer example:**

```python
def merge_dicts(left: dict, right: dict) -> dict:
    """Deep-merge second dict into the first."""
    result = left.copy()
    result.update(right)
    return result

class State(TypedDict):
    metadata: Annotated[dict, merge_dicts]
```

### 2.3 State Persistence Backends

| Backend | Import Path | Use-Case | Notes |
|---|---|---|---|
| **MemorySaver** | `langgraph.checkpoint.memory.MemorySaver` | Development, notebooks | Data lost on restart; not for production |
| **SqliteSaver** | `langgraph.checkpoint.sqlite.SqliteSaver` | Single-server production | Persistent file; ~2 MB per 1000 checkpoints with lean state |
| **PostgresSaver** | `langgraph.checkpoint.postgres.PostgresSaver` | Multi-instance, high-availability | Supports connection pooling, async; required for horizontal scaling |
| **RedisSaver** | `langgraph.checkpoint.redis.RedisSaver` | Low-latency, ephemeral | Sub-millisecond reads; pair with Postgres for durability |
| **MongoDBSaver** | `langgraph.checkpoint.mongodb.MongoDBSaver` | Document-oriented workloads | Native JSON storage; good for deeply nested state |

**Delta Channels (LangGraph v1.2, May 2026)** dramatically reduce checkpoint storage for long-running agents. Instead of storing a full snapshot at every step, DeltaChannel stores only the diff between steps:

```python
from langgraph.channels.delta import DeltaChannel

class LongRunningAgentState(TypedDict):
    messages: Annotated[list[BaseMessage], DeltaChannel(reducer=add_messages, snapshot_frequency=50)]
    files: Annotated[list[str], DeltaChannel(reducer=operator.add, snapshot_frequency=100)]
```

For a 200-turn coding agent, checkpoint storage drops from **5.3 GB to 129 MB** (a 41x reduction). Full snapshots are written every K steps to ensure bounded recovery time.

---

## 3. Checkpointing & Time Travel

### 3.1 How Checkpointing Works

Every time a superstep completes, LangGraph creates a **checkpoint** — a snapshot of the full graph state at that point in execution. The checkpointer is attached at compile time:

```python
from langgraph.checkpoint.postgres import PostgresSaver

checkpointer = PostgresSaver.from_conn_string("postgresql://...")
app = graph.compile(checkpointer=checkpointer)

config = {"configurable": {"thread_id": "user-session-42"}}
app.invoke({"query": "Research quantum computing"}, config)
```

**Checkpoint lifecycle:**

```
invoke() called
  │
  ├─ Checkpoint 0: before superstep 1  (empty initial state)
  ├─ Checkpoint 1: after superstep 1   (node_A executed)
  ├─ Checkpoint 2: after superstep 2   (node_B executed)
  ├─ Checkpoint 3: after superstep 3   (conditional → node_C)
  ├─ ...
  └─ Checkpoint N: after final superstep (graph complete)

Thread: "user-session-42"
  └─ Checkpoints: [0, 1, 2, 3, ..., N]   (ordered list, newest first in queries)
```

**Key API methods:**

```python
# Get current/latest state
state = app.get_state(config)
# state.values -> dict of current state
# state.next   -> tuple of next nodes to execute (empty if graph ended)
# state.config -> config with checkpoint_id for this snapshot
# state.metadata -> {"source": "loop", "step": 3, ...}
# state.created_at -> datetime of checkpoint creation

# List all checkpoints for a thread (like git log)
history = list(app.get_state_history(config))
for snapshot in history:
    print(f"Step {snapshot.metadata['step']}: next={snapshot.next}")

# Update state at a specific checkpoint (like git commit --amend)
app.update_state(config, values={"approved": True})
```

### 3.2 Time-Travel Debugging

Time travel lets you rewind execution to any historical checkpoint and continue from there, enabling debugging, A/B testing, and "what-if" exploration.

**Operation 1: Browse History**

```python
# Iterate through all checkpoints in reverse chronological order
for snapshot in app.get_state_history(config):
    print(f"[{snapshot.created_at}] step={snapshot.metadata['step']}")
    print(f"  State keys: {list(snapshot.values.keys())}")
    print(f"  Next nodes: {snapshot.next}")
    print(f"  Interrupts: {snapshot.interrupts}")
```

**Operation 2: Fork from Historical State**

```python
# Replay from a specific checkpoint into a NEW thread
old_checkpoint = next(h for h in app.get_state_history(config) if h.metadata["step"] == 3)
new_config = old_checkpoint.config.copy()
new_config["configurable"]["thread_id"] = "forked-thread-43"
app.invoke(None, new_config)  # Resume from that exact state
```

**Operation 3: Modify Past State**

```python
# Inject corrected state at a checkpoint, then continue
app.update_state(config, values={"query": "corrected query text"})
app.invoke(None, config)  # Continue from modified state
```

### 3.3 The Command Primitive

`Command` is the typed primitive for updating state and controlling flow within a node, introduced in LangGraph v0.2 and refined through v1.x:

```python
from langgraph.types import Command

def my_node(state: State) -> Command[Literal["next_node", "other_node"]]:
    if some_condition:
        return Command(
            update={"key": "value"},
            goto="next_node"
        )
    else:
        return Command(
            update={"key": "other_value"},
            goto="other_node"
        )

# In a sub-graph, Command.PARENT navigates the parent graph
def handoff_node(state: State) -> Command:
    return Command(
        goto="billing_agent",
        update={"current_agent": "billing"},
        graph=Command.PARENT  # Navigate in parent graph's namespace
    )
```

---

## 4. Human-in-the-Loop

### 4.1 Interrupt Patterns

LangGraph provides three mechanisms to pause execution for human intervention:

| Mechanism | Scope | When Configured |
|---|---|---|
| `interrupt_before` | Stop before specific nodes | Compile time (static) |
| `interrupt_after` | Stop after specific nodes | Compile time (static) |
| `interrupt()` | Pause mid-node with a prompt | Runtime (dynamic, inside node) |

**Static interrupts (compile-time):**

```python
app = graph.compile(
    checkpointer=checkpointer,
    interrupt_before=["send_email", "execute_transaction"],  # approval gates
    interrupt_after=["fetch_sensitive_data"]                   # audit review
)
```

**Dynamic interrupts (runtime):**

```python
def sensitive_operation(state: State) -> dict:
    # Pause execution and surface a value to the human
    approval = interrupt({
        "question": f"Approve deleting {state['record_count']} records?",
        "details": state['records_to_delete'],
        "options": ["approve", "reject", "modify"]
    })
    if approval == "approve":
        return {"status": "deleting"}
    elif approval == "modify":
        return {"status": "awaiting_modification"}
    else:
        return {"status": "cancelled"}
```

### 4.2 How the Graph Pauses and Resumes

```
Client                          LangGraph Runtime                   Human Operator
  │                                    │                                │
  ├─ stream(input, config) ──────────> │                                │
  │                                    ├─ Checkpoint 0 (before start)   │
  │                                    ├─ Execute node_A                │
  │                                    ├─ Checkpoint 1 (after node_A)   │
  │                                    ├─ Execute node_B                │
  │                                    │   → interrupt("Approve?")      │
  │                                    ├─ Checkpoint 2 (interrupt)      │
  │  <── raises GraphInterrupt ─────── ┤                                │
  │                                    │                                │
  │  "Paused. Awaiting approval."      │                                │
  │                                                                     │
  │  ────────────── Human reviews state, makes decision ──────────────> │
  │                                                                     │
  │  stream(Command(resume="approve"), config) ────────────────────────>│
  │                                    ├─ Resume from Checkpoint 2      │
  │                                    ├─ Execute remaining nodes       │
  │                                    ├─ Final checkpoint              │
  │  <── stream chunks ────────────────┤                                │
```

### 4.3 Dynamic Breakpoints

Since LangGraph v1.x, you can add and remove breakpoints at runtime via config:

```python
# Dynamically add a breakpoint for the next invocation only
config["configurable"]["interrupt_before"] = ["risky_operation"]
app.invoke(input, config)
```

### 4.4 Approval Workflow — Complete Example

```python
from typing import TypedDict, Annotated, Literal
from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import interrupt, Command

class ApprovalState(TypedDict):
    draft: str
    approved: bool
    sent: bool

def compose_draft(state: ApprovalState) -> dict:
    # In a real system, LLM generates the draft
    return {"draft": "Generated marketing email content...", "approved": False}

def human_review(state: ApprovalState) -> dict:
    decision = interrupt({
        "action": "review_draft",
        "draft": state["draft"],
        "question": "Approve, reject, or edit this draft?"
    })
    return {"approved": decision == "approve"}

def route_after_review(state: ApprovalState) -> Literal["send_email", "revise_draft"]:
    return "send_email" if state["approved"] else "revise_draft"

def send_email(state: ApprovalState) -> dict:
    # Actually dispatch the email
    return {"sent": True}

def revise_draft(state: ApprovalState) -> dict:
    feedback = interrupt({
        "action": "provide_feedback",
        "question": "What changes are needed?"
    })
    return {"draft": f"{state['draft']}\n\nRevised per: {feedback}"}

builder = StateGraph(ApprovalState)
builder.add_node("compose", compose_draft)
builder.add_node("review", human_review)
builder.add_node("send_email", send_email)
builder.add_node("revise", revise_draft)
builder.add_edge(START, "compose")
builder.add_edge("compose", "review")
builder.add_conditional_edges("review", route_after_review)
builder.add_edge("send_email", END)
builder.add_edge("revise", "review")  # Loop back for re-review

app = builder.compile(checkpointer=MemorySaver())

# Client-side usage
config = {"configurable": {"thread_id": "email-workflow-1"}}

# Start — will pause at the review step
try:
    for event in app.stream({"draft": "", "approved": False, "sent": False}, config):
        print(event)
except Exception as e:
    pass  # GraphInterrupt raised

# Human operator inspects state
state = app.get_state(config)
print(f"Draft: {state.values['draft']}")

# Resume with approval
app.invoke(Command(resume="approve"), config)
print("Email sent!" if app.get_state(config).values["sent"] else "Not sent")
```

---

## 5. Streaming Modes

### 5.1 Mode Reference

LangGraph v1.1+ (2026) exposes 7 streaming modes via the `stream_mode` parameter. In `v2` mode, each streamed chunk is a typed `StreamPart` dict with `type` and `data` keys.

| Mode | Emits | Best For | Data Shape (`v2`) |
|---|---|---|---|
| **`values`** | Full state after each superstep | Debugging, full-context consumers | `{"type": "values", "data": <full state dict>}` |
| **`updates`** | Only the keys updated by each node | Real-time dashboards, delta consumers | `{"type": "updates", "data": {"node_name": {"key": "new_value"}}}` |
| **`messages`** | LLM tokens as they stream, with metadata | Chat UIs, token-by-token rendering | `{"type": "messages", "data": (AIMessageChunk, metadata_dict)}` |
| **`custom`** | User-defined data via `StreamWriter` | Progress bars, custom telemetry | `{"type": "custom", "data": <whatever you wrote>}` |
| **`debug`** | Both `checkpoints` and `tasks` events | Full observability during development | `{"type": "debug", "data": ...}` |
| **`checkpoints`** | Checkpoint creation events | Audit trail, state logging | `{"type": "checkpoints", "data": {"checkpoint_id": "...", "step": N}}` |
| **`tasks`** | Task start/end/error events | Performance monitoring | `{"type": "tasks", "data": {"task_id": "...", "status": "start"}}` |

### 5.2 Mode Selection Guide

```
Bandwidth Efficiency (least → most):
  values (12KB/step) ← 22KB avg in 100-node workflow
  updates (2.3KB/step) ← only deltas
  messages (tokens only) ← 76% less than values in LLM-heavy workflows
```

**Combining modes:**

```python
# Stream multiple modes simultaneously
for part in app.stream(
    input,
    config,
    stream_mode=["messages", "updates", "custom"],
    version="v2"
):
    if part["type"] == "messages":
        chunk, metadata = part["data"]
        print(chunk.content, end="", flush=True)
    elif part["type"] == "updates":
        print(f"\n[Node completed: {list(part['data'].keys())}]")
    elif part["type"] == "custom":
        print(f"[Custom: {part['data']}]")
```

### 5.3 Custom Streaming

```python
from langgraph.config import get_stream_writer

def my_node(state: State) -> dict:
    writer = get_stream_writer()
    writer("Starting analysis...")
    # ... do work ...
    writer({"progress": 50, "message": "Halfway done"})
    # ... more work ...
    writer({"progress": 100, "message": "Analysis complete"})
    return {"result": "..."}
```

### 5.4 Streaming with Concurrent Nodes

When multiple nodes run in the same superstep, `values` mode emits the merged state once after all nodes complete. `updates` mode emits each node's partial update as it finishes. `messages` mode interleaves tokens from concurrent LLM calls tagged with their source node.

---

## 6. Multi-Agent Patterns

### 6.1 Supervisor Agent

The **Supervisor pattern** uses a central router agent that delegates to specialized sub-agents. All communication flows through the supervisor, forming a hub-and-spoke (star) topology.

```
                    ┌──────────────┐
                    │  SUPERVISOR  │
                    │  (router LLM)│
                    └──┬───┬───┬──┘
              ┌────────┘   │   └────────┐
              v            v            v
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Billing  │ │  Tech    │ │ Account  │
        │ Agent    │ │  Support │ │  Agent   │
        │ (tools)  │ │  Agent   │ │ (tools)  │
        └──────────┘ └──────────┘ └──────────┘
```

```python
from typing import TypedDict, Annotated, Literal
from langgraph.graph import StateGraph, START, END

class RouterDecision(BaseModel):
    """Structured output for the supervisor's routing decision."""
    next_agent: Literal["billing", "tech_support", "account", "FINISH"]
    reasoning: str

class SupervisorState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    next_agent: str

def supervisor_node(state: SupervisorState) -> dict:
    """LLM-powered router that decides which agent to call next."""
    llm = ChatOpenAI(model="gpt-4o")
    structured_llm = llm.with_structured_output(RouterDecision)
    response = structured_llm.invoke([
        SystemMessage(content="You are a supervisor routing users to the right specialist..."),
        *state["messages"]
    ])
    return {"next_agent": response.next_agent}

def billing_agent(state: SupervisorState) -> dict:
    llm = ChatOpenAI(model="gpt-4o").bind_tools([lookup_invoice, process_refund])
    response = llm.invoke(state["messages"])
    return {"messages": [response]}

def tech_support_agent(state: SupervisorState) -> dict:
    llm = ChatOpenAI(model="gpt-4o").bind_tools([search_knowledge_base, create_ticket])
    response = llm.invoke(state["messages"])
    return {"messages": [response]}

def account_agent(state: SupervisorState) -> dict:
    llm = ChatOpenAI(model="gpt-4o").bind_tools([update_profile, reset_password])
    response = llm.invoke(state["messages"])
    return {"messages": [response]}

def route_to_agent(state: SupervisorState) -> str:
    return state["next_agent"]

builder = StateGraph(SupervisorState)
builder.add_node("supervisor", supervisor_node)
builder.add_node("billing", billing_agent)
builder.add_node("tech_support", tech_support_agent)
builder.add_node("account", account_agent)

builder.add_edge(START, "supervisor")
builder.add_conditional_edges("supervisor", route_to_agent, {
    "billing": "billing",
    "tech_support": "tech_support",
    "account": "account",
    "FINISH": END,
})
# All agents loop back to supervisor for next routing decision
builder.add_edge("billing", "supervisor")
builder.add_edge("tech_support", "supervisor")
builder.add_edge("account", "supervisor")

app = builder.compile(checkpointer=PostgresSaver.from_conn_string("..."))
```

**Performance characteristics (2026 benchmarks):**
- Single-domain routing latency: ~4.2s
- Cross-domain handoff latency: ~9.1s
- Routing accuracy: 94%
- Average tokens/request: ~2,800

### 6.2 Hierarchical Teams (Manager → Workers)

```
                        ┌──────────────┐
                        │   TOP-LEVEL  │
                        │   MANAGER    │
                        └──┬───────┬───┘
              ┌────────────┘       └────────────┐
              v                                  v
     ┌────────────────┐                ┌────────────────┐
     │  RESEARCH TEAM │                │  WRITING TEAM  │
     │  (sub-graph)   │                │  (sub-graph)   │
     │ ┌──────┐┌─────┐│                │ ┌──────┐┌─────┐│
     │ │WebSrch││Analy││                │ │Draft ││Edit ││
     │ └──────┘└─────┘│                │ └──────┘└─────┘│
     └────────────────┘                └────────────────┘
```

Each team is a compiled sub-graph. The top-level manager delegates tasks to teams, which internally coordinate their own sub-agents.

```python
# Team as sub-graph (defined elsewhere)
research_team = create_research_team()   # returns CompiledStateGraph
writing_team = create_writing_team()     # returns CompiledStateGraph

class ManagerState(TypedDict):
    task: str
    research_output: str
    final_document: str

builder = StateGraph(ManagerState)
builder.add_node("manager", manager_node)
builder.add_node("research", research_team)   # sub-graph
builder.add_node("writing", writing_team)     # sub-graph
builder.add_edge(START, "manager")
builder.add_conditional_edges("manager", route_phase, {
    "research": "research",
    "writing": "writing",
    "done": END,
})
builder.add_edge("research", "manager")  # Return to manager after research
builder.add_edge("writing", END)
```

### 6.3 Network / Decentralized

The **Network pattern** removes the central supervisor. Each agent can communicate directly with peers through a shared state bus. Agents use **handoff tools** to transfer control.

```python
from langgraph.types import Command

def make_handoff_tool(target_agent: str, description: str):
    @tool(f"transfer_to_{target_agent}", description=description)
    def handoff(reason: str) -> Command:
        """Transfer control to a peer agent."""
        return Command(
            goto=target_agent,
            update={
                "current_agent": target_agent,
                "messages": [AIMessage(content=f"Transferring to {target_agent}: {reason}")]
            },
            graph=Command.PARENT,
        )
    return handoff

# Each agent has handoff tools to all peers it can transfer to
billing_agent = create_react_agent(
    llm,
    tools=[lookup_invoice, process_refund,
           make_handoff_tool("tech_support", "Transfer to technical support"),
           make_handoff_tool("account", "Transfer to account services")]
)
```

### 6.4 Swarm

The **Swarm pattern** (available via the `langgraph-swarm` package since v1.0.1) extends the network pattern with dynamic agent spawning and handoff:

```python
from langgraph_swarm import create_swarm

swarm = create_swarm(
    agents=[flight_assistant, hotel_assistant, car_rental_assistant],
    default_active_agent="triage",
    state_schema=TravelState,
)

# Invoke like a normal compiled graph
result = swarm.invoke(
    {"messages": [HumanMessage(content="Book a flight to Tokyo and a hotel nearby")]},
    config
)
```

**Swarm vs. Supervisor trade-offs (2026 benchmarks):**

| Metric | Supervisor | Swarm |
|---|---|---|
| Single-domain latency | 4.2s | **2.8s** (34% faster) |
| Cross-domain latency | 9.1s | **5.4s** (41% faster) |
| LLM calls (single domain) | 2 (route + expert) | 1 (expert only) |
| Routing accuracy | **94%** | 91% |
| Avg tokens/request | 2,800 | **1,900** (32% less) |
| Use when | Accuracy > speed | Speed > accuracy |

---

## 7. Tool Integration

### 7.1 The `@tool` Decorator

```python
from langchain.tools import tool

@tool
def search_web(query: str, num_results: int = 5) -> str:
    """Search the web for current information on any topic.

    Args:
        query: The search query string.
        num_results: Number of results to return (max 10).
    """
    # Implementation
    return json.dumps(results)
```

The decorator auto-generates:
- **Name**: From function name (`search_web`)
- **Description**: From docstring (used by the LLM to decide when to invoke)
- **Schema**: From function signature + docstring Args (injected into the LLM's function-calling schema)

### 7.2 StructuredTool

For tools that need explicit schema control:

```python
from langchain.tools import StructuredTool
from pydantic import BaseModel, Field

class WeatherInput(BaseModel):
    location: str = Field(description="City name, e.g., 'San Francisco'")
    units: Literal["celsius", "fahrenheit"] = Field(default="celsius")

def get_weather(location: str, units: str = "celsius") -> str:
    """Get current weather for a location."""
    return f"Weather in {location}: 22°{'C' if units == 'celsius' else 'F'}"

weather_tool = StructuredTool.from_function(
    func=get_weather,
    name="get_weather",
    description="Get current weather conditions for any city",
    args_schema=WeatherInput,
)
```

### 7.3 MCP Tool Adapter

The **Model Context Protocol (MCP)** is an open standard for connecting LLMs to external tools and data sources. LangChain's `langchain-mcp-adapters` package converts MCP tools into native LangChain tools:

```python
from langchain_mcp_adapters.client import MultiServerMCPClient

async with MultiServerMCPClient({
    "filesystem": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
    },
    "postgres": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-postgres", conn_string],
    },
}) as client:
    # All MCP tools now available as LangChain tools
    tools = client.get_tools()

    agent = create_react_agent(llm, tools)
    result = await agent.ainvoke({"messages": [HumanMessage(content="...")]})
```

Alternatively, load tools from a single server:

```python
from langchain_mcp_adapters.tools import load_mcp_tools

tools = await load_mcp_tools(session)  # Returns list[BaseTool]
```

**Important:** MCP tools return both `content` (visible to the model) and `structuredContent` (stored in `ToolMessage.artifact` for application use). This separation keeps JSON payloads out of the LLM context while making them available for downstream processing.

### 7.4 The LangChain Tool Ecosystem (3,000+ Integrations)

LangChain's integration ecosystem spans:
- **Search**: Google, Bing, Tavily, Brave, SerpAPI, DuckDuckGo
- **Databases**: SQL (Postgres, MySQL, SQLite), Vector stores (Pinecone, Weaviate, Chroma, Qdrant, Milvus, FAISS)
- **APIs**: GitHub, Jira, Slack, Gmail, Google Drive, Salesforce, Stripe
- **Code**: Python REPL, Shell, Code interpreters
- **Files**: Filesystem operations, PDF parsing, CSV, JSON
- **Multimodal**: Image generation (DALL-E, Stable Diffusion), vision, audio transcription

### 7.5 Binding Tools to Agents

```python
# Option 1: Bind to LLM directly
llm = ChatOpenAI(model="gpt-4o").bind_tools([search_web, get_weather, calculator])

# Option 2: Use create_react_agent (high-level)
from langgraph.prebuilt import create_react_agent
agent = create_react_agent(llm, tools=[search_web, get_weather, calculator])

# Option 3: Manual tool node (low-level)
from langgraph.prebuilt import ToolNode
tool_node = ToolNode([search_web, get_weather, calculator])
builder.add_node("tools", tool_node)
```

---

## 8. LangGraph Platform

### 8.1 Open-Source vs. Commercial

LangChain maintains a clear split:

| Component | License | Description |
|---|---|---|
| **`langgraph`** (Python) / `@langchain/langgraph` (JS) | MIT / Apache 2.0 | Graph runtime, state, checkpointing, streaming |
| **`langgraph.checkpoint.*`** | MIT / Apache 2.0 | Checkpointer backends (Memory, SQLite, Postgres) |
| **LangGraph Platform** | Commercial (LangSmith) | Deploy-as-API, managed server, cron, webhooks, Assistants API |
| **LangSmith** | Commercial (free tier available) | Observability, evaluation, tracing, annotation |

### 8.2 Deploy-as-API (LangGraph Server)

The LangGraph Server wraps a compiled graph as a production HTTP API:

```bash
# Define your graph in langgraph.json
# {
#   "graphs": {
#     "customer_support": "./agent.py:app"
#   }
# }

# Deploy
langgraph up              # Local development server on :2024
langgraph deploy          # Deploy to LangSmith Cloud / Hybrid / Self-Hosted
```

**Deployment options:**

| Option | Description |
|---|---|
| **LangSmith Cloud** | Fully managed, includes cron, webhooks, auth, rate limiting |
| **Hybrid** | Control plane managed by LangChain; data plane in your Kubernetes cluster |
| **Self-Hosted (Full Platform)** | Entire stack on your own K8s via Helm chart |
| **Standalone Server** | Lightweight Docker container with external PostgreSQL + Redis |

### 8.3 Assistants API

The Assistants API provides a higher-level abstraction over raw graphs, with managed configuration, versioning, and multi-tenancy:

```python
from langgraph_sdk import get_client

client = get_client(url="https://your-deployment.langchain.com")

# Create an assistant (a configured instance of a graph)
assistant = await client.assistants.create(
    graph_id="customer_support",
    config={"model": "gpt-4o", "temperature": 0.7},
    metadata={"department": "support", "tier": "premium"},
)

# Invoke via API
run = await client.runs.create(
    assistant_id=assistant["assistant_id"],
    input={"messages": [{"role": "user", "content": "I need a refund"}]},
)
```

### 8.4 Background Runs, Cron Jobs, and Webhooks

**Cron jobs** schedule recurring graph executions:

```python
# Daily summary at 9 AM EST
cron = await client.crons.create(
    assistant_id="my-agent",
    schedule="0 9 * * *",
    input={"task": "Generate daily report"},
    on_run_completed="keep",
    timezone="America/New_York",
)

# Stateful cron (always runs on the same persistent thread)
cron = await client.crons.create_for_thread(
    thread_id="persistent-monitor-thread",
    assistant_id="monitor-agent",
    schedule="*/15 * * * *",  # Every 15 minutes
    input={"action": "check_status"},
)
```

**Webhooks** trigger graph execution from external events:

```python
# Webhook handling is built into the platform runtime
# External system POSTs to https://your-deployment/webhook/{webhook_id}
# The graph receives the webhook payload as input
```

---

## 9. LangSmith Observability

### 9.1 Tracing Architecture

LangSmith captures every LLM call, tool execution, chain step, and graph node transition as a **trace**. Each trace is a tree of **runs**:

```
Trace: user-query-8472
├── Run: graph.invoke() [duration: 12.3s]
│   ├── Run: agent_node [duration: 3.1s]
│   │   ├── Run: ChatOpenAI.chat.completions.create [tokens: 450 in, 120 out]
│   │   └── Run: search_web tool [duration: 1.2s]
│   ├── Run: should_continue [duration: 0.05s]
│   ├── Run: tools_node [duration: 2.4s]
│   │   ├── Run: search_web [duration: 1.1s]
│   │   └── Run: calculator [duration: 0.03s]
│   └── Run: agent_node [duration: 2.1s]
│       └── Run: ChatOpenAI.chat.completions.create [tokens: 620 in, 80 out]
```

**SmithDB (2026):** A new Rust-based database built on Apache DataFusion and Vortex, delivering P50 trace tree loads in 92ms and single-run loads in 71ms — up to 15x faster than the previous storage backend.

### 9.2 Key LangSmith Features

| Feature | Description |
|---|---|
| **Tracing** | Automatic capture of every run with inputs, outputs, latency, token counts, and metadata |
| **Evaluation Suite** | 30+ evaluator templates: correctness, safety, trajectory, user behavior, multimodal |
| **Datasets** | Curated test cases with expected outputs; can be built from production traces |
| **Annotation Queues** | Human review pipelines for rating outputs, creating ground truth |
| **Feedback Collection** | Thumbs up/down, star ratings, custom feedback from end users |
| **Experiment Tracking** | Side-by-side comparison of prompt/model/config variants |
| **Insights Agent** | AI-powered analysis of traces: clusters failures, identifies patterns, suggests improvements |
| **Polly Debugger** | Interactive debugging with breakpoints and state inspection |
| **LangSmith Engine (2026)** | Autonomous agent that watches production traces, clusters failures, identifies root causes, and opens PRs with fixes |
| **Online Evaluators** | Custom evaluators that run on every production trace in real-time |

### 9.3 Programmatic Usage

```python
# Set environment variables to enable tracing
# LANGCHAIN_TRACING_V2=true
# LANGCHAIN_API_KEY=ls_...
# LANGCHAIN_PROJECT=my-project

# Or programmatically
from langsmith import Client

client = Client()

# Create a dataset from production traces
dataset = client.create_dataset(
    dataset_name="refund-scenarios",
    description="Test cases for refund processing",
)
for run in client.list_runs(project_name="support-agent", filter="eq(feedback_score, 1)"):
    client.create_example(
        inputs=run.inputs,
        outputs=run.outputs,
        dataset_id=dataset.id,
    )

# Run an evaluation
from langsmith.evaluation import evaluate

results = evaluate(
    lambda inputs: agent.invoke(inputs),
    data=dataset.name,
    evaluators=[
        "correctness",                    # built-in template
        "safety",                         # built-in template
        my_custom_evaluator,              # custom callable
    ],
    experiment_prefix="model-v2-test",
)
```

---

## 10. Deep Agents

**Deep Agents** (v0.6 as of May 2026) is LangChain's agent harness for autonomous, long-running agents. It extends LangGraph with:

### 10.1 Task Planning

Deep Agents decompose complex goals into subtask trees:

```python
from deep_agents import DeepAgent

agent = DeepAgent(
    model="claude-sonnet-4-20250514",
    tools=[search_web, read_file, write_file, execute_code],
    planning_strategy="tree_of_thought",  # or "chain_of_thought", "reactive"
)

result = agent.invoke({
    "goal": "Build a full-stack TODO app with React frontend and FastAPI backend",
    "context": "Target: single-page app, SQLite database, Docker deployment"
})
# Deep Agent internally:
# 1. Plans: [design_schema, build_backend, build_frontend, dockerize, test]
# 2. Spawns sub-agents for independent subtasks
# 3. Aggregates results
```

### 10.2 Sub-Agent Spawning

Deep Agents can dynamically spawn specialized sub-agents for subtasks, each with scoped tools and memory:

```python
# The agent internally creates sub-agents like:
# SubAgent("backend-developer", tools=[write_file, execute_pytest, search_fastapi_docs])
# SubAgent("frontend-developer", tools=[write_file, execute_npm, search_react_docs])
```

### 10.3 Long-Term Memory

Deep Agents maintain three memory tiers:

| Tier | Storage | Lifetime | Content |
|---|---|---|---|
| **Working Memory** | Graph state (in-memory + checkpoint) | Single task | Current plan, intermediate results, context |
| **Episodic Memory** | Vector store | Cross-session | Past task summaries, lessons learned, user preferences |
| **Semantic Memory** | Vector store + Knowledge graph | Permanent | Domain knowledge, code patterns, documentation |

### 10.4 Context Management

Deep Agents use four scheduling strategies to manage finite context windows:

| Strategy | Description |
|---|---|
| **Write** | Hierarchical memory writing — only important information persisted |
| **Select** | Intelligent context injection — retrieve only relevant history |
| **Update** | Incremental learning — update existing knowledge rather than duplicate |
| **Purge** | Resource cleanup — evict stale context, compress verbose tool outputs |

### 10.5 GPU-Accelerated Compute Sandbox

Deep Agents v0.6 integrates with NVIDIA CUDA-X for GPU-accelerated sandbox execution. The sandbox runs in **NVIDIA OpenShell**, a secure runtime that isolates agent code execution with policy-based guardrails:

- Compile and run code in an isolated environment
- GPU-accelerated data processing (pandas, numpy, RAPIDS)
- Policy-based resource limits (CPU, memory, GPU, network, filesystem)
- Automatic cleanup after task completion

---

## 11. NVIDIA Integration (2026)

### 11.1 The Partnership

In March 2026, LangChain and NVIDIA announced a strategic partnership to build an enterprise agentic AI platform. LangChain joined the **Nemotron Coalition**, NVIDIA's initiative for advancing frontier open AI models.

### 11.2 Nemotron Models via NIM Microservices

| Model | Architecture | Active Parameters | Deployment |
|---|---|---|---|
| **Nemotron 3 Nano** | Mixture of Experts | 30B total / 3B active | Single consumer GPU |
| **Nemotron 3 Super** | Mixture of Experts | ~100B total / ~10B active | Single enterprise GPU (A100/H100) |
| **Nemotron 3 Ultra** | Mixture of Experts | ~500B total / ~50B active | Multi-GPU (expected H1 2026) |

**NIM (NVIDIA Inference Microservices)** provides GPU-optimized inference containers:

```python
from langchain_nvidia_ai_endpoints import ChatNVIDIA

# NIM-accelerated model endpoint
llm = ChatNVIDIA(
    model="nvidia/nemotron-3-super",
    nvidia_api_key="nvapi-...",
    temperature=0.7,
)

agent = create_react_agent(llm, tools=[...])
```

**Performance:** NIM delivers up to **2.6x higher throughput** compared to standard deployment configurations across cloud, on-premises, and hybrid environments.

### 11.3 Parallel Execution Optimization

At compile time, LangGraph identifies independent nodes and marks them for parallel execution. With NVIDIA's speculative execution optimization:

```
Conditional edge:
                   ┌── path_A (agent responds to user)
  agent_node ──────┤
                   └── path_B (agent calls a tool)

Instead of waiting for the LLM to decide which path:
  → Both path_A and path_B begin executing speculatively
  → Once the LLM's routing decision arrives, the wrong path is discarded
  → Correct path continues from where it already started
```

This is particularly effective on NVIDIA GPUs where parallel inference of multiple branches adds minimal latency overhead.

### 11.4 NeMo Guardrails

NeMo Guardrails provides content safety as a LangGraph integration:

```python
from nemoguardrails import RailsConfig
from nemoguardrails.integrations.langchain import RunnableRails

config = RailsConfig.from_path("./config")
guardrails = RunnableRails(config)

# Wrap the agent with safety guardrails
safe_agent = guardrails | agent

# Guardrails intercept both user input and model output
# - Block toxic/jailbreak prompts
# - Prevent hallucinated dangerous instructions
# - Enforce topical boundaries ("stay on topic")
# - Redact PII before it reaches the LLM
```

### 11.5 OpenShell for Sandboxed Execution

OpenShell is a secure runtime that sandboxes autonomous, self-evolving agents:

```python
# Deep Agents + OpenShell integration
agent = DeepAgent(
    model="nvidia/nemotron-3-super",
    tools=[search_web, write_file, execute_code],
    sandbox="openshell",  # Uses NVIDIA OpenShell runtime
    sandbox_policy={
        "max_cpu_cores": 4,
        "max_memory_gb": 16,
        "max_gpu_memory_gb": 8,
        "network": "internal_only",
        "filesystem": "isolated",
        "max_runtime_seconds": 300,
    }
)
```

---

## 12. Production Patterns

### 12.1 Temporal + LangGraph Dual-Layer Architecture

The emerging production standard combines two frameworks at different layers:

```
┌──────────────────────────────────────────────────────────┐
│                    TEMPORAL LAYER                         │
│  Durable execution, retries, failure recovery, versioning │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Temporal Workflow                               │   │
│  │  ┌────────────────────────────────────────────┐  │   │
│  │  │        LANGGRAPH LAYER                     │  │   │
│  │  │  Prompt mgmt, tool calling, memory, loops  │  │   │
│  │  │  ┌─────────┐  ┌─────────┐  ┌─────────┐    │  │   │
│  │  │  │ Node A  │─>│ Node B  │─>│ Node C  │    │  │   │
│  │  │  └─────────┘  └─────────┘  └─────────┘    │  │   │
│  │  └────────────────────────────────────────────┘  │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

```python
from temporalio import workflow, activity

@workflow.defn
class AgentOrchestratorWorkflow:
    @workflow.run
    async def run(self, task: str, thread_id: str) -> dict:
        # LangGraph handles the LLM logic within a Temporal activity
        result = await workflow.execute_activity(
            run_langgraph_agent,
            args=[task, thread_id],
            start_to_close_timeout=timedelta(minutes=30),
            retry_policy=RetryPolicy(maximum_attempts=3),
        )
        return result

@activity.defn
async def run_langgraph_agent(task: str, thread_id: str) -> dict:
    config = {"configurable": {"thread_id": thread_id}}
    return await app.ainvoke({"task": task}, config)
```

**Why both?** Temporal guarantees that the entire workflow completes even if infrastructure fails mid-execution. LangGraph provides the LLM-native control flow (loops, branching, memory) that Temporal's deterministic execution model cannot express naturally. Temporal handles infrastructure durability; LangGraph handles AI logic.

### 12.2 Three-Tier State Storage

```
┌──────────────────────────────────────────────────┐
│ Tier 1: HOT (Redis)                               │
│ - Active graph state for in-flight executions     │
│ - Sub-millisecond reads                           │
│ - TTL-based expiry for completed threads          │
├──────────────────────────────────────────────────┤
│ Tier 2: WARM (PostgreSQL / TimescaleDB)           │
│ - Recent checkpoints (last 30 days)               │
│ - Time-series querying for monitoring dashboards  │
│ - Full state for time-travel debugging            │
├──────────────────────────────────────────────────┤
│ Tier 3: COLD (S3 / MinIO / GCS)                   │
│ - Archived checkpoints beyond 30 days             │
│ - Compressed + partitioned by date                │
│ - Restorable for audit / compliance               │
└──────────────────────────────────────────────────┘
```

```python
# Implementation sketch
class TieredCheckpointer:
    def __init__(self, redis_url, pg_url, s3_bucket):
        self.redis = RedisSaver.from_conn_string(redis_url)
        self.postgres = PostgresSaver.from_conn_string(pg_url)
        self.cold_storage = S3ColdStorage(s3_bucket)

    async def get_tuple(self, config):
        # Try hot first
        result = await self.redis.aget_tuple(config)
        if result:
            return result
        # Fall back to warm
        result = await self.postgres.aget_tuple(config)
        if result:
            await self.redis.aput(config, result)  # Promote to hot
            return result
        # Fall back to cold
        return await self.cold_storage.get(config)

    async def put(self, config, checkpoint, metadata, new_versions):
        await self.redis.aput(config, checkpoint, metadata, new_versions)
        await self.postgres.aput(config, checkpoint, metadata, new_versions)
        # Cold storage via async batch job (not on critical path)
```

### 12.3 Fault Tolerance: Three-Level Model

```
Level 1: Model Retry
  ├── Exponential backoff on API rate limits
  ├── Fallback to cheaper/faster model on timeout
  └── Circuit breaker: 5 consecutive failures → pause + alert

Level 2: Tool Degradation
  ├── Primary tool fails → try secondary tool
  ├── Tool unavailable → return cached/generic response
  └── Annotated with degradation reason in state

Level 3: System Circuit Breaker
  ├── Global error rate > threshold → serve graceful-degradation responses
  ├── Queue depth > max → shed load (return 429 to client)
  └── Automatic recovery when health restores
```

```python
class ResilientAgentNode:
    def __init__(self, llm, tools, fallback_llm=None):
        self.llm = llm
        self.fallback_llm = fallback_llm or ChatOpenAI(model="gpt-4o-mini")
        self.failure_count = 0
        self.circuit_open = False

    async def __call__(self, state: State) -> dict:
        if self.circuit_open:
            return {"messages": [AIMessage(content="Service temporarily degraded...")]}

        try:
            response = await asyncio.wait_for(
                self.llm.ainvoke(state["messages"]),
                timeout=30.0
            )
            self.failure_count = 0
            return {"messages": [response]}
        except asyncio.TimeoutError:
            self.failure_count += 1
            if self.failure_count >= 3:
                # Level 1 fallback: switch to faster model
                response = await self.fallback_llm.ainvoke(state["messages"])
                return {"messages": [response]}
        except Exception:
            self.failure_count += 1
            if self.failure_count >= 5:
                self.circuit_open = True
            raise
```

### 12.4 Kubernetes HPA Auto-Scaling

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: langgraph-agent-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: langgraph-server
  minReplicas: 3
  maxReplicas: 50
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          averageUtilization: 80
    - type: Pods
      pods:
        metric:
          name: active_graph_threads
        target:
          type: AverageValue
          averageValue: "100"
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Percent
          value: 50
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300  # Scale down slowly to avoid thrashing
      policies:
        - type: Pods
          value: 1
          periodSeconds: 120
```

---

## 13. Key APIs with Signatures

```python
# === StateGraph ===
class StateGraph(state_schema: type[StateType], config_schema: type[ConfigType] | None = None)
    def add_node(name: str, action: Callable | CompiledStateGraph) -> Self
    def add_edge(start_key: str, end_key: str) -> Self
    def add_conditional_edges(
        source: str,
        path: Callable[[StateType], str | list[str]],
        path_map: dict[str, str] | None = None,
    ) -> Self
    def compile(
        checkpointer: BaseCheckpointSaver | None = None,
        interrupt_before: list[str] | None = None,
        interrupt_after: list[str] | None = None,
        debug: bool = False,
    ) -> CompiledStateGraph

# === CompiledStateGraph ===
class CompiledStateGraph:
    def invoke(
        input: StateType | Command | None,
        config: RunnableConfig | None = None,
        *,
        interrupt_before: list[str] | None = None,
    ) -> StateType

    def stream(
        input: StateType | Command | None,
        config: RunnableConfig | None = None,
        *,
        stream_mode: StreamMode | list[StreamMode] = "values",
        version: Literal["v1", "v2"] = "v1",
    ) -> AsyncIterator[StreamPart | tuple]

    async def astream(
        input: StateType | Command | None,
        config: RunnableConfig | None = None,
        *,
        stream_mode: StreamMode | list[StreamMode] = "values",
        version: Literal["v1", "v2"] = "v1",
    ) -> AsyncIterator[StreamPart | tuple]

    def get_state(config: RunnableConfig) -> StateSnapshot
    def get_state_history(config: RunnableConfig) -> Iterator[StateSnapshot]
    def update_state(config: RunnableConfig, values: dict) -> RunnableConfig

# === Command ===
class Command(Generic[CN]):
    def __init__(
        update: dict | None = None,
        goto: str | list[str] | None = None,
        graph: Literal["parent"] | None = None,
        resume: Any = None,
    )
    PARENT: ClassVar[Literal["parent"]] = "parent"

# === interrupt ===
def interrupt(value: Any) -> Any:
    """Pauses graph execution and surfaces `value` to the caller.
    Returns the value passed to `Command(resume=...)` upon resumption."""

# === Checkpoint Savers ===
class MemorySaver:
    def __init__(self)

class SqliteSaver:
    def __init__(self, conn: sqlite3.Connection)
    @classmethod
    def from_conn_string(cls, conn_string: str) -> SqliteSaver

class PostgresSaver:
    def __init__(self, conn: asyncpg.Connection)
    @classmethod
    def from_conn_string(cls, conn_string: str) -> PostgresSaver

# === create_react_agent ===
def create_react_agent(
    model: str | BaseChatModel,
    tools: list[BaseTool] | ToolNode,
    *,
    state_schema: type | None = None,
    prompt: SystemMessage | str | None = None,
    checkpointer: BaseCheckpointSaver | None = None,
) -> CompiledStateGraph
```

---

## 14. Complete Code Examples

### 14.1 Reflection Loop with Self-Improvement

```python
"""
A self-reflective agent that generates an answer, critiques itself,
and iterates until quality meets a threshold or max iterations reached.
"""
from typing import TypedDict, Annotated, Literal
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages

class ReflectionState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    draft: str
    critique: str
    iteration: int
    quality_score: float

SYSTEM_PROMPT = """You are a thoughtful assistant. Generate high-quality,
well-reasoned responses. After receiving critique, improve your answer."""

CRITIQUE_PROMPT = """You are a quality reviewer. Evaluate the answer for:
1. Factual accuracy  2. Completeness  3. Clarity  4. Logical flow
Provide specific, actionable critique. Also output a quality score from 0.0 to 1.0.

IMPORTANT: Output ONLY a JSON object with keys 'critique' (string) and 'quality_score' (float)."""

def generate_draft(state: ReflectionState) -> dict:
    llm = ChatOpenAI(model="gpt-4o")
    response = llm.invoke([
        SystemMessage(content=SYSTEM_PROMPT),
        *state["messages"]
    ])
    return {
        "draft": response.content,
        "messages": [response],
        "iteration": state.get("iteration", 0) + 1,
    }

def critique_draft(state: ReflectionState) -> dict:
    import json
    llm = ChatOpenAI(model="gpt-4o", response_format={"type": "json_object"})
    response = llm.invoke([
        SystemMessage(content=CRITIQUE_PROMPT),
        HumanMessage(content=f"Original question: {state['messages'][0].content}\n\nDraft:{state['draft']}")
    ])
    result = json.loads(response.content)
    return {
        "critique": result.get("critique", ""),
        "quality_score": result.get("quality_score", 0.5),
        "messages": [HumanMessage(content=f"Critique: {result.get('critique', '')}")],
    }

def should_continue(state: ReflectionState) -> Literal["generate_draft", END]:
    if state["quality_score"] >= 0.85:
        return END
    if state["iteration"] >= 5:
        return END
    return "generate_draft"

builder = StateGraph(ReflectionState)
builder.add_node("generate_draft", generate_draft)
builder.add_node("critique_draft", critique_draft)
builder.add_edge(START, "generate_draft")
builder.add_edge("generate_draft", "critique_draft")
builder.add_conditional_edges("critique_draft", should_continue)
app = builder.compile()

# Usage
result = app.invoke({
    "messages": [HumanMessage(content="Explain quantum entanglement to a high school student.")],
    "draft": "", "critique": "", "iteration": 0, "quality_score": 0.0,
})
print(f"Final quality: {result['quality_score']:.2f} after {result['iteration']} iterations")
print(result["draft"])
```

### 14.2 Supervisor Multi-Agent with Tool-Using Workers

```python
"""
A customer service system with a supervisor routing to specialized agents:
- Billing Agent: Has invoice lookup and refund processing tools
- Technical Support Agent: Has knowledge base search and ticket creation tools
"""
from typing import TypedDict, Annotated, Literal
from pydantic import BaseModel
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode

# --- Tools ---
@tool
def lookup_invoice(user_id: str, limit: int = 10) -> str:
    """Look up recent invoices for a user."""
    invoices = db.query(f"SELECT * FROM invoices WHERE user_id='{user_id}' LIMIT {limit}")
    return json.dumps(invoices, default=str)

@tool
def process_refund(invoice_id: str, reason: str) -> str:
    """Process a refund for a specific invoice."""
    refund_id = payment_gateway.refund(invoice_id, reason)
    return f"Refund processed: {refund_id}"

@tool
def search_knowledge_base(query: str) -> str:
    """Search the technical knowledge base for solutions."""
    results = vector_store.similarity_search(query, k=3)
    return "\n\n".join(r.page_content for r in results)

@tool
def create_support_ticket(user_id: str, issue: str, priority: str = "medium") -> str:
    """Create a support ticket for unresolved issues."""
    ticket_id = ticketing_system.create(user_id, issue, priority)
    return f"Ticket created: {ticket_id}"

# --- State ---
class SupportState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    current_agent: str
    resolution: str

class RoutingDecision(BaseModel):
    next_agent: Literal["billing", "technical", "FINISH"]
    reasoning: str

# --- Agent nodes ---
def supervisor(state: SupportState) -> dict:
    llm = ChatOpenAI(model="gpt-4o")
    structured_llm = llm.with_structured_output(RoutingDecision)
    decision = structured_llm.invoke([
        SystemMessage(content="""Route customer requests to the right specialist.
- 'billing': Invoices, payments, refunds, subscription changes
- 'technical': Bugs, errors, setup issues, feature questions
- 'FINISH': When the issue is fully resolved or the user has no more questions"""),
        *state["messages"]
    ])
    return {"current_agent": decision.next_agent}

def billing_agent(state: SupportState) -> dict:
    llm = ChatOpenAI(model="gpt-4o").bind_tools([lookup_invoice, process_refund])
    response = llm.invoke([
        SystemMessage(content="You are a billing specialist. Help with invoices, payments, and refunds."),
        *state["messages"]
    ])
    return {"messages": [response]}

def technical_agent(state: SupportState) -> dict:
    llm = ChatOpenAI(model="gpt-4o").bind_tools([search_knowledge_base, create_support_ticket])
    response = llm.invoke([
        SystemMessage(content="You are a technical support specialist. Diagnose and resolve technical issues."),
        *state["messages"]
    ])
    return {"messages": [response]}

def tool_router(state: SupportState) -> str:
    last_message = state["messages"][-1]
    if hasattr(last_message, "tool_calls") and last_message.tool_calls:
        return "tools"
    return state["current_agent"]  # Return to the same agent

# --- Build graph ---
builder = StateGraph(SupportState)
builder.add_node("supervisor", supervisor)
builder.add_node("billing", billing_agent)
builder.add_node("technical", technical_agent)
builder.add_node("tools", ToolNode([lookup_invoice, process_refund, search_knowledge_base, create_support_ticket]))

builder.add_edge(START, "supervisor")
builder.add_conditional_edges("supervisor", lambda s: s["current_agent"], {
    "billing": "billing",
    "technical": "technical",
    "FINISH": END,
})
# Tool routing for each agent
builder.add_conditional_edges("billing", tool_router, {"tools": "tools", "billing": "billing"})
builder.add_conditional_edges("technical", tool_router, {"tools": "tools", "technical": "technical"})
# After tools, return to the agent that called them (via current_agent)
builder.add_conditional_edges("tools", lambda s: s["current_agent"], {
    "billing": "billing",
    "technical": "technical",
})

app = builder.compile(checkpointer=SqliteSaver.from_conn_string("support.db"))
```

### 14.3 Human-in-the-Loop Approval Workflow

```python
"""
A document review pipeline requiring human approval at critical stages:
1. AI generates a draft
2. Human reviews and approves/rejects/edits
3. If approved, AI generates a final version
4. Human signs off on final version before publishing
"""
from typing import TypedDict, Annotated, Literal
from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.types import interrupt, Command

class DocumentState(TypedDict):
    topic: str
    outline: str
    draft: str
    draft_approved: bool
    final: str
    final_approved: bool
    published: bool

def generate_outline(state: DocumentState) -> dict:
    llm = ChatOpenAI(model="gpt-4o")
    response = llm.invoke(f"Create a detailed outline about: {state['topic']}")
    return {"outline": response.content}

def write_draft(state: DocumentState) -> dict:
    llm = ChatOpenAI(model="gpt-4o")
    response = llm.invoke(f"Write a first draft following this outline:\n\n{state['outline']}")
    return {"draft": response.content}

def human_draft_review(state: DocumentState) -> dict:
    """Pause for human to review the draft."""
    decision = interrupt({
        "stage": "draft_review",
        "outline": state["outline"],
        "draft": state["draft"],
        "options": [
            {"value": "approve", "label": "Approve - proceed to final"},
            {"value": "reject", "label": "Reject - I'll rewrite the draft"},
            {"value": "edit", "label": "Edit - revise with feedback"}
        ]
    })

    if decision.get("action") == "approve":
        return {"draft_approved": True}

    elif decision.get("action") == "edit":
        # Incorporate feedback and regenerate
        feedback = decision.get("feedback", "")
        llm = ChatOpenAI(model="gpt-4o")
        revised = llm.invoke(f"Revise this draft based on feedback: {feedback}\n\nDraft:{state['draft']}")
        return {"draft": revised.content, "draft_approved": False}

    else:
        return {"draft_approved": False}

def route_after_draft_review(state: DocumentState) -> Literal["write_final", "write_draft"]:
    return "write_final" if state["draft_approved"] else "write_draft"

def write_final(state: DocumentState) -> dict:
    llm = ChatOpenAI(model="gpt-4o")
    response = llm.invoke(f"Polish this draft into a publishable final version:\n\n{state['draft']}")
    return {"final": response.content}

def human_final_signoff(state: DocumentState) -> dict:
    """Final human signoff before publishing."""
    decision = interrupt({
        "stage": "final_signoff",
        "final_version": state["final"],
        "options": [
            {"value": "publish", "label": "Approve and publish"},
            {"value": "revise", "label": "Request revisions"}
        ]
    })

    if decision.get("action") == "publish":
        return {"final_approved": True, "published": True}
    else:
        feedback = decision.get("feedback", "")
        llm = ChatOpenAI(model="gpt-4o")
        revised = llm.invoke(f"Revise based on this signoff feedback: {feedback}\n\nContent:{state['final']}")
        return {"final": revised.content, "final_approved": False}

def route_after_signoff(state: DocumentState) -> Literal[END, "write_final"]:
    return END if state["final_approved"] else "write_final"

builder = StateGraph(DocumentState)
builder.add_node("generate_outline", generate_outline)
builder.add_node("write_draft", write_draft)
builder.add_node("human_draft_review", human_draft_review)
builder.add_node("write_final", write_final)
builder.add_node("human_final_signoff", human_final_signoff)

builder.add_edge(START, "generate_outline")
builder.add_edge("generate_outline", "write_draft")
builder.add_edge("write_draft", "human_draft_review")
builder.add_conditional_edges("human_draft_review", route_after_draft_review)
builder.add_edge("write_final", "human_final_signoff")
builder.add_conditional_edges("human_final_signoff", route_after_signoff)

app = builder.compile(checkpointer=PostgresSaver.from_conn_string("postgresql://..."))

# Client-side usage:
config = {"configurable": {"thread_id": "doc-workflow-123"}}
app.invoke({"topic": "AI safety in enterprise deployments", ...}, config)

# After human approves at draft stage:
app.invoke(Command(resume={"action": "approve"}), config)

# After human approves final:
app.invoke(Command(resume={"action": "publish"}), config)
```

### 14.4 Streaming Chatbot with Checkpointing

```python
"""
A streaming conversational agent that persists state across sessions
and emits token-level streaming to a chat UI.
"""
import asyncio
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.prebuilt import ToolNode

class ChatState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]

# Define tools
tools = [search_web, get_weather, calculator]
tool_node = ToolNode(tools)

def chatbot(state: ChatState) -> dict:
    llm = ChatOpenAI(model="gpt-4o", streaming=True).bind_tools(tools)
    response = llm.invoke(state["messages"])
    return {"messages": [response]}

def should_continue(state: ChatState) -> Literal["tools", END]:
    last = state["messages"][-1]
    if hasattr(last, "tool_calls") and last.tool_calls:
        return "tools"
    return END

builder = StateGraph(ChatState)
builder.add_node("chatbot", chatbot)
builder.add_node("tools", tool_node)
builder.add_edge(START, "chatbot")
builder.add_conditional_edges("chatbot", should_continue)
builder.add_edge("tools", "chatbot")

checkpointer = PostgresSaver.from_conn_string("postgresql://user:pass@host/langgraph")
app = builder.compile(checkpointer=checkpointer)

# --- FastAPI streaming endpoint ---
from fastapi import FastAPI
from fastapi.responses import StreamingResponse

server = FastAPI()

@server.post("/chat/{thread_id}")
async def chat_endpoint(thread_id: str, body: ChatRequest):
    config = {"configurable": {"thread_id": thread_id}}

    async def event_stream():
        async for part in app.astream(
            {"messages": [HumanMessage(content=body.message)]},
            config,
            stream_mode=["messages", "custom", "updates"],
            version="v2"
        ):
            if part["type"] == "messages":
                chunk, metadata = part["data"]
                node = metadata.get("langgraph_node", "unknown")
                yield f"data: {json.dumps({'type': 'token', 'content': chunk.content, 'node': node})}\n\n"

            elif part["type"] == "updates":
                node_names = list(part["data"].keys())
                yield f"data: {json.dumps({'type': 'node_complete', 'nodes': node_names})}\n\n"

            elif part["type"] == "custom":
                yield f"data: {json.dumps({'type': 'custom', 'data': part['data']})}\n\n"

        yield "data: {\"type\": \"done\"}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")

@server.get("/chat/{thread_id}/history")
async def get_history(thread_id: str):
    """Retrieve and replay conversation from any checkpoint."""
    config = {"configurable": {"thread_id": thread_id}}

    # Get all checkpoints for time-travel navigation
    checkpoints = [
        {
            "checkpoint_id": s.config["configurable"]["checkpoint_id"],
            "step": s.metadata.get("step"),
            "created_at": s.created_at.isoformat(),
            "message_count": len(s.values.get("messages", [])),
        }
        for s in app.get_state_history(config)
    ]

    # Get current state
    current = app.get_state(config)
    messages = [
        {"role": m.__class__.__name__, "content": m.content}
        for m in current.values.get("messages", [])
    ]

    return {
        "current_state": messages,
        "checkpoints": checkpoints,
        "next_nodes": current.next,
    }

@server.post("/chat/{thread_id}/rewind/{checkpoint_id}")
async def rewind_and_continue(thread_id: str, checkpoint_id: str, body: ChatRequest):
    """Rewind to a historical checkpoint and continue with new input."""
    config = {"configurable": {"thread_id": thread_id, "checkpoint_id": checkpoint_id}}

    # Update state at that checkpoint
    app.update_state(config, {"messages": []})  # Optional: clear subsequent messages

    # Continue from there
    new_config = {"configurable": {"thread_id": thread_id}}
    result = await app.ainvoke(
        {"messages": [HumanMessage(content=body.message)]},
        new_config
    )
    return {"response": result["messages"][-1].content}
```

---

## 15. Framework Comparison

### 15.1 LangGraph vs. OpenAI Agents SDK vs. CrewAI vs. AutoGen

| Dimension | **LangGraph** | **OpenAI Agents SDK** | **CrewAI** | **AutoGen (AG2)** |
|---|---|---|---|---|
| **GitHub Stars (2026)** | ~31K | ~20K | ~51K | ~56K (AG2 fork) |
| **Architecture** | Graph-based state machine | Lightweight handoff / runner | Role-based crews with hierarchical tasks | Conversational multi-agent with pub/sub |
| **State Management** | Typed schema + reducers + checkpointing (any DB) | In-memory + optional session objects | Implicit in crew context, no typed schemas | Agent-level state, no graph-level shared state |
| **Persistence** | Full checkpointing (SQLite, Postgres, Redis, MongoDB) | None built-in (stateless by default) | Limited (memory + optional persistence) | None built-in |
| **Streaming** | 7 modes: values, updates, messages, custom, debug, checkpoints, tasks | Token streaming only | Limited token streaming | Token streaming via agents |
| **Human-in-the-Loop** | First-class: `interrupt()`, `interrupt_before/after`, dynamic breakpoints | `human_input_handler` callback, basic approval flows | `human_input` parameter on tasks | `human_input_mode` ("NEVER", "TERMINATE", "ALWAYS") |
| **Multi-Agent Patterns** | Supervisor, Swarm, Hierarchical, Network, custom | Handoff pattern only (agent transfers control) | Sequential/ hierarchical crews only | Conversational, nested chats, group chats |
| **Tool Ecosystem** | 3,000+ via LangChain integrations + MCP adapter | Native OpenAI functions + MCP support | LangChain tools (via integration) + custom | Native function + code execution |
| **Model Flexibility** | High (OpenAI, Anthropic, Google, Mistral, Ollama, NVIDIA, etc.) | Low (OpenAI-first; limited third-party via LiteLLM) | High (via LiteLLM) | High (multi-provider) |
| **Observability** | LangSmith (traces, evals, annotation, datasets, Insights Agent, Polly debugger) | OpenAI dashboard only | Limited (callback-based) | Limited (logging-based) |
| **Deployment** | LangGraph Platform (cloud, hybrid, self-hosted, standalone Docker) | OpenAI API (managed) | Self-hosted only | Self-hosted only |
| **Learning Curve** | Steep (graph mental model, state design, checkpointing) | Very Low (minimal abstractions) | Low (intuitive role/task metaphor) | Moderate (conversational agent pattern) |

### 15.2 Quantitative Scores (1-10 scale)

| Criterion | LangGraph | OpenAI Agents SDK | CrewAI | AutoGen |
|---|---|---|---|---|
| **Learning Curve** (higher = easier) | 4 | 9 | 7 | 5 |
| **Production Readiness** | 10 | 5 | 7 | 5 |
| **State Management** | 10 | 3 | 4 | 5 |
| **Streaming** | 10 | 4 | 5 | 4 |
| **Multi-Agent** | 10 | 3 | 6 | 8 |
| **Tool Ecosystem** | 10 | 6 | 7 | 5 |
| **Observability** | 10 | 4 | 5 | 4 |
| **Cost Efficiency** (tokens) | 8 | 9 | 5 | 6 |
| **Vendor Neutrality** | 10 | 2 | 8 | 9 |

### 15.3 Decision Framework

```
Do you need stateful, long-running, auditable agent workflows?
  │
  ├── YES → LangGraph
  │         (Especially if: multi-agent, human-in-the-loop, streaming variety,
  │          production traceability, failure recovery from checkpoints)
  │
  └── NO ── Do you need role-based team metaphors?
              │
              ├── YES → CrewAI
              │         (Good for: rapid prototyping, simple sequential/hierarchical
              │          task delegation where the "crew" mental model fits)
              │
              └── NO ── Are you OpenAI-native and want minimal code?
                          │
                          ├── YES → OpenAI Agents SDK
                          │         (Good for: single-agent tasks, quick PoCs,
                          │          when you accept vendor lock-in tradeoffs)
                          │
                          └── NO → AutoGen / AG2
                                    (Good for: research, code generation,
                                     multi-agent conversations without strong
                                     persistence requirements)
```

### 15.4 Common Migration Path

```
Rapid Prototyping Phase:
  CrewAI  or  OpenAI Agents SDK
       │
       │  (validate concept, identify state/checkpointing needs)
       v
Production Hardening Phase:
  LangGraph
       │
       │  (add checkpointing, streaming, multi-agent, HITL, observability)
       v
Enterprise Deployment:
  LangGraph + Temporal + LangSmith
       │
       │  (add durable execution, production monitoring, eval pipelines)
       v
Continuous Improvement:
  LangSmith Engine + Deep Agents
```

### 15.5 Critical Caveat: AutoGen Status (2026)

Microsoft's AutoGen entered **maintenance mode** in late 2025. The ongoing community fork **AG2** continues independently under Apache 2.0. Microsoft is merging AutoGen + Semantic Kernel into a unified **"Microsoft Agent Framework"** (targeted GA in 2026). Teams on AutoGen should monitor this transition closely.

### 15.6 Why LangGraph Wins Production

LangGraph's key differentiator is its **checkpoint-every-superstep** execution model. Every other framework treats agent state as optional or ephemeral. LangGraph treats it as foundational, which enables:

1. **Resume after crash**: Any agent workflow survives infrastructure failure
2. **Time-travel debugging**: Rewind to any past state and re-execute
3. **Human-in-the-loop**: Pause at any step, inspect state, modify, resume
4. **Branching**: Fork execution from any checkpoint for A/B testing
5. **Audit trail**: Every state transition is persisted and queryable

No other framework in the comparison matrix provides this level of state durability as a first-class primitive. For production systems where losing agent progress is unacceptable, this is the decisive factor.
