package com.minor_project.sportstalentscout

import com.minor_project.sportstalentscout.ml.ExerciseAnalyzer
import com.minor_project.sportstalentscout.ml.LandmarkPoint
import com.minor_project.sportstalentscout.ml.PoseLandmark
import com.minor_project.sportstalentscout.ml.RepCountState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BicepCurlTest {

    // Helper to generate a landmark list with given elbow angle in degrees
    private fun createPoseWithAngle(rightAngleDeg: Double, leftAngleDeg: Double): List<LandmarkPoint?> {
        val landmarks = MutableList<LandmarkPoint?>(33) { null }

        // Right side: Shoulder at (0.35, 0.3), Elbow at (0.35, 0.5)
        val rShoulder = LandmarkPoint(0.35f, 0.3f)
        val rElbow    = LandmarkPoint(0.35f, 0.5f)
        val rRad = Math.toRadians(rightAngleDeg)
        val rWrist = LandmarkPoint(
            (0.35f + 0.2f * sin(rRad)).toFloat(),
            (0.5f - 0.2f * cos(rRad)).toFloat()
        )

        // Left side: Shoulder at (0.65, 0.3), Elbow at (0.65, 0.5)
        val lShoulder = LandmarkPoint(0.65f, 0.3f)
        val lElbow    = LandmarkPoint(0.65f, 0.5f)
        val lRad = Math.toRadians(leftAngleDeg)
        val lWrist = LandmarkPoint(
            (0.65f - 0.2f * sin(lRad)).toFloat(),
            (0.5f - 0.2f * cos(lRad)).toFloat()
        )

        landmarks[PoseLandmark.RIGHT_SHOULDER] = rShoulder
        landmarks[PoseLandmark.RIGHT_ELBOW]    = rElbow
        landmarks[PoseLandmark.RIGHT_WRIST]    = rWrist

        landmarks[PoseLandmark.LEFT_SHOULDER]  = lShoulder
        landmarks[PoseLandmark.LEFT_ELBOW]     = lElbow
        landmarks[PoseLandmark.LEFT_WRIST]     = lWrist

        return landmarks
    }

    @Test
    fun testThreeBicepCurlRepsCountAccurately() {
        var state = RepCountState()

        // 3 full reps with both arms
        for (rep in 1..3) {
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(155.0, 155.0), state)
            assertEquals("DOWN", state.stage)

            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(110.0, 110.0), state)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(85.0, 85.0), state)

            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(55.0, 55.0), state)
            assertEquals(rep, state.counter) // Exactly 1 increment per curl, never 2!
            assertEquals("UP", state.stage)

            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(95.0, 95.0), state)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(140.0, 140.0), state)
            assertEquals("DOWN", state.stage)
        }

        assertEquals(3, state.counter)
    }

    @Test
    fun testThreeFullRepsAndOneHalfPeakCountsExactlyThreeReps() {
        var state = RepCountState()

        for (rep in 1..3) {
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(150.0, 150.0), state)
            assertEquals("DOWN", state.stage)

            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(55.0, 55.0), state)
            assertEquals(rep, state.counter)
            assertEquals("UP", state.stage)

            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(145.0, 145.0), state)
            assertEquals("DOWN", state.stage)
        }

        assertEquals(3, state.counter)

        // 4th movement is only a half-peak (reaches 75°, improper / incomplete rep)
        state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(110.0, 110.0), state)
        state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(75.0, 75.0), state)
        state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(95.0, 95.0), state)
        state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(140.0, 140.0), state)

        // Must still be 3, NOT 4!
        assertEquals(3, state.counter)
    }

    @Test
    fun testSignalAnalysisWithThreePeaksAndOneHalfPeak() {
        val signal = mutableListOf<Double>()

        // Helper to add a smooth wave to signal
        fun addCurlWave(peakAngle: Double) {
            // Down: 150°
            repeat(4) { signal.add(150.0) }
            // Flexing: 130°, 100°, 80°
            signal.add(130.0); signal.add(100.0); signal.add(80.0)
            // Peak:
            repeat(5) { signal.add(peakAngle) }
            // Extending: 80°, 100°, 130°
            signal.add(80.0); signal.add(100.0); signal.add(130.0)
            // Return down: 150°
            repeat(4) { signal.add(150.0) }
        }

        // 3 Full reps (peaking at 45°, 50°, 55°)
        addCurlWave(45.0)
        addCurlWave(50.0)
        addCurlWave(55.0)

        // 1 Half peak (peaking at 75° — incomplete range of motion)
        addCurlWave(75.0)

        val repCount = ExerciseAnalyzer.countRepsFromSignal("Bicep Curls", signal)
        assertEquals(3, repCount) // Exactly 3 reps! Half peak is excluded.
    }

    @Test
    fun testSixFullRepsAndOneHalfRepCountsExactlySixReps() {
        val signal = mutableListOf<Double>()

        fun addCurlWave(peakAngle: Double) {
            repeat(4) { signal.add(150.0) }
            signal.add(130.0); signal.add(100.0); signal.add(80.0)
            repeat(5) { signal.add(peakAngle) }
            signal.add(80.0); signal.add(100.0); signal.add(130.0)
            repeat(4) { signal.add(150.0) }
        }

        // Reps 1, 2, 3: Full curls (peaking at 50°, 52°, 48°)
        addCurlWave(50.0)
        addCurlWave(52.0)
        addCurlWave(48.0)

        // Rep 4: Half curl in the middle (peaking at 75° — incomplete form)
        addCurlWave(75.0)

        // Reps 5, 6, 7: Full curls (peaking at 50°, 52°, 48°)
        addCurlWave(50.0)
        addCurlWave(52.0)
        addCurlWave(48.0)

        val repCount = ExerciseAnalyzer.countRepsFromSignal("Bicep Curls", signal)
        // Must count EXACTLY 6 reps, excluding the 75° half rep!
        assertEquals(6, repCount)
    }

    @Test
    fun testDesyncedArmsStillCountsThreeReps() {
        var state = RepCountState()

        for (rep in 1..3) {
            // Both down
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(145.0, 145.0), state)

            // Right arm curls faster (reaches 58° while left is at 78°)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(58.0, 78.0), state)
            assertEquals(rep, state.counter) // Counted once

            // Left arm catches up to 58° while right is at 65°
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(65.0, 58.0), state)
            assertEquals(rep, state.counter) // Still rep, NOT doubled!

            // Lower down to 140°
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(140.0, 140.0), state)
        }

        assertEquals(3, state.counter)
    }

    @Test
    fun testPartialLockoutStillCounts() {
        var state = RepCountState()

        // User does 3 reps without locking out to 180° (only lowering to 136°)
        for (rep in 1..3) {
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(136.0, 136.0), state)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(58.0, 58.0), state)
            assertEquals(rep, state.counter)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(136.0, 136.0), state)
        }

        assertEquals(3, state.counter)
    }
}
