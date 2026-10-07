# Roadmap

This page lists what is planned for Telar before `1.0`. It is a plan and not a promise: there
are no dates, and the order can change with what users ask for. The [changelog](CHANGELOG.md) lists
what is already released.

To ask for a feature or to move one up, add a 👍 to its issue or
[start a discussion](https://github.com/deeptelar/telar/discussions).

Last updated: 2026-10-07.

## Where the project is

`0.1.0-alpha06` is on Maven Central. It has the graph builder and the engine (loops, parallel
branches, streaming, checkpoints, pausing for a person), checkpointers for memory, files and the
browser, a tool-calling agent for every platform, and model modules for Claude and for LangChain4j.
The API can still change in any release.

Since that release, `main` also has `interrupt`: a node pauses the run in the middle of its work, for
example to ask a person a question it only knows at run time.

## The way to 1.0

| Stage | What happens | The public API |
|---|---|---|
| **Alpha** (now) | The features below are added, the engine first | May change in any release |
| **Beta** | No new features. The API is reviewed and the documentation completed | Changes only for a good reason, and the changelog says how to update |
| **1.0** | Released when the beta has been used in real apps without API changes | Stable. A breaking change waits for `2.0` |

Features that change the core API come first, so that the API can stop moving as early as possible.

Each item has one of three states:

- **In progress**: someone is writing it.
- **Planned**: it will be in `1.0`.
- **Exploring**: wanted, but the design is open. It may arrive after `1.0`.

## 1. Finish the engine

These change `telar-core`, so they come first.

| Feature | What you get | State | Issue |
|---|---|---|---|
| Subgraphs | A compiled graph becomes a node of another graph, with a mapping between the two states and one thread for both. Today a node can call another graph, but the outer run cannot pause or resume inside it. | Planned | [#34] |
| Checkpoint history and forks | A thread keeps every step, not only the last one. Read an earlier state, or continue from it on a new thread without changing the original. This changes the `Checkpointer` interface. | Planned | [#35] |
| Fan-out over a list | One node runs once per item of a list, all at the same time, when the number of items is only known at run time. Today the edges fix how many nodes run in parallel. | Planned | |
| Retry and time limit for a node | A node says how often it may be tried again and how long it may take. Today a failed run is retried by hand with `resume`. | Planned | |
| A merge rule for one property | "Add to this list, replace that value", written once in place of a `Reducer` that merges whole states by hand. Nodes with `work` and `update` already cover most graphs, and Kotlin Multiplatform has no reflection, so the design is open. It is decided before the beta, because it changes how a state is written. | Exploring | [#41] |

## 2. Around the engine

New modules and additions to `telar-agent`. They do not change the core.

| Feature | What you get | State | Issue |
|---|---|---|---|
| Database checkpointer | Saves runs in a SQL database, for servers and for Android apps. | Planned | |
| OpenAI and Ollama | `telar-openai` and `telar-ollama` on Ktor, for every platform. Today these models are reached through LangChain4j, on the JVM only. | Planned | [#37] |
| Typed answers from a model | Ask a model for a `@Serializable` class and get an instance of it, built on the schema generator that tools already use. | Planned | |
| Tracing | Hooks in the engine that report every node, edge, tool call and token count, and a module that sends them to OpenTelemetry. | Planned | [#38] |

## 3. Beta: make it stable

- **API review.** Every public declaration is checked once more: its name, its defaults, and whether
  it can be `internal`. `apiCheck` already fails a build that changes the public API by accident.
- **A marker for experimental API.** An opt-in annotation, so that a feature can be added after
  `1.0` without being frozen on its first day.
- **A checkpoint format you can rely on.** A checkpoint written by one version can be read by every
  later `1.x` version, and a guide explains how to change a state class when saved runs exist.
- **Tests in a real browser.** The JS and Wasm tests of the agent and model modules run on Node.js
  today. Only the browser checkpointer is tested in a browser.
- **Documentation.** A guide for every feature on this page, a tutorial level where one fits, and a
  page for people who know LangGraph for Python: what has the same name and what is different.
- **Written rules.** Which Kotlin versions are supported, and how long a deprecated declaration
  stays before it is removed.

## Exploring

Ideas without a place in the plan yet. Say so in the issue or in a discussion if you need one.

| Idea | What it is | Issue |
|---|---|---|
| Functional API | Write a small workflow as ordinary `suspend` functions, without a graph, and keep checkpoints and streaming. | [#39] |
| Decision models | An interface for models that classify instead of chat, such as Jev, and a router built on it. "Typed answers from a model" above is the first step: it gives a typed router with any model. | [#40] |
| Images and files in messages | A `ChatMessage` holds text only today. | |
| Tools from MCP servers | Use the tools of a Model Context Protocol server as `Tool`s. | |
| Memory across threads | A store that an agent reads and writes in every conversation, next to the checkpoint of one thread. | |
| Ready-made teams of agents | A supervisor and hand-offs between agents, built on subgraphs. | |

## Not planned

- **A port of LangChain.** No prompt templates, vector stores or document loaders. A node is a
  `suspend` function and can call any library.
- **A hosted service or a server that runs graphs.** The library runs inside your app.
- **The same API as LangGraph for Python.** A feature comes over when it fits Kotlin: typed
  immutable state, coroutines, and checks in `compile()`.
- **Reading checkpoints written by LangGraph for Python.**

## Helping

To build something on this page, comment on its issue first, or open one if it has none, so the
approach can be discussed. [CONTRIBUTING.md](CONTRIBUTING.md) has the setup and the design rules.

[#34]: https://github.com/deeptelar/telar/issues/34
[#35]: https://github.com/deeptelar/telar/issues/35
[#37]: https://github.com/deeptelar/telar/issues/37
[#38]: https://github.com/deeptelar/telar/issues/38
[#39]: https://github.com/deeptelar/telar/issues/39
[#40]: https://github.com/deeptelar/telar/issues/40
[#41]: https://github.com/deeptelar/telar/issues/41
