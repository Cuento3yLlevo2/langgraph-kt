@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar

import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Throws an error of JavaScript, as a call to an API of the browser does when it fails. */
private fun throwTypeError(): Unit = js("(function () { throw new TypeError('No such element'); })()")

/** An error that JavaScript throws is not an `Exception` in Kotlin, and still a failure of the code that ran into it. */
class JavaScriptErrorTest {
    @Test
    fun `an error of JavaScript fails the node`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("page") {
                        throwTypeError()
                        it
                    }
                    edge(START, "page")
                    edge("page", END)
                }.compile()

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }

            assertEquals("page", exception.nodeName)
        }

    @Test
    fun `an error of JavaScript fails a condition`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it }
                    edge(START, "a")
                    conditionalEdge("a", targets = setOf(END)) {
                        throwTypeError()
                        END
                    }
                }.compile()

            assertEquals("a", assertFailsWith<EdgeConditionException> { app.invoke(TestState()) }.from)
        }
}
