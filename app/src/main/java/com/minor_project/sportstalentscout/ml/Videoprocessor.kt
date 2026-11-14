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
     *
     * @param videoPath  Absolute path to the recorded .mp4 file
     * @param exerciseType  One of: "Push-ups", "Squats", "Bicep Curls", "Shoulder Press"
     * @return TestResult with real rep count and computed score
     */
    suspend fun analyzeVideo(videoPath: String, exerciseType: String): VideoAnalysisResult =
        withContext(Dispatchers.Default) {

            val landmarker = buildPoseLandmarker()
            val frames = extractFrames(videoPath)

            var state = RepCountState()
            val angleSamples = mutableListOf<AngleSample>()

            if (landmarker != null) {
                try {
                    for (frame in frames) {
                        val landmarks = detectLandmarks(landmarker, frame)

                        if (landmarks.isNotEmpty()) {
                            val angle = when (exerciseType) {
                                "Push-ups" -> {
                                    val s = landmarks[PoseLandmark.LEFT_SHOULDER]
                                    val e = landmarks[PoseLandmark.LEFT_ELBOW]
                                    val w = landmarks[PoseLandmark.LEFT_WRIST]
                                    if (s != null && e != null && w != null)
                                        ExerciseAnalyzer.findAngle(s, e, w)
                                    else null
                                }

                                "Squats" -> {
                                    val h = landmarks[PoseLandmark.RIGHT_HIP]
                                    val k = landmarks[PoseLandmark.RIGHT_KNEE]
                                    val a = landmarks[PoseLandmark.RIGHT_ANKLE]
                                    if (h != null && k != null && a != null)
                                        ExerciseAnalyzer.findAngle(h, k, a)
                                    else null
                                }

                                "Bicep Curls" -> {
                                    val s = landmarks[PoseLandmark.RIGHT_SHOULDER]
                                    val e = landmarks[PoseLandmark.RIGHT_ELBOW]
                                    val w = landmarks[PoseLandmark.RIGHT_WRIST]
                                    if (s != null && e != null && w != null)
                                        ExerciseAnalyzer.findAngle(s, e, w)
                                    else null
                                }

                                "Shoulder Press" -> {
                                    val s = landmarks[PoseLandmark.RIGHT_SHOULDER]
                                    val e = landmarks[PoseLandmark.RIGHT_ELBOW]
                                    val w = landmarks[PoseLandmark.RIGHT_WRIST]
                                    if (s != null && e != null && w != null)
                                        ExerciseAnalyzer.findAngle(s, e, w)
                                    else null
                                }

                                else -> null
                            }

                            angle?.let {
                                angleSamples.add(
                                    AngleSample(
                                        time = System.currentTimeMillis(),
                                        angle = it
                                    )
                                )
                            }

                            state = countRep(exerciseType, landmarks, state)
                        }

                        frame.recycle()
                    }
                } finally {
                    try {
                        landmarker.close()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error closing landmarker", t)
                    }
                }
            } else {
                for (frame in frames) {
                    try { frame.recycle() } catch (ignored: Throwable) {}
                }
                throw IllegalStateException("Pose tracking engine could not be initialized on this device architecture. Please test on an ARM64-compatible environment or physical phone.")
            }

            val repCount = state.counter
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

    // ── Extract frames at SAMPLE_FPS using MediaMetadataRetriever ────────────
    private fun extractFrames(videoPath: String): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val frames = mutableListOf<Bitmap>()
            val intervalUs = (1_000_000L / SAMPLE_FPS)
            var timeUs = 0L

            while (timeUs <= durationMs * 1000L) {
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) frames.add(bitmap)
                timeUs += intervalUs
            }

            return frames
        } catch (t: Throwable) {
            Log.e(TAG, "Error extracting frames from video: ${t.message}", t)
            return emptyList()
        } finally {
            try {
                retriever.release()
            } catch (ignored: Throwable) {}
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

    // ── Dispatch to correct exercise counter ──────────────────────────────────
    private fun countRep(
        exerciseType: String,
        landmarks: List<LandmarkPoint?>,
        state: RepCountState
    ): RepCountState {
        return when (exerciseType) {
            "Push-ups"       -> ExerciseAnalyzer.countPushUp(landmarks, state)
            "Squats"         -> ExerciseAnalyzer.countSquat(landmarks, state)
            "Bicep Curls"    -> ExerciseAnalyzer.countBicepCurl(landmarks, state)
            "Shoulder Press" -> ExerciseAnalyzer.countShoulderPress(landmarks, state)
            else             -> state
        }
    }
}
