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

    @Query(
        """
        SELECT name,
               COUNT(*) AS matches,
               SUM(won) AS wins,
               SUM(lost) AS losses,
               SUM(draw) AS draws
        FROM (
            SELECT player1Name AS name,
                   CASE WHEN score1 > score2 THEN 1 ELSE 0 END AS won,
                   CASE WHEN score1 < score2 THEN 1 ELSE 0 END AS lost,
                   CASE WHEN score1 = score2 THEN 1 ELSE 0 END AS draw
            FROM game_results
            UNION ALL
            SELECT player2Name AS name,
                   CASE WHEN score2 > score1 THEN 1 ELSE 0 END AS won,
                   CASE WHEN score2 < score1 THEN 1 ELSE 0 END AS lost,
                   CASE WHEN score1 = score2 THEN 1 ELSE 0 END AS draw
            FROM game_results
        )
        GROUP BY name
        ORDER BY (wins * 1.0 / matches) DESC, matches DESC
        """
    )
    fun getPlayerStats(): Flow<List<PlayerStats>>
}
