package com.engrdynamics.fingerprintapp.models

data class CameraIdInfo(
    val logicalCameraId: String = "",
    val physicalCameraIds: List<String> = emptyList()
)