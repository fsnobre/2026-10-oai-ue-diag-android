package br.dev.nobre.oaiuediag.data

sealed interface CellSummary {
    val registered: Boolean
    val radio: String
}

data class NrCellSummary(
    override val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    val nci: Long?,
    val pci: Int?,
    val tac: Int?,
    val nrarfcn: Int?,
    val bands: List<Int>,
    val ssRsrp: Int?,
    val ssRsrq: Int?,
    val ssSinr: Int?,
) : CellSummary {
    override val radio: String = "NR"
}

data class LteCellSummary(
    override val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    val ci: Int?,
    val pci: Int?,
    val tac: Int?,
    val earfcn: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
) : CellSummary {
    override val radio: String = "LTE"
}

data class GenericCellSummary(
    override val registered: Boolean,
    override val radio: String,
    val description: String,
) : CellSummary
