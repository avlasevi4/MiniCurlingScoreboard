package com.example.minicurlingscoreboard.data

data class PlayerStats(
    val name: String,
    val matches: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int
) {
    val winPercent: Int
        get() = if (matches > 0) wins * 100 / matches else 0
}
