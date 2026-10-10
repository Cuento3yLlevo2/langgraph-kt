@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Throws an error of JavaScript, as a call to an API of the browser does when it fails. */
private fun throwTypeError(): Unit = js("(function () { throw new TypeError('No such element'); })()")

/** An error that JavaScript throws is not an `Exception` in Kotlin, and still a failure of the tool that ran into it. */
class JavaScriptErrorTest {
    @Test
    fun `an error of JavaScript in a tool gives an error result`() =
        runTest {
            val page =
                Tool<Unit>("page_title", "Returns the title of the page.") {
                    throwTypeError()
                    "Pixel Pizza"
                }

            val result = listOf(page).execute(listOf(ToolCall("a", "page_title", JsonObject(emptyMap())))).single()

            assertTrue(result.isError)
            assertEquals("page_title", result.toolName)
        }
}
