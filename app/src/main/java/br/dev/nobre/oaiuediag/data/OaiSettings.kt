package br.dev.nobre.oaiuediag.data

import kotlinx.serialization.Serializable

@Serializable
data class OaiSettings(
    val mcc: String = "",
    val mnc: String = "",
    val tac: String = "",
    val nrarfcn: String = "",
    val pci: String = "",
    val dnnApnLabel: String = "",
    val sst: String = "",
    val sd: String = "",
    val notes: String = "",
) {
    val hasExpectedCellCriteria: Boolean
        get() = listOf(mcc, mnc, tac, nrarfcn, pci).any { it.isNotBlank() }
}

data class DiagnosisResult(
    val verdict: String,
    val details: List<String>,
)
