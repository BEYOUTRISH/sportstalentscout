package com.minor_project.sportstalentscout

import com.minor_project.sportstalentscout.ml.ExerciseAnalyzer
import com.minor_project.sportstalentscout.ml.LandmarkPoint
import com.minor_project.sportstalentscout.ml.PoseLandmark
import com.minor_project.sportstalentscout.ml.RepCountState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShoulderPressTest {

    // Helper to generate a landmark list for shoulder press
    // isOverhead: true for overhead lockout (~160°), false for shoulder rack position (~85°)
    // armsDownAtHips: true if arms are resting straight down at sides
    private fun createShoulderPressPose(
        rightOverhead: Boolean,
        leftOverhead: Boolean,
        armsDownAtHips: Boolean = false
    ): List<LandmarkPoint?> {
        val landmarks = MutableList<LandmarkPoint?>(33) { null }

        if (armsDownAtHips) {
            // Arms hanging straight down at hips (wrist.y > shoulder.y)
            landmarks[PoseLandmark.RIGHT_SHOULDER] = LandmarkPoint(0.35f, 0.30f)
            landmarks[PoseLandmark.RIGHT_ELBOW]    = LandmarkPoint(0.35f, 0.50f)
            landmarks[PoseLandmark.RIGHT_WRIST]    = LandmarkPoint(0.35f, 0.70f)

            landmarks[PoseLandmark.LEFT_SHOULDER]  = LandmarkPoint(0.65f, 0.30f)
            landmarks[PoseLandmark.LEFT_ELBOW]     = LandmarkPoint(0.65f, 0.50f)
            landmarks[PoseLandmark.LEFT_WRIST]     = LandmarkPoint(0.65f, 0.70f)
            return landmarks
        }

        // Right side
        val rShoulder = LandmarkPoint(0.35f, 0.45f)
        if (rightOverhead) {
            // Extended overhead: elbow nearly straight (~165°)
            landmarks[PoseLandmark.RIGHT_SHOULDER] = rShoulder
            landmarks[PoseLandmark.RIGHT_ELBOW]    = LandmarkPoint(0.32f, 0.25f)
            landmarks[PoseLandmark.RIGHT_WRIST]    = LandmarkPoint(0.30f, 0.05f)
        } else {
            // Bent at shoulder height: elbow bent (~85°)
            landmarks[PoseLandmark.RIGHT_SHOULDER] = rShoulder
            landmarks[PoseLandmark.RIGHT_ELBOW]    = LandmarkPoint(0.20f, 0.45f)
            landmarks[PoseLandmark.RIGHT_WRIST]    = LandmarkPoint(0.22f, 0.25f)
        }

        // Left side
        val lShoulder = LandmarkPoint(0.65f, 0.45f)
        if (leftOverhead) {
            landmarks[PoseLandmark.LEFT_SHOULDER] = lShoulder
            landmarks[PoseLandmark.LEFT_ELBOW]    = LandmarkPoint(0.68f, 0.25f)
            landmarks[PoseLandmark.LEFT_WRIST]    = LandmarkPoint(0.70f, 0.05f)
        } else {
            landmarks[PoseLandmark.LEFT_SHOULDER] = lShoulder
            landmarks[PoseLandmark.LEFT_ELBOW]    = LandmarkPoint(0.80f, 0.45f)
            landmarks[PoseLandmark.LEFT_WRIST]    = LandmarkPoint(0.78f, 0.25f)
        }

        return landmarks
    }

    @Test
    fun testThreeShoulderPressRepsCountAccurately() {
        var state = RepCountState()

        for (rep in 1..3) {
            // Hands at shoulders (rack position)
            state = ExerciseAnalyzer.countShoulderPress(
                createShoulderPressPose(rightOverhead = false, leftOverhead = false),
                state
            )
            assertEquals("DOWN", state.stage)

            // Press overhead (lockout)
            state = ExerciseAnalyzer.countShoulderPress(
                createShoulderPressPose(rightOverhead = true, leftOverhead = true),
                state
            )
            assertEquals("UP", state.stage)
            assertEquals(rep, state.counter)

            // Lower back to shoulders
            state = ExerciseAnalyzer.countShoulderPress(
                createShoulderPressPose(rightOverhead = false, leftOverhead = false),
                state
            )
            assertEquals("DOWN", state.stage)
        }

        assertEquals(3, state.counter)
    }

    @Test
    fun testArmsDownAtSidesDoesNotTriggerShoulderPress() {
        var state = RepCountState()

        // Athlete stands with arms hanging down at sides
        for (i in 1..5) {
            state = ExerciseAnalyzer.countShoulderPress(
                createShoulderPressPose(rightOverhead = false, leftOverhead = false, armsDownAtHips = true),
                state
            )
            // Must stay DOWN and 0 reps!
            assertEquals(0, state.counter)
            assertEquals("DOWN", state.stage)
        }
    }

    @Test
    fun testSingleArmShoulderPressDoesNotCountAsRep() {
        var state = RepCountState()

        // Start at shoulders
        state = ExerciseAnalyzer.countShoulderPress(
            createShoulderPressPose(rightOverhead = false, leftOverhead = false),
            state
        )
        assertEquals("DOWN", state.stage)

        // Only press Right arm overhead, while Left arm stays at shoulder
        state = ExerciseAnalyzer.countShoulderPress(
            createShoulderPressPose(rightOverhead = true, leftOverhead = false),
            state
        )
        // Average angle is ~125° < 145°, so must NOT count as a rep!
        assertEquals(0, state.counter)
        assertEquals("DOWN", state.stage)
    }

    @Test
    fun testSignalAnalysisWithThreeFullPressesAndOneHalfPress() {
        val signal = mutableListOf<Double>()

        fun addPressWave(peakAngle: Double) {
            // At shoulders (85°)
            repeat(4) { signal.add(85.0) }
            // Pressing up: 100°, 120°, 135°
            signal.add(100.0); signal.add(120.0); signal.add(135.0)
            // Peak overhead:
            repeat(5) { signal.add(peakAngle) }
            // Lowering down: 135°, 120°, 100°
            signal.add(135.0); signal.add(120.0); signal.add(100.0)
            // Return to shoulders: 85°
            repeat(4) { signal.add(85.0) }
        }

        // 3 Full reps (peaking at 155°, 160°, 162° >= 145°)
        addPressWave(155.0)
        addPressWave(160.0)
        addPressWave(162.0)

        // 1 Half press (peaking at 122° — incomplete overhead lockout)
        addPressWave(122.0)

        val repCount = ExerciseAnalyzer.countRepsFromSignal("Shoulder Press", signal)
        // Must count EXACTLY 3 reps, ignoring the incomplete half-press!
        assertEquals(3, repCount)
    }
}
