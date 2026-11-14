package com.minor_project.sportstalentscout.data.models

data class VideoAnalysisResult(
    val testResult: TestResult,
    val angleSamples: List<AngleSample>
)