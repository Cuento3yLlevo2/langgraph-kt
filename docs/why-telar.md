# Why Telar

- **Your state is a plain `data class`.** Each node returns a `.copy()` of it, so a run is easy to
  test, print and save, and parallel branches cannot overwrite each other by accident.
- **Mistakes in the graph fail in `compile()`.** An unknown node, a node nothing leads to, or two
  parallel nodes with no rule to merge their results are reported before anything runs.
- **Pause anywhere, continue later.** Stop before a node, or call `interrupt` from inside one when
  it finds out it needs a person. The run is saved, and `resume` continues it hours later, in
  another process. `history` and `fork` go back to any earlier step.
- **Every Kotlin platform.** The core depends only on kotlinx-coroutines and runs on the JVM,
  Android, iOS, macOS, Linux, Windows, JS and Wasm. Agents, models and checkpoints work there too,
  down to runs saved in the browser's `localStorage`.
- **Coroutines all the way.** Nodes, edges and models are `suspend` functions. Cancellation works
  as you expect, and parallel nodes use structured concurrency.
- **Any model, small surface.** Claude, OpenAI, Gemini, Ollama and every server with the API of
  OpenAI, plus any LangChain4j model on the JVM. `ChatModel` is one function, so a model of your
  own, or a fake one in a test, takes a few lines.

## Telar and other libraries

A Kotlin project has other good choices for AI agents. This is how we see the differences in October
2026; if something here is wrong or out of date, please
[open an issue](https://github.com/deeptelar/telar/issues).

| | Telar | [Koog](https://github.com/JetBrains/koog) | [LangGraph4j](https://github.com/langgraph4j/langgraph4j) | [LangChain4j](https://github.com/langchain4j/langchain4j) |
|---|---|---|---|---|
| **What it is** | A graph engine for agents and workflows | JetBrains' framework for AI agents | A Java port of LangGraph | A Java toolkit for LLM apps |
| **Platforms** | JVM, Android, iOS, macOS, Linux, Windows, JS, Wasm | JVM, Android, iOS, JS, Wasm | JVM (Java 17+) | JVM (Java 17+) |
| **Workflow state** | Your own immutable `data class` | Values passed between the nodes of a strategy, and the agent's storage | A map of channels with reducers | Mostly the conversation memory |
| **Stage** | Alpha: the API can still change | Stable `1.x` | Stable `1.x` | Stable `1.x` |
| **Best at** | Workflows with typed state, checks before a run, pauses and save points, on every platform | A complete agent platform: many providers, MCP, tracing, backed by JetBrains | LangGraph's design on the JVM, from Java | Integrations: models, vector stores, RAG |

Choose Telar when the workflow itself is the hard part: several steps, branches that join, people
who approve, runs that must survive a restart, and state you want to read as ordinary Kotlin. If you
need a large set of ready-made integrations or a stable API today, Koog or LangChain4j are the
safer choice. Telar works with LangChain4j: [`telar-langchain4j`](guides/ai-models.md) turns any of its
models into a `ChatModel`.

> Telar is an independent project inspired by [LangGraph](https://github.com/langchain-ai/langgraph).
> It is not affiliated with or endorsed by LangChain, Inc.
