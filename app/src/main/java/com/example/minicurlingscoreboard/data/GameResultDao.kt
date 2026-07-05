package com.example.minicurlingscoreboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameResultDao {
    @Insert
    suspend fun insert(result: GameResult)

    @Query("SELECT * FROM game_results ORDER BY playedAt DESC")
    fun getAll(): Flow<List<GameResult>>

    @Query("SELECT * FROM game_results WHERE id = :id")
    fun getById(id: Long): Flow<GameResult?>
}
