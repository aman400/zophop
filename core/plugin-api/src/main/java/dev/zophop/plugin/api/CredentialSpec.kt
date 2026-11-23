package dev.zophop.plugin.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CredentialSpec(
    val typeId: String,
    val displayName: String,
    val description: String = "",
    val fields: List<ConfigFieldSpec> = emptyList(),
) : Parcelable
