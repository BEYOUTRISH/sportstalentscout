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
        // Wrist position determined by angle from shoulder-elbow vector
        val rRad = Math.toRadians(rightAngleDeg)
        // At 180 deg (straight down): dy = +0.2, dx = 0
        // At 0 deg (folded up): dy = -0.2, dx = 0
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

        // 3 full reps
        for (rep in 1..3) {
            // 1. Arm extended down (155°)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(155.0, 155.0), state)
            assertEquals("down", state.stageRight)
            assertEquals("down", state.stageLeft)

            // 2. Curling up (110°, 85°)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(110.0, 110.0), state)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(85.0, 85.0), state)

            // 3. Peak of curl (65°)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(65.0, 65.0), state)
            assertEquals(rep, state.counter)
            assertEquals("up", state.stageRight)
            assertEquals("up", state.stageLeft)

            // 4. Lowering down (95°, 140°)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(95.0, 95.0), state)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(140.0, 140.0), state)
            assertEquals("down", state.stageRight)
            assertEquals("down", state.stageLeft)
        }

        assertEquals(3, state.counter)
    }

    @Test
    fun testDesyncedArmsStillCountsThreeReps() {
        var state = RepCountState()

        for (rep in 1..3) {
            // Both down
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(145.0, 145.0), state)

            // Right arm curls faster (reaches 65° while left is still at 85°)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(65.0, 85.0), state)
            assertEquals(rep, state.counter) // Counted immediately when right arm completed curl

            // Left arm catches up to 65° while right is at 70°
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(70.0, 65.0), state)
            assertEquals(rep, state.counter) // Not double counted

            // Lower down to 140°
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(140.0, 140.0), state)
        }

        assertEquals(3, state.counter)
    }

    @Test
    fun testPartialLockoutStillCounts() {
        var state = RepCountState()

        // User does 3 reps without locking out to 180° (only lowering to 135°)
        for (rep in 1..3) {
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(136.0, 136.0), state)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(70.0, 70.0), state)
            assertEquals(rep, state.counter)
            state = ExerciseAnalyzer.countBicepCurl(createPoseWithAngle(136.0, 136.0), state)
        }

        assertEquals(3, state.counter)
    }
}
