package com.example.ptts.features.jump_session.presentation

object JumpSessionDefaults {
    const val InitialDurationSeconds = 60
    const val MinDurationSeconds = 10
    const val DurationStepSeconds = 10
}

fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}
