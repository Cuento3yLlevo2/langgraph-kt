# The Telar tutorial

This guide teaches Telar the way a game teaches you to play: eight levels, each with a new
move and a small task to try it out, and nothing you have not been shown yet. You do not need to
know LangGraph, AI agents or graphs. You need to be able to read a little Kotlin.

Every level builds the same thing a bit further: the help desk of a pizza shop called Pixel Pizza,
which reads a customer's message and writes a reply.

Want to see where this leads first? The same help desk is a
[game you can play in your browser](https://cuento3yllevo2.github.io/langgraph-kt-demo/). Its eight
stages are the eight levels: stage 3 is level 3.

## What you need

- JDK 17 or newer.
- This repository on your computer:

```bash
git clone https://github.com/Cuento3yLlevo2/langgraph-kt.git
cd langgraph-kt
./gradlew :samples:runLevel1
```

The first run downloads Gradle and Kotlin and takes a few minutes. When it prints
`Hi Ana, thanks for writing to Pixel Pizza!`, you are ready.

You do not need an AI account or an API key. The "AI" in this tutorial is ordinary Kotlin code that
pretends, so that you can concentrate on how a workflow is built. Level 6 shows where a real model
plugs in.

## The levels

| Level | You learn to | New moves |
|---|---|---|
| [The idea](00-the-idea.md) | Read the map. A short page to read first. | state, node, edge, graph |
| [1. A line of nodes](01-a-line-of-nodes.md) | Build and run a graph, and pass work from one node to the next | `StateGraph`, `node`, `then`, `compile`, `invoke` |
| [2. Choices](02-choices.md) | Take a different path depending on the state | `conditionalEdge`, `targets` |
| [3. Loops](03-loops.md) | Repeat a step until the result is good | an edge that goes back, `maxIterations` |
| [4. Two things at once](04-two-things-at-once.md) | Run nodes at the same time, and watch a run while it happens | fan-out, `work` and `update`, `stream`, `GraphEvent` |
| [5. Save points](05-save-points.md) | Pause for a human and continue later | `GraphConfig`, checkpointer, `resume`, `lastResult` |
| [6. The agent](06-the-agent.md) | Let a model write the reply, and decide which of your functions to call | `ChatModel`, `Tool`, `toolAgent`, `toolLoop` |
| [7. Game over screens](07-game-over-screens.md) | Understand and recover from errors | exceptions, retry with `resume` |
| [8. Your own workflow](08-your-own-workflow.md) | Combine every move and design a graph for your own problem | the complete help desk, a recipe, testing |

Take them in order. Most take about 10 minutes; level 6 is the longest. The
[cheat sheet](cheat-sheet.md) has every word and every move on one page.

## How a level works

Each level has the same parts:

- **Goal**: what the help desk can do at the end.
- **The map**: a drawing of the graph.
- **The code**: the program. It is a real file in
  [`samples/.../tutorial`](../samples/src/main/kotlin/dev/deeptelar/telar/samples/tutorial), and a test
  checks that it prints what this guide says.
- **Run it**: one command, and the output you should see.
- **Your turn**: a small change to make yourself. This is where you actually learn it, so do not
  skip it. Edit the level's file and run the command again.
- **Level complete**: what you can do now.

Start with [the idea](00-the-idea.md).
