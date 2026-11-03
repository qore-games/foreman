package games.qore.foreman.client.mcp

import com.mojang.brigadier.arguments.IntegerArgumentType
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.client.Minecraft
import java.util.concurrent.TimeUnit

/**
 * Who is driving the client, so two agents don't test at once: a lease, not a lock. The first call of a tool that acts
 * (commands, input, the camera, screens, tests…) takes it for its MCP session; it lasts while that session keeps
 * calling, until [IMPLICIT_MS] after its last call (an idle limit of its choosing with `client_lock acquire`), and goes
 * at once with a release or when the session ends. Other sessions' acting calls are refused meanwhile, with who has it
 * and until when; tools that only look stay free. Nothing is saved: a restart frees it, so it can't go stale. The
 * player takes the client back with `/foreman-lock take` (agents are refused until `/foreman-lock free`).
 */
object ClientLock {
    /** A lease taken by a call, without client_lock: two minutes after its holder's last call */
    private const val IMPLICIT_MS = 120_000L
    private const val MAX_MINUTES = 30
    private const val PLAYER_MINUTES = 30

    /** Who calls: their MCP session, or the path they call without one. */
    class Holder(val key: String, val name: String)

    private val PLAYER = Holder("player", "the player")

    private class Lease(val holder: Holder, var label: String?, var idleMs: Long, var explicit: Boolean) {
        var last = System.nanoTime()
        /** Calls of the holder running now: no expiry meanwhile */
        var busy = 0
        fun expired(now: Long) = busy == 0 && now - last > TimeUnit.MILLISECONDS.toNanos(idleMs)
        fun leftMs(now: Long) = idleMs - TimeUnit.NANOSECONDS.toMillis(now - last)
    }

    private val monitor = Object()
    private var lease: Lease? = null

    /** The holder of the call running on this thread (set by ClientMcp around each call), for client_lock. */
    val caller = ThreadLocal<Holder?>()

    fun init() {
        ClientMcp.register(ClientTool(
            "client_lock",
            "Who drives the client, so two agents don't test at once. Any call of a tool that acts (commands, input, " +
                "the camera, screens, tests, recordings…) takes the client for your session if it's free and keeps it " +
                "2 minutes past your last call; while another session has it, those calls are refused (tools that only " +
                "look aren't). `status` (default): who has it and until when. `acquire` it for a longer session " +
                "(`label`: what you're doing, `minutes` without a call before it goes, default 10, max $MAX_MINUTES), " +
                "`release` it when done, or `wait` until it's free (`seconds`, default 120, max 600) and take it.",
            ClientTools.schema(
                "action" to "string: status, acquire, release or wait",
                "label" to "string: what you're doing, shown to whoever is refused and to the player",
                "minutes" to "integer: with acquire or wait, how long it lasts without a call (default 10)",
                "seconds" to "integer: with wait, how long to wait at most (default 120)",
            ),
            acts = { false },
        ) { _, args ->
            val holder = caller.get() ?: throw ToolFailure("No caller")
            val minutes = args["minutes"].int()?.coerceIn(1, MAX_MINUTES)
            val text = when (args["action"].str() ?: "status") {
                "status" -> status(holder)
                "acquire" -> acquire(holder, args["label"].str(), (minutes ?: 10) * 60_000L)
                "release" -> release(holder)
                "wait" -> wait(holder, args["label"].str(), minutes?.let { it * 60_000L }, (args["seconds"].int() ?: 120).coerceIn(1, 600))
                else -> throw ToolFailure("action: status, acquire, release or wait")
            }
            listOf(ClientMcp.text(text))
        })

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("foreman-lock")
                .executes { Chat.info(status(PLAYER)); 1 }
                .then(literal("take").executes { Chat.info(take(PLAYER_MINUTES)); 1 }
                    .then(argument("minutes", IntegerArgumentType.integer(1, 24 * 60)).executes { Chat.info(take(IntegerArgumentType.getInteger(it, "minutes"))); 1 }))
                .then(literal("free").executes { Chat.info(free()); 1 }))
        }
        Keys.bind("lock-take", Keys.Category.AI, Keys.UNBOUND, "Take the client back from AI agents / let them use it again") {
            Chat.info(if (synchronized(monitor) { lease?.holder === PLAYER }) free() else take(PLAYER_MINUTES))
        }
    }

    /**
     * Before a call of [holder]: whether it counts as theirs (to pass to [exit] after), taking the client when it's
     * free and the call [acts]. Throws when another holder has it and the call acts.
     */
    fun enter(holder: Holder, acts: Boolean): Boolean = synchronized(monitor) {
        val now = System.nanoTime()
        val held = current(now)
        when {
            held?.holder?.key == holder.key -> { held.busy++; held.last = now; true }
            held != null -> if (acts) throw ToolFailure(refusal(held, now)) else false
            acts -> { lease = Lease(holder, null, IMPLICIT_MS, false).also { it.busy = 1 }; true }
            else -> false
        }
    }

    fun exit(holder: Holder) = synchronized(monitor) {
        lease?.takeIf { it.holder.key == holder.key }?.let { it.busy = maxOf(0, it.busy - 1); it.last = System.nanoTime() }
        monitor.notifyAll()
    }

    /** [holder]'s session ended (or went unknown): its lease goes. */
    fun ended(holder: Holder) = synchronized(monitor) {
        if (lease?.holder?.key == holder.key) {
            if (lease!!.explicit) announce("${describe(lease!!)} is done with the client (its session ended)")
            lease = null
            monitor.notifyAll()
        }
    }

    /** The lease now, null once expired. Under the monitor. */
    private fun current(now: Long): Lease? = lease?.takeIf { !it.expired(now) }.also { if (it == null) lease = null }

    private fun acquire(holder: Holder, label: String?, idleMs: Long): String = synchronized(monitor) {
        val now = System.nanoTime()
        val held = current(now)
        if (held != null && held.holder.key != holder.key) throw ToolFailure(refusal(held, now) + " (client_lock wait queues you)")
        val fresh = held == null || !held.explicit
        val mine = held ?: Lease(holder, null, idleMs, true).also { this.lease = it }
        mine.label = label ?: mine.label
        mine.idleMs = idleMs
        mine.explicit = true
        mine.last = now
        if (fresh) announce("${describe(mine)} is using the client: /foreman-lock take takes it back")
        "Yours: ${describe(mine)}, until ${idleMs / 60_000} min after your last call or client_lock release"
    }

    private fun release(holder: Holder): String = synchronized(monitor) {
        val held = current(System.nanoTime())
        when {
            held == null -> "Nobody had it"
            held.holder.key != holder.key -> "Not yours: ${describe(held)} has it"
            else -> {
                if (held.explicit) announce("${describe(held)} is done with the client")
                lease = null
                monitor.notifyAll()
                "Released"
            }
        }
    }

    /** Waits until the client is free, then takes it (for [idleMs] with client_lock's [label], else as a call would). */
    private fun wait(holder: Holder, label: String?, idleMs: Long?, seconds: Int): String {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(seconds.toLong())
        synchronized(monitor) {
            while (true) {
                val now = System.nanoTime()
                val held = current(now)
                if (held == null || held.holder.key == holder.key) {
                    val waited = TimeUnit.NANOSECONDS.toSeconds(now - started)
                    return if (label != null || idleMs != null) "After $waited s: " + acquire(holder, label, idleMs ?: 600_000L)
                    else {
                        if (held == null) lease = Lease(holder, null, IMPLICIT_MS, false)
                        "Free after $waited s: yours (2 minutes past your last call)"
                    }
                }
                if (now >= deadline) return "Still taken after $seconds s: " + refusal(held, now)
                // woken by a release or a call's end; else when it would expire
                val sleep = minOf(TimeUnit.NANOSECONDS.toMillis(deadline - now), if (held.busy > 0) 1000L else held.leftMs(now)).coerceAtLeast(50)
                monitor.wait(sleep)
            }
        }
    }

    private fun status(holder: Holder): String = synchronized(monitor) {
        val now = System.nanoTime()
        val held = current(now) ?: return "Free: the first call of a tool that acts takes it"
        // this status call is the holder's own: not one running
        if (held.holder.key == holder.key) "Yours: ${describe(held)}, ${until(held, now, held.busy - 1)}" else "Taken: " + refusal(held, now)
    }

    /** The player takes the client back for [minutes]: agents are refused until then or /foreman-lock free. */
    private fun take(minutes: Int): String = synchronized(monitor) {
        val had = current(System.nanoTime())?.takeIf { it.holder !== PLAYER }
        lease = Lease(PLAYER, "playing", minutes * 60_000L, true)
        monitor.notifyAll()
        "The client is yours for $minutes min" + (had?.let { " (taken from ${describe(it)})" } ?: "") + ": AI agents are refused until /foreman-lock free"
    }

    private fun free(): String = synchronized(monitor) {
        val mine = lease?.holder === PLAYER
        if (mine) lease = null
        monitor.notifyAll()
        if (mine) "AI agents may use the client again" else "It wasn't yours: " + status(PLAYER)
    }

    private fun refusal(held: Lease, now: Long): String =
        if (held.holder === PLAYER) "The player took the client back (${ago(held, now)}): ask them to /foreman-lock free"
        else "The client is in use by ${describe(held)}: last call ${ago(held, now)}, ${until(held, now)}"

    private fun until(held: Lease, now: Long, busy: Int = held.busy) =
        if (busy > 0) "a call running now" else "free in ${clock(held.leftMs(now))} unless it calls again or releases"

    private fun ago(held: Lease, now: Long) = if (held.busy > 0) "running now" else "${TimeUnit.NANOSECONDS.toSeconds(now - held.last)} s ago"

    private fun describe(lease: Lease) = lease.holder.name + (lease.label?.let { " («$it»)" } ?: "")

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** A chat line to the player. */
    private fun announce(text: String) = Minecraft.getInstance().execute { Chat.info(text) }
}
