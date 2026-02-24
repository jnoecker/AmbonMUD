package dev.ambon.swarm

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginFlowInterpreterTest {
    private val credential = BotCredential(name = "swarm_0001", password = "swarmpass")

    @Test
    fun `handles create-account prompt sequence`() {
        val interpreter = LoginFlowInterpreter(credential)

        assertEquals(LoginSignal.SEND_NAME, interpreter.onLine("Enter your name:"))
        assertEquals(
            LoginSignal.SEND_YES,
            interpreter.onLine("No user named 'swarm_0001' was found. Create a new user? (yes/no)"),
        )
        assertEquals(LoginSignal.SEND_PASSWORD, interpreter.onLine("Create a password:"))
        assertEquals(LoginSignal.SUCCESS, interpreter.onLine("Exits: north, south"))
    }

    @Test
    fun `handles existing-account prompt and ansi wrapped text`() {
        val interpreter = LoginFlowInterpreter(credential)

        assertEquals(LoginSignal.SEND_NAME, interpreter.onLine("\u001B[2m\u001B[96mEnter your name:\u001B[0m"))
        assertEquals(LoginSignal.SEND_PASSWORD, interpreter.onLine("Password:"))
        assertEquals(LoginSignal.SUCCESS, interpreter.onLine("\u001B[2m\u001B[96mAlso online: Alice\u001B[0m"))
    }
}
