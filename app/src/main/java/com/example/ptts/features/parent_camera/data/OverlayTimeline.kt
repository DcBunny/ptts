package com.example.ptts.features.parent_camera.data

data class OverlayFrameState(
    val elapsedMs: Long,
    val remainingSeconds: Int,
    val jumpCount: Int,
)

class OverlayTimeline(states: List<OverlayFrameState>) {
    private val orderedStates = states
        .sortedBy { it.elapsedMs }
        .ifEmpty {
            throw IllegalArgumentException("OverlayTimeline requires at least one state")
        }

    fun stateAt(elapsedMs: Long): OverlayFrameState {
        var low = 0
        var high = orderedStates.lastIndex
        var resultIndex = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (orderedStates[mid].elapsedMs <= elapsedMs) {
                resultIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return orderedStates[resultIndex]
    }
}
