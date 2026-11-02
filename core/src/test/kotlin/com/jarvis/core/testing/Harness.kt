package com.jarvis.core.testing

import com.jarvis.core.engine.Dialog
import com.jarvis.core.lock.LockState
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Outcome
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.impl.ToolRegistry
import kotlinx.coroutines.runBlocking

/** Runs tools against the fakes in [TestEnv] and reports what REALLY happened. */
class ToolHarness(val env: TestEnv = TestEnv()) {
    val registry = ToolRegistry.default(env.services, settleMs = 0)
    val dialog = Dialog(env.clock)

    fun ctx(lock: LockState = LockState.UNLOCKED, origin: Origin = Origin.TEXT) = ToolContext(env.services, origin, lock, dialog)

    fun run(name: String, args: Map<String, Any?> = emptyMap(), ctx: ToolContext = ctx()): Outcome =
        runBlocking { registry.get(name)!!.execute(Args(args), ctx) }

    fun run(name: String, vararg args: Pair<String, Any?>): Outcome = run(name, mapOf(*args))

    /** Answer a pending question like the user would. */
    fun answer(text: String) = runBlocking { dialog.resume(text) }
}
