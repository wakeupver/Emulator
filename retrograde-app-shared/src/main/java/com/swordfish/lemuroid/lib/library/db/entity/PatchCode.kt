package com.swordfish.lemuroid.lib.library.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * Represents a single cheat / patch code entry tied to a specific game.
 *
 * The [code] field accepts formats understood by the libretro core:
 *   - Game Genie (NES/GB/GBC/GBA/SNES/Genesis…)
 *   - GameShark / Action Replay
 *   - Pro Action Replay
 *   - Raw address+value (some cores)
 *
 * Multiple codes for the same logical "cheat" should be separated by "+" as
 * RetroArch does (e.g. "DEAD BEEF+CAFE 1234").
 */
@Entity(
    tableName = "patch_codes",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("id", unique = true),
        Index("gameId"),
    ],
)
data class PatchCode(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** Foreign key to [Game.id]. */
    val gameId: Int,
    /** Human-readable description shown in the UI (e.g. "Infinite Lives"). */
    val description: String,
    /**
     * The raw code string passed to [retro_cheat_set].
     * Multiple sub-codes may be separated by '+'.
     */
    val code: String,
    /** Whether this cheat is currently active. */
    val enabled: Boolean = false,
) : Serializable
