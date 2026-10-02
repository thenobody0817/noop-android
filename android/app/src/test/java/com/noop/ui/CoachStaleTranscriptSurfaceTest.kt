package com.noop.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #2087: opening Coach on a new day must retire the previous day's transcript BEFORE the scheduled
 * brief is offered to it, on both platforms.
 *
 * This is wiring, not logic: [CoachConversationDayTest] already pins the day rule itself. A source
 * tripwire is the right instrument because the screen-level call sites compile perfectly while
 * omitting a step, and because the bug is invisible in any short-lived test: the persisted-message
 * load runs once per PROCESS, so only a process that survives midnight reaches the broken state.
 * Neither a JVM test nor an instrumented one can hold a ViewModel across a real day boundary.
 */
class CoachStaleTranscriptSurfaceTest {
    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
        return candidates.firstOrNull { File(it, "Strand/AI/AICoach.swift").isFile }
            ?: error("could not locate the repo root from ${userDir.absolutePath}")
    }

    private fun source(path: String): String = File(repoRoot(), path).readText()

    /** The body of a declaration, from its signature to the next closing brace at [indent]. */
    private fun body(src: String, signature: String, indent: String = "    "): String {
        val start = src.indexOf(signature)
        assertTrue("missing declaration: $signature", start >= 0)
        val end = src.indexOf("\n$indent}", start)
        assertTrue("unterminated declaration: $signature", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `android retires the stale transcript between the restore and the brief`() {
        val screen = source("android/app/src/main/java/com/noop/ui/CoachScreen.kt")
        val load = screen.indexOf("vm.loadPersistedMessagesIfNeeded()")
        val retire = screen.indexOf("vm.retireStaleConversationIfNeeded()")
        val brief = screen.indexOf("vm.consumeScheduledBriefIfAny(")
        assertTrue("the Coach screen must restore the persisted transcript", load >= 0)
        assertTrue("the Coach screen must retire a transcript from an earlier day", retire >= 0)
        assertTrue("the Coach screen must offer the scheduled brief", brief >= 0)
        assertTrue("retiring must follow the restore, or it retires nothing", load < retire)
        assertTrue("retiring must precede the brief, or the brief is refused", retire < brief)
    }

    /**
     * Parity: iOS keeps yesterday's assistant replies out of today's context too.
     *
     * REWRITTEN for this tree, deliberately not string-matched to upstream's shape. Upstream retires a
     * single persisted transcript from `CoachView` between a restore and the scheduled brief. This tree
     * keeps MULTIPLE conversations, so there is no one transcript to empty: retiring now opens a fresh
     * conversation and leaves what the user wrote in history. The guarantee the upstream test protected
     * is therefore not "these three calls, in this order" but "a new turn never sees an earlier day's
     * replies", which is asserted here against the code that actually carries it.
     */
    @Test
    fun `ios retires the stale conversation before a new turn`() {
        val coach = source("Strand/AI/AICoach.swift")
        val send = coach.indexOf("func send(_ userText: String) async {")
        val retire = coach.indexOf("retireStaleConversationIfNeeded()", send)
        val append = coach.indexOf("appendMessage(ChatMessage(role: .user", send)
        assertTrue("AICoach must have a send path", send >= 0)
        assertTrue("a new turn must retire an earlier day's conversation", retire >= 0)
        assertTrue("retiring must precede the user's message, or the turn is contaminated", retire < append)
    }

    /**
     * The fork's brief must not contaminate an active chat. Upstream could assert this by ordering the
     * retire call before the brief; here `surfaceScheduledBrief` carries the guard itself.
     */
    @Test
    fun `ios scheduled brief no-ops when a conversation already exists`() {
        val coach = source("Strand/AI/AICoach.swift")
        val brief = coach.indexOf("func surfaceScheduledBrief(_ text: String) {")
        val guard = coach.indexOf("guard messages.isEmpty else { return }", brief)
        val append = coach.indexOf("appendMessage(ChatMessage(role: .assistant", brief)
        assertTrue("AICoach must surface a scheduled brief", brief >= 0)
        assertTrue("the brief must refuse to join a conversation that already has messages", guard >= 0)
        assertTrue("the guard must precede the append it protects", guard < append)
    }

    /**
     * Retiring must not destroy what the wearer wrote. Upstream empties one transcript; this tree opens a
     * new conversation instead, so the stale one survives as history.
     */
    @Test
    fun `ios retiring opens a new conversation rather than discarding history`() {
        val coach = source("Strand/AI/AICoach.swift")
        val retire = coach.indexOf("func retireStaleConversationIfNeeded() {")
        val fresh = coach.indexOf("newConversation()", retire)
        assertTrue("AICoach must retire a stale conversation", retire >= 0)
        assertTrue("retiring must open a fresh conversation so history is not lost", fresh >= 0)
    }

    /**
     * A day whose only message is its brief must still date the transcript. Left null, the day rule
     * reads "nothing sent yet, never stale", so the brief-only transcript is never retired and the
     * NEXT day's brief is refused — the half of #2087 that reproduces without a typed turn.
     */
    @Test
    fun `both scheduled-brief paths date the transcript they create`() {
        val vm = body(
            source("android/app/src/main/java/com/noop/ui/CoachViewModel.kt"),
            "fun consumeScheduledBriefIfAny(",
        )
        assertTrue(
            "consumeScheduledBriefIfAny must set conversationDay",
            vm.contains("conversationDay = LocalDate.now().toEpochDay()"),
        )
        val engine = body(
            source("Strand/AI/AICoach.swift"),
            "func surfaceScheduledBrief(",
        )
        assertTrue(
            "surfaceScheduledBrief must set conversationDay",
            engine.contains("conversationDay = Self.localEpochDay()"),
        )
    }
}
