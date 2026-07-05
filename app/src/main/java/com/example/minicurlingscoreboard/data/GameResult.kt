package com.example.minicurlingscoreboard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_results")
data class GameResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val player1Name: String,
    val player2Name: String,
    val player1ColorName: String,
    val player2ColorName: String,
    val score1: Int,
    val score2: Int,
    val durationMs: Long,
    val playedAt: Long,
    val baseEnds: Int,
    /** Per-end scores, encoded as "p1,p2;p1,p2;...", one entry per end actually played. */
    val endsData: String
)

internal fun encodeEnds(ends: List<Pair<Int, Int>>): String =
    ends.joinToString(";") { "${it.first},${it.second}" }

internal fun decodeEnds(data: String): List<Pair<Int, Int>> {
    if (data.isBlank()) return emptyList()
    return data.split(";").map { entry ->
        val (p1, p2) = entry.split(",")
        p1.toInt() to p2.toInt()
    }
}
