package dev.deeptelar.telar

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The state of the subgraph. [question] is what it asks a person, [approved] the answer. */
private data class Study(
    val topic: String = "",
    val notes: List<String> = emptyList(),
    val question: String? = null,
    val approved: Boolean? = null,
)

/** The state of the graph around it, which keeps the state of the subgraph in [research]. */
private data class Request(
    val topic: String,
    val research: Study = Study(),
    val reply: String = "",
    val stamped: Boolean = false,
)

class SubgraphTest {
    /** How often the node "search" of the subgraph started. */
    private var searches = 0

    /** Searches, asks a person whether to use what it found, and sums up. */
    private val researchGraph =
        StateGraph<Study> {
            val search =
                node("search") {
                    searches++
                    it.copy(notes = it.notes + "found ${it.topic}")
                }
            val ask =
                node("ask") {
                    if (it.approved == null) interrupt(it.copy(question = "Use the notes on ${it.topic}?"))
                    it.copy(question = null)
                }
            val sum = node("sum") { it.copy(notes = it.notes + if (it.approved == true) "approved" else "rejected") }

            START then search then ask then sum then END
        }.compile()

    private val app =
        StateGraph<Request> {
            val intake = node("intake") { it.copy(research = Study(topic = it.topic)) }
            val research =
                subgraph("research", researchGraph, state = { it.research }, update = {
                    order,
                    research,
                    ->
                    order.copy(research = research)
                })
            val reply = node("reply") { it.copy(reply = it.research.notes.joinToString()) }

            START then intake then research then reply then END
        }.compile()

    private val checkpointer = MemoryCheckpointer<Request>()
    private val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

    private val asked = Study("wasm", notes = listOf("found wasm"), question = "Use the notes on wasm?")

    @Test
    fun `a subgraph runs from its start to its end inside one step`() =
        runTest {
            val quick =
                StateGraph<Request> {
                    val research =
                        subgraph(
                            "research",
                            researchGraph,
                            // A subgraph that does not pause can get a new state and give back only its result.
                            state = { Study(topic = it.topic, approved = true) },
                            update = { order, research -> order.copy(reply = research.notes.joinToString()) },
                        )
                    START then research then END
                }.compile()

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "research", Request("wasm")),
                    GraphEvent.NodeCompleted(1, "research", Request("wasm", reply = "found wasm, approved")),
                    GraphEvent.StepCompleted(1, listOf("research"), Request("wasm", reply = "found wasm, approved")),
                    GraphEvent.Completed(Request("wasm", reply = "found wasm, approved")),
                ),
                // The three steps of the subgraph are one step here.
                quick.stream(Request("wasm")).toList().filterNot { it is GraphEvent.SubgraphEvent },
            )
        }

    @Test
    fun `a pause inside a subgraph pauses the graph around it`() =
        runTest {
            val paused = app.invoke(Request("wasm"), config)

            // The state of the subgraph is in the state that is saved, so the caller sees the question.
            assertEquals(GraphResult.Interrupted(Request("wasm", research = asked), listOf("research")), paused)
            assertEquals(
                Checkpoint(
                    Request("wasm", research = asked),
                    listOf("research"),
                    step = 1,
                    interruptedBefore = true,
                    subgraphs = mapOf("research" to SubgraphPosition(listOf("ask"), step = 1, interruptedBefore = true)),
                ),
                checkpointer.load("t"),
            )
            assertEquals(paused, app.lastResult(config))
        }

    @Test
    fun `resume continues inside the subgraph at the node that paused`() =
        runTest {
            app.invoke(Request("wasm"), config)

            val finished = app.resume(config) { it.copy(research = it.research.copy(approved = true)) }

            val research = Study("wasm", notes = listOf("found wasm", "approved"), approved = true)
            assertEquals(GraphResult.Completed(Request("wasm", research = research, reply = "found wasm, approved")), finished)
            // "search" came before the pause, so it did not run again.
            assertEquals(1, searches)
            assertEquals(Checkpoint(finished.state, emptyList(), step = 3), checkpointer.load("t"))
        }

    @Test
    fun `resume without an answer pauses inside the subgraph again`() =
        runTest {
            val paused = app.invoke(Request("wasm"), config)

            assertEquals(paused, app.resume(config))
            assertEquals("found wasm, rejected", app.resume(config) { it.copy(research = it.research.copy(approved = false)) }.state.reply)
            assertEquals(1, searches)
        }

    @Test
    fun `a stream has the events of the subgraph between the start and the end of its node`() =
        runTest {
            val reporting =
                StateGraph<TestState> {
                    val count =
                        node("count") {
                            reportProgress("counting")
                            it.copy(count = it.count + 1)
                        }
                    val double = node("double") { it.copy(count = it.count * 2) }

                    START then count then double then END
                }.compile()
            val around = StateGraph<TestState> { START then subgraph("inner", reporting) then END }.compile()

            fun inside(event: GraphEvent<TestState>) = GraphEvent.SubgraphEvent(1, "inner", event, TestState(0))

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "inner", TestState(0)),
                    inside(GraphEvent.NodeStarted(1, "count", TestState(0))),
                    inside(GraphEvent.NodeProgress(1, "count", "counting", TestState(0))),
                    inside(GraphEvent.NodeCompleted(1, "count", TestState(1))),
                    inside(GraphEvent.StepCompleted(1, listOf("count"), TestState(1))),
                    inside(GraphEvent.NodeStarted(2, "double", TestState(1))),
                    inside(GraphEvent.NodeCompleted(2, "double", TestState(2))),
                    inside(GraphEvent.StepCompleted(2, listOf("double"), TestState(2))),
                    inside(GraphEvent.Completed(TestState(2))),
                    GraphEvent.NodeCompleted(1, "inner", TestState(2)),
                    GraphEvent.StepCompleted(1, listOf("inner"), TestState(2)),
                    GraphEvent.Completed(TestState(2)),
                ),
                around.stream(TestState(0)).toList(),
            )
        }

    @Test
    fun `the events of a subgraph carry its own state type`() =
        runTest {
            val events = app.stream(Request("wasm"), config).toList()
            val received = Request("wasm", research = Study("wasm"))

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(2, "research", received),
                    GraphEvent.SubgraphEvent(2, "research", GraphEvent.NodeStarted(1, "search", Study("wasm")), received),
                ),
                events.filter { (it as? GraphEvent.NodeStarted)?.node == "research" || it is GraphEvent.SubgraphEvent }.take(2),
            )
        }

    @Test
    fun `a pause inside a subgraph ends its events and then the stream with Interrupted`() =
        runTest {
            val events = app.stream(Request("wasm"), config).toList()
            val received = Request("wasm", research = Study("wasm"))

            assertEquals(
                listOf(
                    GraphEvent.SubgraphEvent(2, "research", GraphEvent.NodeStarted(2, "ask", asked.copy(question = null)), received),
                    GraphEvent.SubgraphEvent(2, "research", GraphEvent.Interrupted(asked, listOf("ask")), received),
                    GraphEvent.Interrupted(Request("wasm", research = asked), listOf("research")),
                ),
                events.takeLast(3),
            )
        }

    @Test
    fun `a resumed stream has the events of the subgraph from the node that paused`() =
        runTest {
            app.invoke(Request("wasm"), config)

            val events = app.streamResume(config) { it.copy(research = it.research.copy(approved = true)) }.toList()

            assertEquals(
                listOf("ask", "sum"),
                events
                    .filterIsInstance<GraphEvent.SubgraphEvent<Request>>()
                    .mapNotNull { (it.event as? GraphEvent.NodeStarted)?.node },
            )
            assertEquals(1, searches)
        }

    @Test
    fun `the events of subgraphs that run in the same step each name their node`() =
        runTest {
            val one = StateGraph<TestState> { START then node("work") { it.copy(count = it.count + 1) } then END }.compile()
            val around =
                StateGraph<TestState> {
                    val left =
                        subgraph("left", one, state = { it }, update = { state, result ->
                            state.copy(
                                count =
                                    state.count + result.count,
                            )
                        })
                    val right =
                        subgraph("right", one, state = { it }, update = { state, result ->
                            state.copy(
                                count =
                                    state.count + result.count,
                            )
                        })

                    START then left
                    START then right
                }.compile()

            val events = around.stream(TestState(0)).toList()

            for (name in listOf("left", "right")) {
                assertEquals(
                    listOf(
                        GraphEvent.NodeStarted(1, "work", TestState(0)),
                        GraphEvent.NodeCompleted(1, "work", TestState(1)),
                        GraphEvent.StepCompleted(1, listOf("work"), TestState(1)),
                        GraphEvent.Completed(TestState(1)),
                    ),
                    events.filterIsInstance<GraphEvent.SubgraphEvent<TestState>>().filter { it.node == name }.map { it.event },
                )
            }
            assertEquals(GraphEvent.Completed(TestState(2)), events.last())
        }

    @Test
    fun `the events of a nested subgraph are wrapped once for every graph around it`() =
        runTest {
            val inner = StateGraph<TestState> { START then node("leaf") { it.copy(count = it.count + 1) } then END }.compile()
            val middle = StateGraph<TestState> { START then subgraph("inner", inner) then END }.compile()
            val outer = StateGraph<TestState> { START then subgraph("middle", middle) then END }.compile()

            val nested =
                outer
                    .stream(TestState(0))
                    .toList()
                    .filterIsInstance<GraphEvent.SubgraphEvent<TestState>>()
                    .first { it.innermost == GraphEvent.NodeStarted(1, "leaf", TestState(0)) }

            assertEquals(
                GraphEvent.SubgraphEvent(
                    1,
                    "middle",
                    GraphEvent.SubgraphEvent(1, "inner", GraphEvent.NodeStarted(1, "leaf", TestState(0)), TestState(0)),
                    TestState(0),
                ),
                nested,
            )
            assertEquals(listOf("middle", "inner"), nested.path)
        }

    @Test
    fun `a subgraph that is not streamed runs without events`() =
        runTest {
            var collected: Boolean? = null
            val inner =
                StateGraph<TestState> {
                    START then
                        node("look") {
                            collected = isProgressCollected()
                            it
                        } then END
                }.compile()
            val around = StateGraph<TestState> { START then subgraph("inner", inner) then END }.compile()

            around.invoke(TestState(0))
            assertEquals(false, collected)

            around.stream(TestState(0)).toList()
            assertEquals(true, collected)
        }

    @Test
    fun `a run that paused inside a subgraph does not pause before it again`() =
        runTest {
            val both = config.copy(interruptBefore = setOf("research"))

            assertEquals(listOf("research"), assertIs<GraphResult.Interrupted<Request>>(app.invoke(Request("wasm"), both)).nextNodes)
            assertEquals(asked, app.resume(both).state.research)
            assertIs<GraphResult.Completed<Request>>(app.resume(both) { it.copy(research = it.research.copy(approved = true)) })
            assertEquals(1, searches)
        }

    @Test
    fun `the other nodes of the step run again and the subgraph continues`() =
        runTest {
            var stamps = 0
            val parallel =
                StateGraph<Request> {
                    val stamp =
                        node("stamp", work = { stamps++ }) { order, _ -> order.copy(stamped = true) }
                    val research =
                        subgraph(
                            "research",
                            researchGraph,
                            state = { it.research },
                            update = { order, research -> order.copy(research = research) },
                        )
                    // "stamp" is first, so it has finished when the subgraph pauses.
                    START then stamp
                    START then research
                }.compile()
            val start = Request("wasm", research = Study("wasm"))

            val paused = parallel.invoke(start, config)

            assertEquals(GraphResult.Interrupted(start.copy(research = asked), listOf("stamp", "research")), paused)

            val finished = parallel.resume(config) { it.copy(research = it.research.copy(approved = true)) }

            assertEquals(listOf("found wasm", "approved"), finished.state.research.notes)
            assertEquals(true, finished.state.stamped)
            assertEquals(2, stamps)
            assertEquals(1, searches)
        }

    @Test
    fun `a subgraph with the state type of the graph around needs no mapping`() =
        runTest {
            val ask =
                StateGraph<TestState> {
                    val add = node("add") { it.copy(count = it.count + 1) }
                    val ask = node("ask") { if (it.count < 100) interrupt(it) else it }

                    START then add then ask then END
                }.compile()
            val around =
                StateGraph<TestState> {
                    START then node("before") { it.copy(count = it.count + 10) } then subgraph("inner", ask) then
                        node("after") { it.copy(count = it.count * 2) } then END
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertEquals(GraphResult.Interrupted(TestState(11), listOf("inner")), around.invoke(TestState(0), config))
            assertEquals(GraphResult.Completed(TestState(222)), around.resume(config) { it.copy(count = it.count + 100) })
        }

    @Test
    fun `two subgraphs that return a whole state need a reducer to run together`() {
        val inner = StateGraph<TestState> { START then node("add") { it.copy(count = it.count + 1) } then END }.compile()

        assertFailsWith<GraphValidationException> {
            StateGraph<TestState> {
                START then subgraph("one", inner)
                START then subgraph("two", inner)
            }.compile()
        }
    }

    @Test
    fun `subgraphs can be nested and a pause in the innermost one is resumed there`() =
        runTest {
            // Every node adds to its own digit, so the count shows how often each one ran.
            val inner =
                StateGraph<TestState> {
                    val a = node("a") { it.copy(count = it.count + 1) }
                    val ask = node("ask") { if (it.count < 1000) interrupt(it) else it }
                    val b = node("b") { it.copy(count = it.count + 10) }

                    START then a then ask then b then END
                }.compile()
            val middle =
                StateGraph<TestState> {
                    START then node("m") { it.copy(count = it.count + 100) } then subgraph("inner", inner) then END
                }.compile()
            val outer =
                StateGraph<TestState> {
                    START then subgraph("middle", middle) then END
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer)

            assertEquals(GraphResult.Interrupted(TestState(101), listOf("middle")), outer.invoke(TestState(0), config))
            val innerPosition = SubgraphPosition(listOf("ask"), step = 1, interruptedBefore = true)
            val middlePosition =
                SubgraphPosition(
                    listOf("inner"),
                    step = 1,
                    interruptedBefore = true,
                    subgraphs =
                        mapOf(
                            "inner" to innerPosition,
                        ),
                )
            assertEquals(
                Checkpoint(
                    TestState(101),
                    listOf("middle"),
                    step = 0,
                    interruptedBefore = true,
                    subgraphs =
                        mapOf(
                            "middle" to middlePosition,
                        ),
                ),
                checkpointer.load("default"),
            )

            assertEquals(GraphResult.Completed(TestState(1111)), outer.resume(config) { it.copy(count = it.count + 1000) })
        }

    @Test
    fun `a subgraph in a loop starts from its first node on every visit`() =
        runTest {
            val looping =
                StateGraph<Request> {
                    val research =
                        subgraph("research", researchGraph, state = { it.research }, update = { order, research ->
                            order.copy(research = research)
                        })
                    START then research
                    // Twice: until the subgraph has searched a second time.
                    conditionalEdge(research, targets = setOf(research, NodeRef.END)) {
                        if (it.research.notes.count { note -> note.startsWith("found") } < 2) research else NodeRef.END
                    }
                }.compile()

            looping.invoke(Request("wasm", research = Study("wasm")), config)
            val finished = looping.resume(config) { it.copy(research = it.research.copy(approved = true)) }

            // The second visit found the answer of the first one in its state and did not ask again.
            assertEquals(listOf("found wasm", "approved", "found wasm", "approved"), finished.state.research.notes)
            assertEquals(2, searches)
        }

    @Test
    fun `a failure inside a subgraph is the cause of the failure of its node`() =
        runTest {
            val failing = StateGraph<Study> { START then node("search") { error("no network") } then END }.compile()
            val around =
                StateGraph<Request> {
                    START then subgraph("research", failing, state = { it.research }, update = { order, _ -> order }) then END
                }.compile()

            val exception = assertFailsWith<NodeExecutionException> { around.invoke(Request("wasm")) }

            assertEquals("research", exception.nodeName)
            assertEquals("search", assertIs<NodeExecutionException>(exception.cause).nodeName)
        }

    @Test
    fun `a pause inside a subgraph needs a checkpointer`() =
        runTest {
            val exception = assertFailsWith<NodeExecutionException> { app.invoke(Request("wasm")) }

            assertEquals("research", exception.nodeName)
            assertIs<GraphValidationException>(exception.cause)
        }

    @Test
    fun `the step limit counts the steps of a subgraph on their own`() =
        runTest {
            val threeSteps =
                StateGraph<TestState> {
                    val add = node("add") { it.copy(count = it.count + 1) }
                    START then add
                    conditionalEdge(add, targets = setOf(add, NodeRef.END)) { if (it.count % 3 == 0) NodeRef.END else add }
                }.compile()
            val around =
                StateGraph<TestState> {
                    START then subgraph("first", threeSteps) then subgraph("second", threeSteps) then
                        node("last") { it } then END
                }.compile()

            // Three steps around and three in each subgraph: none of the graphs is over the limit.
            assertEquals(GraphResult.Completed(TestState(6)), around.invoke(TestState(0), GraphConfig(maxIterations = 3)))

            val exception = assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), GraphConfig(maxIterations = 2)) }
            assertEquals("first", exception.nodeName)
            assertIs<MaxIterationsExceededException>(exception.cause)
        }

    @Test
    fun `resume rejects a checkpoint that stands in a subgraph the graph does not have`() =
        runTest {
            val position = SubgraphPosition(listOf("ask"), step = 1, interruptedBefore = true)
            val state = Request("wasm", research = asked)

            // "reply" is a node, but not a subgraph.
            checkpointer.save(
                "t",
                Checkpoint(
                    state,
                    listOf("reply"),
                    step = 2,
                    interruptedBefore = true,
                    subgraphs =
                        mapOf(
                            "reply" to position,
                        ),
                ),
            )
            assertFailsWith<GraphValidationException> { app.resume(config) }

            // The subgraph is not among the nodes the run continues with.
            checkpointer.save(
                "t",
                Checkpoint(
                    state,
                    listOf("reply"),
                    step = 2,
                    interruptedBefore = true,
                    subgraphs =
                        mapOf(
                            "research" to position,
                        ),
                ),
            )
            assertFailsWith<GraphValidationException> { app.resume(config) }

            // The subgraph has no node "removed".
            val removed = mapOf("research" to position.copy(nextNodes = listOf("removed")))
            checkpointer.save("t", Checkpoint(state, listOf("research"), step = 1, interruptedBefore = true, subgraphs = removed))
            assertFailsWith<GraphValidationException> { app.resume(config) }
        }

    @Test
    fun `a subgraph follows the rules for node names`() {
        assertFailsWith<GraphValidationException> {
            StateGraph<Request> {
                node("research") { it }
                subgraph("research", researchGraph, state = { it.research }, update = { order, _ -> order })
            }
        }
    }
}
