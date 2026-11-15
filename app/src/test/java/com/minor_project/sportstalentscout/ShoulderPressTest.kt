package com.minor_project.sportstalentscout

import com.minor_project.sportstalentscout.ml.ExerciseAnalyzer
import com.minor_project.sportstalentscout.ml.LandmarkPoint
import com.minor_project.sportstalentscout.ml.PoseLandmark
import com.minor_project.sportstalentscout.ml.RepCountState
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoulderPressTest {

    // Helper to generate a landmark list for shoulder press
    // isOverhead: true for overhead lockout (~140° - 160°), false for shoulder rack position (~85°)
    // armsDownAtHips: true if arms are resting straight down at sides
    private fun createShoulderPressPose(
        rightOverhead: Boolean,
        leftOverhead: Boolean,
        armsDownAtHips: Boolean = false
    ): List<LandmarkPoint?> {
        val landmarks = MutableList<LandmarkPoint?>(33) { null }

        // Hips at Y = 0.75
        landmarks[PoseLandmark.RIGHT_HIP] = LandmarkPoint(0.38f, 0.75f)
        landmarks[PoseLandmark.LEFT_HIP]  = LandmarkPoint(0.62f, 0.75f)

        if (armsDownAtHips) {
            // Arms hanging straight down at hips (wrist.y > mid-torso)
            landmarks[PoseLandmark.RIGHT_SHOULDER] = LandmarkPoint(0.35f, 0.35f)
            landmarks[PoseLandmark.RIGHT_ELBOW]    = LandmarkPoint(0.35f, 0.55f)
            landmarks[PoseLandmark.RIGHT_WRIST]    = LandmarkPoint(0.35f, 0.75f)

            landmarks[PoseLandmark.LEFT_SHOULDER]  = LandmarkPoint(0.65f, 0.35f)
            landmarks[PoseLandmark.LEFT_ELBOW]     = LandmarkPoint(0.65f, 0.55f)
            landmarks[PoseLandmark.LEFT_WRIST]     = LandmarkPoint(0.65f, 0.75f)
            return landmarks
        }

        // Right side
        val rShoulder = LandmarkPoint(0.35f, 0.40f)
        if (rightOverhead) {
            // Extended overhead (~145° - 160°)
            landmarks[PoseLandmark.RIGHT_SHOULDER] = rShoulder
            landmarks[PoseLandmark.RIGHT_ELBOW]    = LandmarkPoint(0.32f, 0.22f)
            landmarks[PoseLandmark.RIGHT_WRIST]    = LandmarkPoint(0.30f, 0.04f)
        } else {
            // Bent at shoulder height (~85°)
            landmarks[PoseLandmark.RIGHT_SHOULDER] = rShoulder
            landmarks[PoseLandmark.RIGHT_ELBOW]    = LandmarkPoint(0.20f, 0.40f)
            landmarks[PoseLandmark.RIGHT_WRIST]    = LandmarkPoint(0.22f, 0.22f)
        }

        // Left side
        val lShoulder = LandmarkPoint(0.65f, 0.40f)
        if (leftOverhead) {
            landmarks[PoseLandmark.LEFT_SHOULDER] = lShoulder
            landmarks[PoseLandmark.LEFT_ELBOW]    = LandmarkPoint(0.68f, 0.22f)
            landmarks[PoseLandmark.LEFT_WRIST]    = LandmarkPoint(0.70f, 0.04f)
        } else {
            landmarks[PoseLandmark.LEFT_SHOULDER] = lShoulder
            landmarks[PoseLandmark.LEFT_ELBOW]    = LandmarkPoint(0.80f, 0.40f)
            landmarks[PoseLandmark.LEFT_WRIST]    = LandmarkPoint(0.78f, 0.22f)
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
        // Average angle is ~115° < 130°, so must NOT count as a rep!
        assertEquals(0, state.counter)
        assertEquals("DOWN", state.stage)
    }

    @Test
    fun testFourProperRepsOneSingleArmOneProperCountsFiveReps() {
        val signal = mutableListOf<Double>()

        fun addPressWave(peakAngle: Double) {
            repeat(4) { signal.add(85.0) }
            signal.add(95.0); signal.add(110.0); signal.add(125.0)
            repeat(5) { signal.add(peakAngle) }
            signal.add(125.0); signal.add(110.0); signal.add(95.0)
            repeat(4) { signal.add(85.0) }
        }

        // Reps 1, 2, 3, 4: 4 Proper full presses (peaking at 138° >= 130°)
        addPressWave(138.0)
        addPressWave(140.0)
        addPressWave(136.0)
        addPressWave(138.0)

        // 1 Single-arm press (one hand to 138°, one at 85° -> average = 111.5° < 130°)
        addPressWave(111.5)

        // Rep 5: 1 Proper full press (peaking at 138°)
        addPressWave(138.0)

        val repCount = ExerciseAnalyzer.countRepsFromSignal("Shoulder Press", signal)
        // Must count EXACTLY 5 reps, properly excluding the single-arm press!
        assertEquals(5, repCount)
    }
}
