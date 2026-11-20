package com.controlx.domain

data class ActionRecord(
    val action: AgentAction,
    val snapshotFingerprint: String,
    val timestampMillis: Long,
)
