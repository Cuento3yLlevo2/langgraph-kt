package org.langgraphkt

/**
 * Base class for every error raised by langgraph-kt itself, so callers can catch the whole family
 * with a single `catch (e: LangGraphException)`.
 */
public sealed class LangGraphException(
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
 * A node's action threw an exception. The original exception is available as [cause].
 *
 * Coroutine cancellation is never wrapped: a `CancellationException` propagates unchanged.
 */
public class NodeExecutionException(
    public val nodeName: String,
    cause: Throwable,
) : LangGraphException("Node '$nodeName' failed: ${cause.message}", cause)
