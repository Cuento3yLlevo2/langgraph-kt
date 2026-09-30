# Contributing to langgraph-kt

First off, thank you for considering contributing to `langgraph-kt`! Our goal is to build the definitive, native Kotlin engine for stateful AI workflows.

Whether you are a human developer or an autonomous CLI agent (like OpenCode), you must adhere to the core architectural principles outlined below to maintain the safety, concurrency, and predictability of the graph execution.

## Core Architectural Rules

This repository enforces strict Kotlin-idiomatic patterns. All pull requests and automated commits will be reviewed against these three rules:

### 1. Immutability is Law

LangGraph's state routing relies on predictable state updates.

* **DO:** Use Kotlin `data class` structures for all state representations.
* **DO:** Use the `.copy()` function to apply state updates.
* **DO:** Use Kotlin collection operators (e.g., `list + newItem`) to append data.
* **DO NOT:** Use mutable collections (`MutableList`, `ArrayList`) or `var` properties inside state objects.

### 2. Native Coroutines Only

We are building for the JVM and Android, maximizing asynchronous performance without blocking threads.

* **DO:** Make every node and edge evaluation a `suspend` function.
* **DO:** Use `coroutineScope` and `async { ... }` for parallel node execution.
* **DO NOT:** Use `Thread.sleep()`, Java's `CompletableFuture`, or `RxJava`.
* **DO NOT:** Block the main thread for any LLM network or JNI calls.

### 3. Type-Safe DSL

The graph builder must remain intuitive and safe at compile time.

* **DO:** Use Kotlin's `infix` functions and reified type parameters to keep the routing syntax clean.
* **DO:** Ensure edges cannot connect nodes that do not share compatible state contracts.

## Development Setup

1. Clone the repository:
```bash
git clone git@github.com:Cuento3yLlevo2/langgraph-kt.git

```


2. Open the project in **IntelliJ IDEA**. The project uses the Gradle Kotlin DSL (`build.gradle.kts`).
3. Ensure your JDK is set to version 17 or higher.
4. Run the initial build to download dependencies:
```bash
./gradlew build

```



## Guidelines for CLI Agents (OpenCode / LLMs)

If this repository is being modified by an autonomous AI agent, the agent must observe the following constraints:

* **Context Scope:** Always parse this `CONTRIBUTING.md` file before generating code.
* **Dependencies:** Do not introduce heavy third-party libraries without explicit user permission. Rely on Kotlin Standard Library (`kotlinx.coroutines`, `kotlinx.serialization`) and `LangChain4j` core interfaces.
* **Testing:** Every new `suspend` Node or Edge implementation must be accompanied by a unit test using `kotlinx-coroutines-test` (`runTest { ... }`).
* **Formatting:** Follow standard `ktlint` styling.

## Pull Request Process

1. Check the **Issues** tab for an existing task, or open a new one to discuss your proposed changes.
2. Create a new branch from `main` (e.g., `feature/room-checkpointer`).
3. Write your code, ensuring all new functions are documented with KotlinDoc.
4. Run the test suite locally:
```bash
./gradlew check

```


5. Submit a Pull Request. Include a brief summary of the changes and link to the relevant GitHub Issue.

## License

By contributing to `langgraph-kt`, you agree that your contributions will be licensed under its **Apache License 2.0**.