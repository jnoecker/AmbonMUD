package dev.ambon.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobSystemTest {
    @Test
    fun `tick always returns 0 - movement is driven by BehaviorTreeSystem`() =
        runTest {
            val system = MobSystem()
            assertEquals(0, system.tick())
            assertEquals(0, system.tick())
        }
}
