# Errors

Mistakes in the graph itself (an unknown node name, a node nothing leads to, two nodes that return
a whole state in the same step without a reducer) are reported by `compile()`, before anything runs. Everything the library throws
extends `TelarException`:

| Exception | When |
|---|---|
| `GraphValidationException` | The graph or `GraphConfig` is invalid. Thrown by `compile()` or when a run starts, and when a node calls `interrupt` in a run without a checkpointer. |
| `NodeExecutionException` | A node threw, or a `withTimeout` inside it expired. `nodeName` and the original `cause` are available. Every exception is wrapped, also one of this table that a graph inside the node threw. |
| `EdgeConditionException` | The function of a conditional edge threw. `from` and the original `cause` are available. |
| `ReducerException` | The reducer threw. `nodes` (the nodes whose states it was merging) and the original `cause` are available. |
| `InvalidRouteException` | A conditional edge returned a node that does not exist or is not a declared target. |
| `MaxIterationsExceededException` | The run took more steps than `GraphConfig.maxIterations` (default 25). |
| `CheckpointNotFoundException`, `GraphAlreadyCompletedException` | `resume` had nothing to continue. |
| `ThreadAlreadyExistsException` | `fork` was given a thread that already has a checkpoint. |
| `CheckpointCorruptedException` | A stored checkpoint could not be read. |
| `LocalStorageException` | The browser refused to read or write `localStorage`: it is full, or the page may not use it. From `telar-checkpoint-browser`. |
| `ChatModelException` | A call to a `ChatModel` failed, or the model declined to answer. From `telar-agent`. A run reports it as the `cause` of a `NodeExecutionException`. |
