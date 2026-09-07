package com.example.tv

import java.io.Serializable

data class TvVideo(
    val id: String,
    val title: String,
    val url: String,
    val duration: String,
    val isLocal: Boolean,
    val thumbnailUrl: String? = null,
    val folder: String? = null,
    var watchedPosition: Long = 0L,
    var totalDuration: Long = 0L
) : Serializable
