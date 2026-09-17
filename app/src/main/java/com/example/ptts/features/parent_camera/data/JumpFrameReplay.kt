package com.example.ptts.features.parent_camera.data

import com.example.ptts.features.parent_camera.domain.JumpCounter
import com.example.ptts.features.parent_camera.domain.JumpCounterResult
import com.example.ptts.features.parent_camera.domain.PoseFrame

/** Runs the production counter against a recorded landmark stream. */
class JumpFrameReplay(
    private val counterFactory: () -> JumpCounter = { JumpCounter() },
) {
    fun run(frames: Iterable<PoseFrame>): ReplayResult {
        val counter = counterFactory()
        val results = frames.map { counter.accept(it) }
        return ReplayResult(results = results, finalResult = results.lastOrNull())
    }
}

data class ReplayResult(
    val results: List<JumpCounterResult>,
    val finalResult: JumpCounterResult?,
)
