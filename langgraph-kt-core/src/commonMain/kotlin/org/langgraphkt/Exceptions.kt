package org.langgraphkt

/**
 * Base class for every error raised by langgraph-kt itself, so callers can catch the whole family
 * with a single `catch (e: LangGraphException)`. Integration modules and custom [Checkpointer]s may
 * add their own subclasses.
 */
public abstract class LangGraphException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The graph definition or its run configuration is invalid: for example an edge points at an unknown
 * node, or a fan-out has no [Reducer]. Thrown while building or compiling the graph, or when a run
 * starts, never in the middle of execution.
 */
public class GraphValidationException(
    message: String,
) : LangGraphException(message)

/**
 * The graph ran more steps than [GraphConfig.maxIterations] allows, which usually means a cycle
 * never routes to [END].
 */
public class MaxIterationsExceededException(
    public val maxIterations: Int,
) : LangGraphException("Graph execution exceeded max iterations ($maxIterations). Possible infinite loop.")

/**
 * A conditional edge from [from] returned [target], which is not a node of the graph or not one of
 * the edge's declared targets.
 */
public class InvalidRouteException(
    public val from: String,
    public val target: String,
) : LangGraphException("Conditional edge from '$from' routed to '$target', which is not a valid target.")

/**
 * A node's action threw an exception. The original exception is available as [cause].
 *
 * Cancellation of the run itself is never wrapped: it propagates as a `CancellationException`. A
 * `CancellationException` that a node raises while the run is still active, such as an expired
 * `withTimeout` inside the node, is a failure of that node and is wrapped like any other.
 */
public class NodeExecutionException(
    public val nodeName: String,
    cause: Throwable,
) : LangGraphException("Node '$nodeName' failed: ${cause.message}", cause)

/**
 * The condition of the conditional edge from [from] threw an exception. The original exception is
 * available as [cause]. Cancellation is handled as for [NodeExecutionException].
 */
public class EdgeConditionException(
    public val from: String,
    cause: Throwable,
) : LangGraphException("Conditional edge from '$from' failed: ${cause.message}", cause)

/**
 * The [Reducer] threw an exception while it merged the results of [nodes], the nodes of the failed
 * step that each returned a whole state. The original exception is available as [cause]. Cancellation is
 * handled as for [NodeExecutionException].
 */
public class ReducerException(
    public val nodes: List<String>,
    cause: Throwable,
) : LangGraphException("Reducer failed to merge the results of $nodes: ${cause.message}", cause)

/**
 * [CompiledGraph.resume] was called for [threadId], but the checkpointer has no checkpoint for it.
 */
public class CheckpointNotFoundException(
    public val threadId: String,
) : LangGraphException("No checkpoint found for thread '$threadId'. Start the run with invoke() first.")

/**
 * [CompiledGraph.resume] was called for [threadId], but its last run already completed. Start a new
 * run with [CompiledGraph.invoke].
 */
public class GraphAlreadyCompletedException(
    public val threadId: String,
) : LangGraphException("Thread '$threadId' already ran to completion, so there is nothing to resume.")

/**
 * The stored checkpoint of [threadId] could not be read, for example because the file is damaged or
 * was written in a newer format. [Checkpointer] implementations throw this from [Checkpointer.load].
 */
public class CheckpointCorruptedException(
    public val threadId: String,
    message: String,
    cause: Throwable? = null,
) : LangGraphException("Checkpoint of thread '$threadId' cannot be read: $message", cause)
