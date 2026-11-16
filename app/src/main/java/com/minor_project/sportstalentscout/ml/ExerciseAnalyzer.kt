package com.minor_project.sportstalentscout.ml
import kotlin.math.*


// MediaPipe Pose landmark indices (matches Python mp_pose.PoseLandmark)
object PoseLandmark {
    const val LEFT_SHOULDER  = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW     = 13
    const val RIGHT_ELBOW    = 14
    const val LEFT_WRIST     = 15
    const val RIGHT_WRIST    = 16
    const val LEFT_HIP       = 23
    const val RIGHT_HIP      = 24
    const val LEFT_KNEE      = 25
    const val RIGHT_KNEE     = 26
    const val LEFT_ANKLE     = 27
    const val RIGHT_ANKLE    = 28
}

data class LandmarkPoint(val x: Float, val y: Float)

data class RepCountState(
    val counter: Int = 0,
    val stage: String? = null,
    val stageRight: String? = null,
    val stageLeft: String? = null
)

data class ExerciseResult(
    val repCount: Int,
    val score: Int,
    val exerciseType: String
)

object ExerciseAnalyzer {

    // ── Angle calculation using dot product (0° to 180°, no branch-cut or wrapping errors) ──
    fun calculateAngle(a: LandmarkPoint, b: LandmarkPoint, c: LandmarkPoint): Double {
        val bax = (a.x - b.x).toDouble()
        val bay = (a.y - b.y).toDouble()
        val bcx = (c.x - b.x).toDouble()
        val bcy = (c.y - b.y).toDouble()
        val dot = bax * bcx + bay * bcy
        val magBA = kotlin.math.sqrt(bax * bax + bay * bay)
        val magBC = kotlin.math.sqrt(bcx * bcx + bcy * bcy)
        if (magBA < 1e-6 || magBC < 1e-6) return 180.0
        val cosTheta = (dot / (magBA * magBC)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosTheta))
    }

    // ── find_angle from PoseModule2.py (uses atan2 with += 360 for negatives) ──
    // This version matches the detector.find_angle() used in count_repetition_* functions
    fun findAngle(a: LandmarkPoint, b: LandmarkPoint, c: LandmarkPoint): Double {
        var angle = Math.toDegrees(
            atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble()) -
                    atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())
        )
        if (angle < 0) angle += 360.0
        return angle
    }

    // ── Safe landmark getter — returns null if index out of bounds ──
    private fun getLandmark(landmarks: List<LandmarkPoint?>, index: Int): LandmarkPoint? {
        return landmarks.getOrNull(index)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUSH-UP  (count_repetition_push_up in ExerciseTrainer.py)
    // Landmarks: 12=R_SHOULDER, 14=R_ELBOW, 16=R_WRIST, 11=L_SHOULDER, 13=L_ELBOW, 15=L_WRIST
    // Stage down: leftArmAngle < 220
    // Stage up:   leftArmAngle > 240 && stage == "down"  → counter++
    // ─────────────────────────────────────────────────────────────────────────
    fun countPushUp(
        landmarks: List<LandmarkPoint?>,
        state: RepCountState
    ): RepCountState {
        val lShoulder = getLandmark(landmarks, PoseLandmark.LEFT_SHOULDER)  ?: return state
        val lElbow    = getLandmark(landmarks, PoseLandmark.LEFT_ELBOW)     ?: return state
        val lWrist    = getLandmark(landmarks, PoseLandmark.LEFT_WRIST)     ?: return state

        val leftAngle = findAngle(lShoulder, lElbow, lWrist)

        var stage   = state.stage
        var counter = state.counter

        if (leftAngle < 220) {
            stage = "down"
        }
        if (leftAngle > 240 && stage == "down") {
            stage = "up"
            counter++
        }

        return state.copy(counter = counter, stage = stage)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQUAT
    // Uses true interior knee angle (0-180°):
    // - Standing (up):                         angle >= 145°
    // - Squatting (down, thighs parallel/low): angle <= 88° (strict parallel squat; rejects shallow squats >= 95°)
    // Checks hip descent relative to knee to ensure thighs reach parallel to the floor.
    // ─────────────────────────────────────────────────────────────────────────
    fun countSquat(
        landmarks: List<LandmarkPoint?>,
        state: RepCountState
    ): RepCountState {
        val rh = getLandmark(landmarks, PoseLandmark.RIGHT_HIP)
        val rk = getLandmark(landmarks, PoseLandmark.RIGHT_KNEE)
        val ra = getLandmark(landmarks, PoseLandmark.RIGHT_ANKLE)

        val lh = getLandmark(landmarks, PoseLandmark.LEFT_HIP)
        val lk = getLandmark(landmarks, PoseLandmark.LEFT_KNEE)
        val la = getLandmark(landmarks, PoseLandmark.LEFT_ANKLE)

        var rAngle = if (rh != null && rk != null && ra != null) calculateAngle(rh, rk, ra) else null
        var lAngle = if (lh != null && lk != null && la != null) calculateAngle(lh, lk, la) else null

        // Thigh parallelism check: if hips remain significantly above knees (knee.y - hip.y > 0.08f),
        // the thighs have not reached parallel to the floor. Penalize angle so shallow squats do not count.
        if (rh != null && rk != null && (rk.y - rh.y) > 0.08f && rAngle != null) {
            rAngle = kotlin.math.max(rAngle, 95.0)
        }
        if (lh != null && lk != null && (lk.y - lh.y) > 0.08f && lAngle != null) {
            lAngle = kotlin.math.max(lAngle, 95.0)
        }

        val angle = when {
            rAngle != null && lAngle != null -> {
                val avg = (rAngle + lAngle) / 2.0
                // Bilateral requirement: if one leg does not bend (> 120°), reject unilateral cheat
                if (kotlin.math.max(rAngle, lAngle) > 120.0) {
                    kotlin.math.max(avg, 95.0)
                } else {
                    avg
                }
            }
            rAngle != null -> rAngle
            else -> lAngle
        } ?: return state

        var stage = state.stage ?: "STANDING"
        var counter = state.counter

        if (stage == "STANDING") {
            if (angle <= 88.0) {
                stage = "SQUATTING"
            }
        } else if (stage == "SQUATTING") {
            if (angle >= 145.0) {
                stage = "STANDING"
                counter++
            }
        }

        return state.copy(counter = counter, stage = stage)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BICEP CURL
    // Uses true interior elbow angle (0-180°):
    // - Arm extended (down): angle >= 130°
    // - Arm curled   (up):   angle <= 55° (strict full curl; rejects loose folds / arm raises >= 60°)
    // Tracks active curl motion so curling both arms counts as 1 rep.
    // ─────────────────────────────────────────────────────────────────────────
    fun countBicepCurl(
        landmarks: List<LandmarkPoint?>,
        state: RepCountState
    ): RepCountState {
        val rs = getLandmark(landmarks, PoseLandmark.RIGHT_SHOULDER)
        val re = getLandmark(landmarks, PoseLandmark.RIGHT_ELBOW)
        val rw = getLandmark(landmarks, PoseLandmark.RIGHT_WRIST)
        val rh = getLandmark(landmarks, PoseLandmark.RIGHT_HIP)

        val ls = getLandmark(landmarks, PoseLandmark.LEFT_SHOULDER)
        val le = getLandmark(landmarks, PoseLandmark.LEFT_ELBOW)
        val lw = getLandmark(landmarks, PoseLandmark.LEFT_WRIST)
        val lh = getLandmark(landmarks, PoseLandmark.LEFT_HIP)

        var rAngle = if (rs != null && re != null && rw != null) calculateAngle(rs, re, rw) else null
        var lAngle = if (ls != null && le != null && lw != null) calculateAngle(ls, le, lw) else null

        // Form stability check: penalize arm raise / forward elbow swing / cheat lifts
        if (rAngle != null && rs != null && re != null) {
            val rShoulderAngle = if (rh != null) calculateAngle(rh, rs, re) else 0.0
            if (rShoulderAngle > 50.0 || re.y <= rs.y + 0.08f) {
                rAngle = kotlin.math.max(rAngle, 85.0)
            }
        }
        if (lAngle != null && ls != null && le != null) {
            val lShoulderAngle = if (lh != null) calculateAngle(lh, ls, le) else 0.0
            if (lShoulderAngle > 50.0 || le.y <= ls.y + 0.08f) {
                lAngle = kotlin.math.max(lAngle, 85.0)
            }
        }

        val angle = when {
            rAngle != null && lAngle != null -> {
                val avg = (rAngle + lAngle) / 2.0
                // Require bilateral engagement: if one arm is not curling (> 95°), penalize
                if (kotlin.math.max(rAngle, lAngle) > 95.0) {
                    kotlin.math.max(avg, 85.0)
                } else {
                    avg
                }
            }
            rAngle != null -> rAngle
            else -> lAngle
        } ?: return state

        var stage = state.stage ?: "DOWN"
        var counter = state.counter

        if (stage == "DOWN") {
            if (angle <= 55.0) {
                stage = "UP"
                counter++
            }
        } else if (stage == "UP") {
            if (angle >= 130.0) {
                stage = "DOWN"
            }
        }

        return state.copy(counter = counter, stage = stage)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Post-analysis Rep Counter from Smoothed Motion Waveform
    // Matches the visual waveform peaks on the results chart 100%
    // ─────────────────────────────────────────────────────────────────────────
    fun countRepsFromSignal(exerciseType: String, rawAngles: List<Double>): Int {
        if (rawAngles.size < 3) return 0

        // 5-point moving average smoothing matching the waveform chart
        val smoothed = rawAngles.windowed(size = 5, step = 1, partialWindows = true) { window ->
            window.average()
        }

        return when (exerciseType) {
            "Bicep Curls" -> {
                // Down (extended): angle >= 130°
                // Up (curled):     angle <= 55° (strict full athletic curl, rejects loose folds / cheat raises >= 60°)
                var state = if (smoothed.firstOrNull()?.let { it <= 55.0 } == true) "UP" else "DOWN"
                var count = 0
                for (angle in smoothed) {
                    if (state == "DOWN") {
                        if (angle <= 55.0) {
                            state = "UP"
                            count++
                        }
                    } else if (state == "UP") {
                        if (angle >= 130.0) {
                            state = "DOWN"
                        }
                    }
                }
                count
            }
            "Push-ups" -> {
                // Plank (arms straight): angle >= 140°
                // Down (chest low):      angle <= 95°
                var state = if (smoothed.firstOrNull()?.let { it <= 95.0 } == true) "BOTTOM" else "TOP"
                var count = 0
                for (angle in smoothed) {
                    if (state == "TOP") {
                        if (angle <= 95.0) {
                            state = "BOTTOM"
                        }
                    } else if (state == "BOTTOM") {
                        if (angle >= 140.0) {
                            state = "TOP"
                            count++
                        }
                    }
                }
                count
            }
            "Squats" -> {
                // Standing:  angle >= 145°
                // Squatting (thighs parallel to floor): angle <= 88° (strict parallel squat; rejects shallow squats >= 95°)
                var state = if (smoothed.firstOrNull()?.let { it <= 88.0 } == true) "SQUATTING" else "STANDING"
                var count = 0
                for (angle in smoothed) {
                    if (state == "STANDING") {
                        if (angle <= 88.0) {
                            state = "SQUATTING"
                        }
                    } else if (state == "SQUATTING") {
                        if (angle >= 145.0) {
                            state = "STANDING"
                            count++
                        }
                    }
                }
                count
            }
            "Shoulder Press" -> {
                // At shoulders (rack): angle <= 100°
                // Overhead (lockout):   angle >= 130° (calibrated for natural 2D camera view; rejects half-presses <= 118°)
                var state = if (smoothed.firstOrNull()?.let { it >= 130.0 } == true) "UP" else "DOWN"
                var count = 0
                for (angle in smoothed) {
                    if (state == "DOWN") {
                        if (angle >= 130.0) {
                            state = "UP"
                            count++
                        }
                    } else if (state == "UP") {
                        if (angle <= 100.0) {
                            state = "DOWN"
                        }
                    }
                }
                count
            }
            else -> 0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHOULDER PRESS
    // Uses true interior elbow angle combined with wrist elevation verification.
    // - Hands at shoulders (down): angle <= 100°
    // - Hands overhead (up):       angle >= 130°
    // ─────────────────────────────────────────────────────────────────────────
    fun countShoulderPress(
        landmarks: List<LandmarkPoint?>,
        state: RepCountState
    ): RepCountState {
        val rs = getLandmark(landmarks, PoseLandmark.RIGHT_SHOULDER)
        val re = getLandmark(landmarks, PoseLandmark.RIGHT_ELBOW)
        val rw = getLandmark(landmarks, PoseLandmark.RIGHT_WRIST)
        val rh = getLandmark(landmarks, PoseLandmark.RIGHT_HIP)

        val ls = getLandmark(landmarks, PoseLandmark.LEFT_SHOULDER)
        val le = getLandmark(landmarks, PoseLandmark.LEFT_ELBOW)
        val lw = getLandmark(landmarks, PoseLandmark.LEFT_WRIST)
        val lh = getLandmark(landmarks, PoseLandmark.LEFT_HIP)

        val rMidY = if (rs != null && rh != null) (rs.y + rh.y) / 2f else (rs?.y?.plus(0.28f) ?: 0.6f)
        val lMidY = if (ls != null && lh != null) (ls.y + lh.y) / 2f else (ls?.y?.plus(0.28f) ?: 0.6f)

        val rValid = (rs != null && re != null && rw != null && rw.y < rMidY)
        val lValid = (ls != null && le != null && lw != null && lw.y < lMidY)

        val rAngle = if (rValid) calculateAngle(rs, re, rw) else null
        val lAngle = if (lValid) calculateAngle(ls, le, lw) else null

        val angle = when {
            rAngle != null && lAngle != null -> {
                val avg = (rAngle + lAngle) / 2.0
                // Require bilateral press engagement: if one arm stays idle at shoulder (< 115°), cap at 118°
                if (kotlin.math.min(rAngle, lAngle) < 115.0) {
                    kotlin.math.min(avg, 118.0)
                } else {
                    avg
                }
            }
            rAngle != null -> rAngle
            lAngle != null -> lAngle
            else -> 80.0 // Arms down at sides at rest
        }

        var stage = state.stage ?: "DOWN"
        var counter = state.counter

        if (stage == "DOWN") {
            if (angle >= 130.0) {
                stage = "UP"
                counter++
            }
        } else if (stage == "UP") {
            if (angle <= 100.0) {
                stage = "DOWN"
            }
        }

        return state.copy(counter = counter, stage = stage)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Score calculation — converts rep count to 0-100 score
    // Benchmarks roughly match typical fitness standards
    // ─────────────────────────────────────────────────────────────────────────
    fun calculateScore(exerciseType: String, repCount: Int): Int {
        val maxReps = when (exerciseType) {
            "Push-ups"      -> 50   // 50 push-ups = 100 score
            "Squats"        -> 50
            "Bicep Curls"   -> 30
            "Shoulder Press"-> 30
            else            -> 40
        }
        return ((repCount.toFloat() / maxReps) * 100).toInt().coerceIn(0, 100)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recommendations based on score
    // ─────────────────────────────────────────────────────────────────────────
    fun getRecommendations(exerciseType: String, score: Int): List<String> {
        return when {
            score >= 85 -> listOf(
                "Outstanding performance!",
                "Add progressive overload to keep improving",
                "Consider adding weight/resistance",
                "Maintain your consistency"
            )
            score >= 70 -> listOf(
                "Great effort! Above average performance",
                "Focus on full range of motion",
                "Add 2–3 more reps each session",
                "Ensure proper form throughout"
            )
            score >= 50 -> listOf(
                "Good start! Keep building endurance",
                "Take shorter rest breaks between sets",
                "Practice 3–4 times per week",
                when (exerciseType) {
                    "Push-ups"       -> "Try knee push-ups to build strength"
                    "Squats"         -> "Focus on depth — thighs parallel to floor"
                    "Bicep Curls"    -> "Use a lighter weight for strict form"
                    "Shoulder Press" -> "Strengthen your core for better stability"
                    else -> "Focus on consistent practice"
                }
            )
            else -> listOf(
                "Keep going — every rep counts!",
                "Focus on learning the correct form first",
                "Start with fewer reps and increase gradually",
                "Consider a warm-up before exercising"
            )
        }
    }
}