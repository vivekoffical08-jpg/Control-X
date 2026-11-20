package com.controlx.accessibility

data class AccessibilitySnapshot(
    val packageName: String?,
    val nodes: List<AccessibilityNodeSnapshot>,
)

data class AccessibilityNodeSnapshot(
    val nodeId: String,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
)
