package dev.zophop.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestSheet() {
    val sheetState =
        rememberModalBottomSheetState(
            confirmValueChange = { it != SheetValue.Hidden },
        )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
    }
}
