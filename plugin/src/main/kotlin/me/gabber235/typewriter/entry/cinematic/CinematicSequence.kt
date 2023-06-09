package me.gabber235.typewriter.entry.cinematic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.gabber235.typewriter.entry.entries.CinematicAction
import me.gabber235.typewriter.entry.entries.CinematicEntry
import me.gabber235.typewriter.entry.entries.SystemTrigger.CINEMATIC_END
import me.gabber235.typewriter.entry.triggerEntriesFor
import me.gabber235.typewriter.entry.triggerFor
import me.gabber235.typewriter.events.AsyncCinematicEndEvent
import me.gabber235.typewriter.events.AsyncCinematicTickEvent
import org.bukkit.entity.Player

private const val STARTING_FRAME = -1
private const val ENDED_FRAME = -2

class CinematicSequence(
    private val player: Player,
    private val entries: List<CinematicEntry>,
    private val triggers: List<String>
) {
    private var frame = STARTING_FRAME
    private var actions = emptyList<CinematicAction>()

    suspend fun start() {
        if (frame > STARTING_FRAME) return
        actions = entries.map { it.create(player) }
        actions.forEach {
            try {
                it.setup()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun tick() {
        if (frame == ENDED_FRAME) return
        if (frame == STARTING_FRAME) start()
        if (canEnd) return

        frame++
        actions.forEach {
            try {
                it.tick(frame)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        AsyncCinematicTickEvent(player, frame).callEvent()

        if (canEnd) {
            CINEMATIC_END triggerFor player
        }
    }

    private val canEnd get() = actions.all { it.canFinish(frame) }

    suspend fun end(force: Boolean = false) {
        if (frame == ENDED_FRAME || frame == STARTING_FRAME) return
        val originalFrame = frame
        frame = ENDED_FRAME
        actions.forEach {
            try {
                it.teardown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!force) {
            triggers triggerEntriesFor player
        }

        withContext(Dispatchers.IO) {
            AsyncCinematicEndEvent(player, originalFrame).callEvent()
        }
    }
}