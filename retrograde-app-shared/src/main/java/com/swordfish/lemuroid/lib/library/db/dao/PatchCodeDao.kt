package com.swordfish.lemuroid.lib.library.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.swordfish.lemuroid.lib.library.db.entity.PatchCode
import kotlinx.coroutines.flow.Flow

@Dao
interface PatchCodeDao {

    /** Observe all codes for a game (live, ordered by insertion id). */
    @Query("SELECT * FROM patch_codes WHERE gameId = :gameId ORDER BY id ASC")
    fun getCodesForGame(gameId: Int): Flow<List<PatchCode>>

    /** One-shot fetch used when applying codes at game start. */
    @Query("SELECT * FROM patch_codes WHERE gameId = :gameId ORDER BY id ASC")
    suspend fun getCodesForGameOnce(gameId: Int): List<PatchCode>

    /** Only the enabled codes – useful for applying on resume. */
    @Query("SELECT * FROM patch_codes WHERE gameId = :gameId AND enabled = 1 ORDER BY id ASC")
    suspend fun getEnabledCodesForGame(gameId: Int): List<PatchCode>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(code: PatchCode): Long

    @Update
    suspend fun update(code: PatchCode)

    @Delete
    suspend fun delete(code: PatchCode)

    /** Toggle enabled flag without loading the full object. */
    @Query("UPDATE patch_codes SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)

    @Query("DELETE FROM patch_codes WHERE gameId = :gameId")
    suspend fun deleteAllForGame(gameId: Int)
}
