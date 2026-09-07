package com.example

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun test() {
    ModalBottomSheet(
        onDismissRequest = {},
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0,0,0,0) }
    ) {
        
    }
}
