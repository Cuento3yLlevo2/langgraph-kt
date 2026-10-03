# The langgraph-kt tutorial

This guide teaches langgraph-kt the way a game teaches you to play: one new move per level, a
small task to try it out, and nothing you have not been shown yet. You do not need to know
LangGraph, AI agents or graphs. You need to be able to read a little Kotlin.

Every level builds the same thing a bit further: the help desk of a pizza shop called Pixel Pizza,
which reads a customer's message and writes a reply.

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
pretends, so that you can concentrate on how a workflow is built. Level 8 shows where a real model
plugs in.

## The levels

| Level | You learn to | New moves |
|---|---|---|
| [0. The idea](00-the-idea.md) | Read the map | state, node, edge, graph |
| [1. Your first graph](01-first-graph.md) | Build and run a graph with one node | `StateGraph`, `node`, `then`, `compile`, `invoke` |
| [2. A line of nodes](02-a-line-of-nodes.md) | Pass work from one node to the next | `copy`, several nodes |
| [3. Choices](03-choices.md) | Take a different path depending on the state | `conditionalEdge`, `targets` |
| [4. Loops](04-loops.md) | Repeat a step until the result is good | an edge that goes back, `maxIterations` |
| [5. Doing two things at once](05-parallel.md) | Run nodes at the same time | fan-out, `work` and `update` |
| [6. Watching a run](06-watching-a-run.md) | See what the graph is doing while it runs | `stream`, `GraphEvent`, `states` |
| [7. Save points](07-save-points.md) | Pause for a human and continue later | `GraphConfig`, checkpointer, `resume`, `lastResult` |
| [8. A real AI model](08-a-real-ai-model.md) | Let a model write the reply | `chatNode` |
| [9. Game over screens](09-game-over-screens.md) | Understand and recover from errors | exceptions, retry with `resume` |
| [10. Your own workflow](10-your-own-workflow.md) | Combine every move and design a graph for your own problem | the complete help desk, a recipe, testing |

Take them in order; each one takes 5 to 10 minutes. The [cheat sheet](cheat-sheet.md) has every
word and every move on one page.

## How a level works

Each level has the same parts:

- **Goal**: what the help desk can do at the end.
- **The map**: a drawing of the graph.
- **The code**: the program. It is a real file in
  [`samples/.../tutorial`](../samples/src/main/kotlin/org/langgraphkt/samples/tutorial), and a test
  checks that it prints what this guide says.
- **Run it**: one command, and the output you should see.
- **Your turn**: a small change to make yourself. This is where you actually learn it, so do not
  skip it. Edit the level's file and run the command again.
- **Level complete**: what you can do now.

Start with [level 0](00-the-idea.md).
