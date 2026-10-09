# Parallel branches

When several edges leave the same place, their target nodes run at the same time. Write such a node
in two parts: `work` is the slow part, for example a call to a model, and returns a result. The
block after it is the node's `update`, which writes that result into the state.

```kotlin
data class ResearchState(
    val question: String,
    val findings: List<String> = emptyList(),
    val summary: String = "",
)

val graph = StateGraph<ResearchState> {
    // The `work` of both nodes runs at the same time. Their updates are then applied one after the other.
    val web = node("web", work = { searchWeb(it.question) }) { state, found ->
        state.copy(findings = state.findings + found)
    }
    val docs = node("docs", work = { searchDocs(it.question) }) { state, found ->
        state.copy(findings = state.findings + found)
    }
    val summarize = node("summarize") { it.copy(summary = summarize(it.findings)) }

    // Two edges leave START, so "web" and "docs" run at the same time.
    START then web then summarize
    START then docs then summarize
    // "summarize" runs once, after both have finished, with the findings of both.
    summarize then END
}.compile()
```

Runnable version: [`ParallelResearch`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/ParallelResearch.kt).

- The updates are applied in the order the nodes were added to the graph (`web`, then `docs`), each
  to the state that the previous one produced. If two nodes write the same property, the one added
  later wins.
- `update` only builds the new state, and the engine may call it more than once. Keep slow calls
  and side effects in `work`.
- If one branch fails, the others are cancelled and the error is rethrown as
  `NodeExecutionException` with the name of the failing node.

## Merging whole states

A node written as `node(name) { ... }` returns a whole state. One such node can share a step with
the nodes above, but when two of them run in the same step there are two states and the run can
continue with only one. `compile()` then asks for a `Reducer`, which makes one state out of them:

```kotlin
data class Pitch(val product: String, val text: String = "")

// `current` is the state before the step. `updates` has one state per node that returned a whole state.
// Two writers each return a complete pitch, and the shorter one is kept.
val keepShortest = Reducer<Pitch> { current, updates -> updates.minBy { it.text.length } }

val graph = StateGraph<Pitch> {
    val formal = node("formal") { it.copy(text = writeFormally(it.product)) }
    val casual = node("casual") { it.copy(text = writeCasually(it.product)) }

    START then formal then END
    START then casual then END
}.compile(reducer = keepShortest) // without a reducer, compile() rejects this graph
```

A reducer written by hand has to carry over everything it wants to keep from `updates`. When the
nodes add to the state, `mergeRules` builds the reducer from one rule for each property they change:

```kotlin
data class Research(val notes: List<String> = emptyList(), val status: String = "", val score: Int = 0)

val graph = StateGraph<Research> {
    val web = node("web") { it.copy(notes = it.notes + searchWeb()) }
    val docs = node("docs") { it.copy(notes = it.notes + searchDocs(), status = "found") }

    START then web then END
    START then docs then END
}.compile(
    reducer = mergeRules {
        // A list gets what each node added to it.
        append(Research::notes) { copy(notes = it) }
        // A value is taken from the node that changed it.
        replace(Research::status) { copy(status = it) }
        // Anything else: say how the changed values combine.
        merge(Research::score, combine = { _, changed -> changed.max() }) { copy(score = it) }
    },
)
```

- A rule names a property and says how to write the merged value back, which is a `copy`.
- A node that changes a property without a rule fails the run with a `ReducerException`. Nothing is
  lost without a word.
- So do two nodes that `replace` a property with different values. `merge` says which one counts:
  `combine = { _, changed -> changed.last() }` lets the last node of the step win.

Which one to use: `work` and `update` when a node does slow work and then adds its result;
`mergeRules` when nodes are written as `node(name) { ... }` and change different properties, or add
to the same list; a reducer of your own when the step has to choose or compare whole states.
