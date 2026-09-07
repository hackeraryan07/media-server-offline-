package com.example.tv

import java.util.concurrent.CopyOnWriteArrayList

object TvDataStore {
    val playlist = CopyOnWriteArrayList<TvVideo>()
}
