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
        private const val SAMPLE_FPS = 15
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
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        try {
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
                        } finally {
                            bitmap.recycle()
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
                val ls = landmarks.getOrNull(PoseLandmark.LEFT_SHOULDER)
                val le = landmarks.getOrNull(PoseLandmark.LEFT_ELBOW)
                val lw = landmarks.getOrNull(PoseLandmark.LEFT_WRIST)
                val rAngle = if (rs != null && re != null && rw != null) ExerciseAnalyzer.calculateAngle(rs, re, rw) else null
                val lAngle = if (ls != null && le != null && lw != null) ExerciseAnalyzer.calculateAngle(ls, le, lw) else null
                when {
                    rAngle != null && lAngle != null -> kotlin.math.min(rAngle, lAngle)
                    rAngle != null -> rAngle
                    else -> lAngle
                }
            }

            "Shoulder Press" -> {
                val rs = landmarks.getOrNull(PoseLandmark.RIGHT_SHOULDER)
                val re = landmarks.getOrNull(PoseLandmark.RIGHT_ELBOW)
                val rw = landmarks.getOrNull(PoseLandmark.RIGHT_WRIST)
                val ls = landmarks.getOrNull(PoseLandmark.LEFT_SHOULDER)
                val le = landmarks.getOrNull(PoseLandmark.LEFT_ELBOW)
                val lw = landmarks.getOrNull(PoseLandmark.LEFT_WRIST)
                val rAngle = if (rs != null && re != null && rw != null) ExerciseAnalyzer.calculateAngle(rs, re, rw) else null
                val lAngle = if (ls != null && le != null && lw != null) ExerciseAnalyzer.calculateAngle(ls, le, lw) else null
                when {
                    rAngle != null && lAngle != null -> (rAngle + lAngle) / 2.0
                    rAngle != null -> rAngle
                    else -> lAngle
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
