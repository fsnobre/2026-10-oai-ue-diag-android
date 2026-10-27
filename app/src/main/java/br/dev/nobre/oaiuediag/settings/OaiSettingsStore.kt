package br.dev.nobre.oaiuediag.settings

import android.content.Context
import br.dev.nobre.oaiuediag.data.OaiSettings

class OaiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("oai_settings", Context.MODE_PRIVATE)

    fun load(): OaiSettings {
        return OaiSettings(
            mcc = preferences.getString(KEY_MCC, "").orEmpty(),
            mnc = preferences.getString(KEY_MNC, "").orEmpty(),
            tac = preferences.getString(KEY_TAC, "").orEmpty(),
            nrarfcn = preferences.getString(KEY_NRARFCN, "").orEmpty(),
            pci = preferences.getString(KEY_PCI, "").orEmpty(),
            dnnApnLabel = preferences.getString(KEY_DNN_APN, "").orEmpty(),
            sst = preferences.getString(KEY_SST, "").orEmpty(),
            sd = preferences.getString(KEY_SD, "").orEmpty(),
            notes = preferences.getString(KEY_NOTES, "").orEmpty(),
        )
    }

    fun save(settings: OaiSettings) {
        preferences.edit()
            .putString(KEY_MCC, settings.mcc.trim())
            .putString(KEY_MNC, settings.mnc.trim())
            .putString(KEY_TAC, settings.tac.trim())
            .putString(KEY_NRARFCN, settings.nrarfcn.trim())
            .putString(KEY_PCI, settings.pci.trim())
            .putString(KEY_DNN_APN, settings.dnnApnLabel.trim())
            .putString(KEY_SST, settings.sst.trim())
            .putString(KEY_SD, settings.sd.trim())
            .putString(KEY_NOTES, settings.notes.trim())
            .apply()
    }

    private companion object {
        const val KEY_MCC = "mcc"
        const val KEY_MNC = "mnc"
        const val KEY_TAC = "tac"
        const val KEY_NRARFCN = "nrarfcn"
        const val KEY_PCI = "pci"
        const val KEY_DNN_APN = "dnn_apn"
        const val KEY_SST = "sst"
        const val KEY_SD = "sd"
        const val KEY_NOTES = "notes"
    }
}
