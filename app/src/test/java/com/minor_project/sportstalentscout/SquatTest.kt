package com.minor_project.sportstalentscout

import com.minor_project.sportstalentscout.ml.ExerciseAnalyzer
import com.minor_project.sportstalentscout.ml.LandmarkPoint
import com.minor_project.sportstalentscout.ml.PoseLandmark
import com.minor_project.sportstalentscout.ml.RepCountState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SquatTest {

    // Helper to generate pose landmarks with specified knee interior angle and hip height
    private fun createSquatPose(kneeAngleDeg: Double, isThighParallel: Boolean = true): List<LandmarkPoint?> {
        val landmarks = MutableList<LandmarkPoint?>(33) { null }

        // Knee joint at (0.50, 0.70)
        val kx = 0.50f
        val ky = 0.70f
        val legLength = 0.20f

        // Hip joint:
        // When thighs are parallel to the floor, hip drops to knee height (diff <= 0.08f)
        // When thighs are not parallel (standing/shallow), hip is high above knee (diff > 0.08f)
        val hipY = if (isThighParallel) 0.68f else 0.50f
        val hipX = if (isThighParallel) 0.32f else 0.45f

        val v1x = (hipX - kx).toDouble()
        val v1y = (hipY - ky).toDouble()
        val theta1 = kotlin.math.atan2(v1y, v1x)

        // Position ankle so interior angle at knee is exactly kneeAngleDeg
        val theta2 = theta1 + Math.toRadians(kneeAngleDeg)
        val ankleX = (kx + legLength * kotlin.math.cos(theta2)).toFloat()
        val ankleY = (ky + legLength * kotlin.math.sin(theta2)).toFloat()

        val rHip = LandmarkPoint(hipX, hipY)
        val rKnee = LandmarkPoint(kx, ky)
        val rAnkle = LandmarkPoint(ankleX, ankleY)

        val lHip = LandmarkPoint(1.0f - hipX, hipY)
        val lKnee = LandmarkPoint(1.0f - kx, ky)
        val lAnkle = LandmarkPoint(1.0f - ankleX, ankleY)

        landmarks[PoseLandmark.RIGHT_HIP]   = rHip
        landmarks[PoseLandmark.RIGHT_KNEE]  = rKnee
        landmarks[PoseLandmark.RIGHT_ANKLE] = rAnkle

        landmarks[PoseLandmark.LEFT_HIP]    = lHip
        landmarks[PoseLandmark.LEFT_KNEE]   = lKnee
        landmarks[PoseLandmark.LEFT_ANKLE]  = lAnkle

        return landmarks
    }

    @Test
    fun testThreeParallelSquatsCountAccurately() {
        var state = RepCountState()

        for (rep in 1..3) {
            // Standing tall (160°)
            state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)
            assertEquals("STANDING", state.stage)

            // Descending through 120°, 100°
            state = ExerciseAnalyzer.countSquat(createSquatPose(120.0, isThighParallel = false), state)
            state = ExerciseAnalyzer.countSquat(createSquatPose(100.0, isThighParallel = false), state)

            // Deep squat with thighs parallel to floor (80° <= 88.0°)
            state = ExerciseAnalyzer.countSquat(createSquatPose(80.0, isThighParallel = true), state)
            assertEquals("SQUATTING", state.stage)

            // Rising back up to standing (160° >= 145.0°)
            state = ExerciseAnalyzer.countSquat(createSquatPose(115.0, isThighParallel = false), state)
            state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)
            assertEquals(rep, state.counter)
            assertEquals("STANDING", state.stage)
        }

        assertEquals(3, state.counter)
    }

    @Test
    fun testShallowSquatWithoutParallelThighsDoesNotCount() {
        var state = RepCountState()

        // Reps 1, 2, 3: Full parallel squats
        for (rep in 1..3) {
            state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)
            state = ExerciseAnalyzer.countSquat(createSquatPose(80.0, isThighParallel = true), state)
            state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)
            assertEquals(rep, state.counter)
        }

        assertEquals(3, state.counter)

        // 4th rep: Person does NOT make thighs parallel to the floor (shallow squat reaching only 98°)
        state = ExerciseAnalyzer.countSquat(createSquatPose(130.0, isThighParallel = false), state)
        state = ExerciseAnalyzer.countSquat(createSquatPose(98.0, isThighParallel = false), state)
        state = ExerciseAnalyzer.countSquat(createSquatPose(130.0, isThighParallel = false), state)
        state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)

        // Counter MUST remain 3, NOT 4!
        assertEquals(3, state.counter)
    }

    @Test
    fun testSignalAnalysisRejectsShallowFourthRep() {
        val signal = mutableListOf<Double>()

        fun addSquatWave(bottomAngle: Double) {
            // Standing at 165°
            repeat(4) { signal.add(165.0) }
            // Descending
            signal.add(140.0); signal.add(115.0); signal.add(95.0)
            // Bottom of squat
            repeat(5) { signal.add(bottomAngle) }
            // Ascending
            signal.add(95.0); signal.add(115.0); signal.add(140.0)
            // Standing lockout
            repeat(4) { signal.add(165.0) }
        }

        // Reps 1, 2, 3: Full parallel squats (peaking at 78°, 75°, 80°)
        addSquatWave(78.0)
        addSquatWave(75.0)
        addSquatWave(80.0)

        // Rep 4: Shallow squat where thighs did not reach parallel (peaking at 98°)
        addSquatWave(98.0)

        // Total 4 waves in signal, but exactly 3 proper reps counted!
        val repCount = ExerciseAnalyzer.countRepsFromSignal("Squats", signal)
        assertEquals(3, repCount)
    }

    @Test
    fun testHighHipPenaltyRejectsSquat() {
        var state = RepCountState()

        state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)
        assertEquals("STANDING", state.stage)

        // Even if knee angle is 82°, if hips stayed high up (isThighParallel = false),
        // form check clamps angle to >= 95.0° and rep is rejected!
        state = ExerciseAnalyzer.countSquat(createSquatPose(82.0, isThighParallel = false), state)
        assertEquals("STANDING", state.stage) // Did NOT enter SQUATTING!

        state = ExerciseAnalyzer.countSquat(createSquatPose(160.0, isThighParallel = false), state)
        assertEquals(0, state.counter)
    }
}
