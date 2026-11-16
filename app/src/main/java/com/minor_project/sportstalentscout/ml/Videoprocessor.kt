package com.minor_project.sportstalentscout.ml

import com.minor_project.sportstalentscout.data.models.AngleSample
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.minor_project.sportstalentscout.data.models.TestResult
import com.minor_project.sportstalentscout.data.models.VideoAnalysisResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Processes a recorded video file frame by frame.
 * Uses MediaPipe Pose Landmarker (Android SDK) to extract landmarks,
 * then runs the ported rep-counting logic from ExerciseTrainer.py.
 */
class VideoProcessor(private val context: Context) {

    companion object {
        private const val TAG = "VideoProcessor"
        private const val SAMPLE_FPS = 10
        private const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"
    }

    /**
     * Main entry point. Call from ViewModel via coroutine.
     * Streams video frames, extracts pose landmarks, and counts reps
     * using smoothed motion waveform analysis that matches the results chart.
     *
     * @param videoPath  Absolute path to the recorded .mp4 file
     * @param exerciseType  One of: "Push-ups", "Squats", "Bicep Curls", "Shoulder Press"
     * @return TestResult with real rep count and computed score
     */
    suspend fun analyzeVideo(videoPath: String, exerciseType: String): VideoAnalysisResult =
        withContext(Dispatchers.Default) {

            val landmarker = buildPoseLandmarker()
                ?: throw IllegalStateException("Pose tracking engine could not be initialized on this device architecture.")

            val angleSamples = mutableListOf<AngleSample>()
            val rawAngles = mutableListOf<Double>()

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)

                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                val intervalUs = (1_000_000L / SAMPLE_FPS)
                var timeUs = 0L

                while (timeUs <= durationMs * 1000L) {
                    val rawBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        try {
                            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 640)
                        } catch (ignored: Throwable) {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        }
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

                    if (rawBitmap != null) {
                        // MediaPipe requires software ARGB_8888 bitmap; convert if hardware-backed
                        val bitmap = if (rawBitmap.config == Bitmap.Config.HARDWARE) {
                            rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            rawBitmap
                        }

                        if (bitmap != null) {
                            val landmarks = detectLandmarks(landmarker, bitmap)

                            if (landmarks.isNotEmpty()) {
                                val angle = extractExerciseAngle(exerciseType, landmarks)
                                if (angle != null) {
                                    rawAngles.add(angle)
                                    angleSamples.add(
                                        AngleSample(
                                            time = timeUs / 1000L,
                                            angle = angle
                                        )
                                    )
                                }
                            }
                        }
                    }
                    timeUs += intervalUs
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error analyzing video frames: ${t.message}", t)
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Throwable) {}
                try {
                    landmarker.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error closing landmarker", t)
                }
            }

            // Reps counted from the smoothed motion waveform — perfectly matching the visual chart peaks
            val repCount = ExerciseAnalyzer.countRepsFromSignal(exerciseType, rawAngles)
            val score = ExerciseAnalyzer.calculateScore(exerciseType, repCount)

            val result = TestResult(
                testType = exerciseType,
                value = repCount.toString(),
                unit = "reps",
                score = score
            )

            VideoAnalysisResult(
                testResult = result,
                angleSamples = angleSamples
            )
        }

    // ── Helper to extract the primary movement angle for each exercise ────────
    private fun extractExerciseAngle(exerciseType: String, landmarks: List<LandmarkPoint?>): Double? {
        return when (exerciseType) {
            "Push-ups" -> {
                val ls = landmarks.getOrNull(PoseLandmark.LEFT_SHOULDER)
                val le = landmarks.getOrNull(PoseLandmark.LEFT_ELBOW)
                val lw = landmarks.getOrNull(PoseLandmark.LEFT_WRIST)
                val rs = landmarks.getOrNull(PoseLandmark.RIGHT_SHOULDER)
                val re = landmarks.getOrNull(PoseLandmark.RIGHT_ELBOW)
                val rw = landmarks.getOrNull(PoseLandmark.RIGHT_WRIST)
                val lAngle = if (ls != null && le != null && lw != null) ExerciseAnalyzer.calculateAngle(ls, le, lw) else null
                val rAngle = if (rs != null && re != null && rw != null) ExerciseAnalyzer.calculateAngle(rs, re, rw) else null
                lAngle ?: rAngle
            }

            "Squats" -> {
                val lh = landmarks.getOrNull(PoseLandmark.LEFT_HIP)
                val lk = landmarks.getOrNull(PoseLandmark.LEFT_KNEE)
                val la = landmarks.getOrNull(PoseLandmark.LEFT_ANKLE)
                val rh = landmarks.getOrNull(PoseLandmark.RIGHT_HIP)
                val rk = landmarks.getOrNull(PoseLandmark.RIGHT_KNEE)
                val ra = landmarks.getOrNull(PoseLandmark.RIGHT_ANKLE)
                val lAngle = if (lh != null && lk != null && la != null) ExerciseAnalyzer.calculateAngle(lh, lk, la) else null
                val rAngle = if (rh != null && rk != null && ra != null) ExerciseAnalyzer.calculateAngle(rh, rk, ra) else null
                when {
                    lAngle != null && rAngle != null -> (lAngle + rAngle) / 2.0
                    lAngle != null -> lAngle
                    else -> rAngle
                }
            }

            "Bicep Curls" -> {
                val rs = landmarks.getOrNull(PoseLandmark.RIGHT_SHOULDER)
                val re = landmarks.getOrNull(PoseLandmark.RIGHT_ELBOW)
                val rw = landmarks.getOrNull(PoseLandmark.RIGHT_WRIST)
                val rh = landmarks.getOrNull(PoseLandmark.RIGHT_HIP)

                val ls = landmarks.getOrNull(PoseLandmark.LEFT_SHOULDER)
                val le = landmarks.getOrNull(PoseLandmark.LEFT_ELBOW)
                val lw = landmarks.getOrNull(PoseLandmark.LEFT_WRIST)
                val lh = landmarks.getOrNull(PoseLandmark.LEFT_HIP)

                var rAngle = if (rs != null && re != null && rw != null) ExerciseAnalyzer.calculateAngle(rs, re, rw) else null
                var lAngle = if (ls != null && le != null && lw != null) ExerciseAnalyzer.calculateAngle(ls, le, lw) else null

                // Form stability check: penalize arm raise / forward elbow swing / cheat lifts
                if (rAngle != null && rs != null && re != null) {
                    val rShoulderAngle = if (rh != null) ExerciseAnalyzer.calculateAngle(rh, rs, re) else 0.0
                    if (rShoulderAngle > 50.0 || re.y <= rs.y + 0.08f) {
                        rAngle = kotlin.math.max(rAngle, 85.0)
                    }
                }
                if (lAngle != null && ls != null && le != null) {
                    val lShoulderAngle = if (lh != null) ExerciseAnalyzer.calculateAngle(lh, ls, le) else 0.0
                    if (lShoulderAngle > 50.0 || le.y <= ls.y + 0.08f) {
                        lAngle = kotlin.math.max(lAngle, 85.0)
                    }
                }

                when {
                    rAngle != null && lAngle != null -> {
                        val avg = (rAngle + lAngle) / 2.0
                        // Bilateral requirement: if one arm is not curling (> 95°), reject single-arm cheat
                        if (kotlin.math.max(rAngle, lAngle) > 95.0) {
                            kotlin.math.max(avg, 85.0)
                        } else {
                            avg
                        }
                    }
                    rAngle != null -> rAngle
                    else -> lAngle
                }
            }

            "Shoulder Press" -> {
                val rs = landmarks.getOrNull(PoseLandmark.RIGHT_SHOULDER)
                val re = landmarks.getOrNull(PoseLandmark.RIGHT_ELBOW)
                val rw = landmarks.getOrNull(PoseLandmark.RIGHT_WRIST)
                val rh = landmarks.getOrNull(PoseLandmark.RIGHT_HIP)

                val ls = landmarks.getOrNull(PoseLandmark.LEFT_SHOULDER)
                val le = landmarks.getOrNull(PoseLandmark.LEFT_ELBOW)
                val lw = landmarks.getOrNull(PoseLandmark.LEFT_WRIST)
                val lh = landmarks.getOrNull(PoseLandmark.LEFT_HIP)

                // Active pressing zone: wrists must be above mid-torso (not resting down at hips)
                val rMidY = if (rs != null && rh != null) (rs.y + rh.y) / 2f else (rs?.y?.plus(0.28f) ?: 0.6f)
                val lMidY = if (ls != null && lh != null) (ls.y + lh.y) / 2f else (ls?.y?.plus(0.28f) ?: 0.6f)

                val rValid = (rs != null && re != null && rw != null && rw.y < rMidY)
                val lValid = (ls != null && le != null && lw != null && lw.y < lMidY)

                val rAngle = if (rValid) ExerciseAnalyzer.calculateAngle(rs, re, rw) else null
                val lAngle = if (lValid) ExerciseAnalyzer.calculateAngle(ls, le, lw) else null

                when {
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
                    else -> 80.0 // Hands resting at hips -> baseline rack position
                }
            }

            else -> null
        }
    }

    // ── Build MediaPipe PoseLandmarker safely ─────────────────────────────────
    private fun buildPoseLandmarker(): PoseLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(POSE_MODEL_ASSET)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            PoseLandmarker.createFromOptions(context, options)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize PoseLandmarker: ${t.message}", t)
            null
        }
    }

    // ── Run MediaPipe on a single Bitmap frame ────────────────────────────────
    private fun detectLandmarks(landmarker: PoseLandmarker, bitmap: Bitmap): List<LandmarkPoint?> {
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.landmarks().isEmpty()) return emptyList()

            val rawLandmarks = result.landmarks()[0]
            val landmarkList = MutableList<LandmarkPoint?>(33) { null }
            rawLandmarks.forEachIndexed { idx, lm ->
                if (idx < 33) {
                    landmarkList[idx] = LandmarkPoint(lm.x(), lm.y())
                }
            }
            landmarkList
        } catch (t: Throwable) {
            Log.e(TAG, "Error detecting landmarks in frame: ${t.message}", t)
            emptyList()
        }
    }
}
