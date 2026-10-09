# Loops

A loop is a conditional edge that can point back to an earlier node. Keep a counter in the state so
the loop always ends:

```kotlin
data class Draft(val text: String = "", val attempts: Int = 0)

val graph = StateGraph<Draft> {
    val write = node("write") { it.copy(text = improve(it.text), attempts = it.attempts + 1) }

    START then write
    // Good enough, or tried three times: finish. Otherwise run "write" again.
    conditionalEdge(write, targets = setOf(write, NodeRef.END)) { draft ->
        if (isGood(draft.text) || draft.attempts >= 3) NodeRef.END else write
    }
}.compile()
```

`NodeRef.END` is `END` as a node reference, which is what a conditional edge returns. A conditional
edge can also work with node names, for a target that is computed from data:
`conditionalEdge("write", targets = setOf("write", END)) { ... }`.

As a safety net, a run that takes more than `GraphConfig.maxIterations` steps (25 by default) stops
with `MaxIterationsExceededException`.
