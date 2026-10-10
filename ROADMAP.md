# Roadmap

Telar aims to be the open-source library a Kotlin developer reaches for to build AI agents and
workflows: agents first, simple to start with, and with the most complete set of workflow features.
Models, and the ways of working with them, change fast. This plan changes with them: what
developers need for their agents goes on this page, and the order follows what they ask for.

This page lists what is planned for Telar before `1.0`. It is a plan and not a promise: there
are no dates, and the order can change with what users ask for. The [changelog](CHANGELOG.md) lists
what is already released.

To ask for a feature or to move one up, add a 👍 to its issue or
[start a discussion](https://github.com/deeptelar/telar/discussions).

Last updated: 2026-10-10.

## Where the project is

`0.1.0-alpha08` is on Maven Central. It has the graph builder and the engine: loops, parallel
branches, subgraphs, streaming, checkpoints with the history of a run, and pausing for a person,
before a node or in the middle of its work. Around the engine it has checkpointers for memory, files
and the browser, a tool-calling agent for every platform, and model modules for Claude, for OpenAI,
Ollama and the other servers with the Chat Completions API, for LangChain4j, and for Jev, a decision
model. Any of the chat models can take the decisions of a workflow too. The API can still change in
any release.

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
| Subgraphs | A compiled graph becomes a node of another graph, with a mapping between the two states and one thread for both. Released: `subgraph`, a run that pauses inside a subgraph and resumes there, the events of a subgraph in the stream of the graph around it, and the subgraph in `topology`. Still to come: `interruptBefore` for a node of a subgraph, and a retry after a failure that continues inside the subgraph. | In progress | [#34] |
| Fan-out over a list | One node runs once per item of a list, all at the same time, when the number of items is only known at run time. Today the edges fix how many nodes run in parallel. | Planned | [#82] |
| Retry and time limit for a node | A node says how often it may be tried again and how long it may take. Today a failed run is retried by hand with `resume`. | Planned | [#83] |

## 2. Around the engine

New modules and additions to `telar-agent`. They do not change the core.

| Feature | What you get | State | Issue |
|---|---|---|---|
| Database checkpointer | Saves runs in a SQL database, for servers and for Android apps. | Planned | [#84] |
| Typed answers from a model | Ask a model for a `@Serializable` class and get an instance of it, built on the schema generator that tools already use. | Planned | [#85] |
| Tracing | Hooks in the engine that report every node, edge, tool call and token count, and a module that sends them to OpenTelemetry. | Planned | [#38] |
| Helpers for tests | `telar-test`: a model that gives the answers a test wrote down, so that a graph with an agent is tested without an API key and without a model of your own. | Planned | [#86] |
| One version for all modules | `telar-bom`: a bill of materials. An app names the version of Telar once, and every module it adds uses that version. | Planned | [#79] |

## 3. Beta: make it stable

- **API review.** Every public declaration is checked once more: its name, its defaults, and whether
  it can be `internal`. `apiCheck` already fails a build that changes the public API by accident.
- **A marker for experimental API.** An opt-in annotation, so that a feature can be added after
  `1.0` without being frozen on its first day.
- **A checkpoint format you can rely on.** A checkpoint written by one version can be read by every
  later `1.x` version, and a guide explains how to change a state class when saved runs exist.
- **Tests in a real browser.** The JS and Wasm tests of the agent and model modules run on Node.js
  today. Only the browser checkpointer is tested in a browser ([#81]).
- **Documentation.** A guide for every feature on this page, a tutorial level where one fits, and a
  page for people who know LangGraph for Python: what has the same name and what is different
  ([#80]).
- **Written rules.** Which Kotlin versions are supported, and how long a deprecated declaration
  stays before it is removed.

## Exploring

Ideas without a place in the plan yet. Say so in the issue or in a discussion if you need one.

| Idea | What it is | Issue |
|---|---|---|
| Functional API | Write a small workflow as ordinary `suspend` functions, without a graph, and keep checkpoints and streaming. | [#39] |
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

For a first contribution, the issues labeled
[good first issue](https://github.com/deeptelar/telar/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
are small and say where to start. The ones labeled
[help wanted](https://github.com/deeptelar/telar/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)
are larger, or need something the maintainers lack, such as a key for another model provider.

[#34]: https://github.com/deeptelar/telar/issues/34
[#38]: https://github.com/deeptelar/telar/issues/38
[#39]: https://github.com/deeptelar/telar/issues/39
[#79]: https://github.com/deeptelar/telar/issues/79
[#80]: https://github.com/deeptelar/telar/issues/80
[#81]: https://github.com/deeptelar/telar/issues/81
[#82]: https://github.com/deeptelar/telar/issues/82
[#83]: https://github.com/deeptelar/telar/issues/83
[#84]: https://github.com/deeptelar/telar/issues/84
[#85]: https://github.com/deeptelar/telar/issues/85
[#86]: https://github.com/deeptelar/telar/issues/86
