package br.dev.nobre.oaiuediag.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import br.dev.nobre.oaiuediag.data.CellSummary
import br.dev.nobre.oaiuediag.data.DeviceSummary
import br.dev.nobre.oaiuediag.data.GenericCellSummary
import br.dev.nobre.oaiuediag.data.LteCellSummary
import br.dev.nobre.oaiuediag.data.NetworkSummary
import br.dev.nobre.oaiuediag.data.NrCellSummary
import br.dev.nobre.oaiuediag.data.SubscriptionSummary
import br.dev.nobre.oaiuediag.data.TelemetrySnapshot
import java.time.Instant

class TelephonyReader(private val context: Context) {
    private val telephonyManager =
        context.getSystemService(TelephonyManager::class.java)
    private val subscriptionManager =
        context.getSystemService(SubscriptionManager::class.java)

    fun readSnapshot(): TelemetrySnapshot {
        val errors = mutableListOf<String>()
        val device = DeviceSummary(
            manufacturer = Build.MANUFACTURER.orUnknown(),
            model = Build.MODEL.orUnknown(),
            androidVersion = Build.VERSION.RELEASE.orUnknown(),
            sdk = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT.orUnknown(),
        )

        val subscriptions = readSubscriptions(errors)
        val network = readNetwork(errors)
        val cells = readCells(errors)

        return TelemetrySnapshot(
            timestamp = Instant.now(),
            device = device,
            subscriptions = subscriptions,
            network = network,
            cells = cells,
            errors = errors,
        )
    }

    private fun readSubscriptions(errors: MutableList<String>): List<SubscriptionSummary> {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            errors += "READ_PHONE_STATE not granted; subscription details unavailable."
            return emptyList()
        }

        return runCatching {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().map { info ->
                info.toSummary()
            }
        }.getOrElse {
            errors += "Subscription read failed: ${it.message ?: it::class.java.simpleName}"
            emptyList()
        }
    }

    private fun readNetwork(errors: MutableList<String>): NetworkSummary {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            errors += "READ_PHONE_STATE not granted; network type details may be unavailable."
        }

        return runCatching {
            NetworkSummary(
                dataNetworkType = networkTypeName(telephonyManager.dataNetworkType),
                voiceNetworkType = networkTypeName(telephonyManager.voiceNetworkType),
                isRoaming = telephonyManager.isNetworkRoaming,
                simState = simStateName(telephonyManager.simState),
            )
        }.getOrElse {
            errors += "Network read failed: ${it.message ?: it::class.java.simpleName}"
            NetworkSummary(
                dataNetworkType = "Unavailable",
                voiceNetworkType = "Unavailable",
                isRoaming = false,
                simState = "Unavailable",
            )
        }
    }

    private fun readCells(errors: MutableList<String>): List<CellSummary> {
        val missing = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filterNot(::hasPermission)

        if (missing.isNotEmpty()) {
            errors += "${missing.joinToString()} not granted; cell list unavailable."
            return emptyList()
        }

        return runCatching {
            telephonyManager.allCellInfo.orEmpty()
                .map(::toCellSummary)
                .sortedWith(cellSummaryComparator)
        }.getOrElse {
            errors += "Cell read failed: ${it.message ?: it::class.java.simpleName}"
            emptyList()
        }
    }

    private fun toCellSummary(cellInfo: CellInfo): CellSummary {
        return when (cellInfo) {
            is CellInfoNr -> cellInfo.toNrSummary()
            is CellInfoLte -> cellInfo.toLteSummary()
            else -> GenericCellSummary(
                registered = cellInfo.isRegistered,
                radio = cellInfo.javaClass.simpleName.removePrefix("CellInfo").ifBlank { "Unknown" },
                description = cellInfo.toString(),
            )
        }
    }

    private fun CellInfoNr.toNrSummary(): NrCellSummary {
        val identity = cellIdentity as? CellIdentityNr
        val signal = cellSignalStrength as? CellSignalStrengthNr
        return NrCellSummary(
            registered = isRegistered,
            mcc = identity?.mccString,
            mnc = identity?.mncString,
            nci = identity?.nci?.validLong(),
            pci = identity?.pci?.validInt(),
            tac = identity?.tac?.validInt(),
            nrarfcn = identity?.nrarfcn?.validInt(),
            bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                identity?.bands?.toList().orEmpty()
            } else {
                emptyList()
            },
            ssRsrp = signal?.ssRsrp?.validSignal(),
            ssRsrq = signal?.ssRsrq?.validSignal(),
            ssSinr = signal?.ssSinr?.validSignal(),
        )
    }

    private fun CellInfoLte.toLteSummary(): LteCellSummary {
        val identity = cellIdentity as? CellIdentityLte
        val signal = cellSignalStrength as? CellSignalStrengthLte
        return LteCellSummary(
            registered = isRegistered,
            mcc = identity?.mccString,
            mnc = identity?.mncString,
            ci = identity?.ci?.validInt(),
            pci = identity?.pci?.validInt(),
            tac = identity?.tac?.validInt(),
            earfcn = identity?.earfcn?.validInt(),
            rsrp = signal?.rsrp?.validSignal(),
            rsrq = signal?.rsrq?.validSignal(),
        )
    }

    private fun SubscriptionInfo.toSummary(): SubscriptionSummary {
        return SubscriptionSummary(
            subscriptionId = subscriptionId,
            simSlotIndex = simSlotIndex,
            carrierName = carrierName?.toString()?.ifBlank { null },
            displayName = displayName?.toString()?.ifBlank { null },
            mcc = mccString?.ifBlank { null },
            mnc = mncString?.ifBlank { null },
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private val cellSummaryComparator = compareBy<CellSummary>(
        { if (it.registered) 0 else 1 },
        {
            when (it) {
                is NrCellSummary -> 0
                is LteCellSummary -> 1
                is GenericCellSummary -> 2
            }
        },
    )

    private fun networkTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            19 -> "LTE_CA"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            else -> "Unknown ($type)"
        }
    }

    private fun simStateName(state: Int): String {
        return when (state) {
            TelephonyManager.SIM_STATE_UNKNOWN -> "Unknown"
            TelephonyManager.SIM_STATE_ABSENT -> "Absent"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
            TelephonyManager.SIM_STATE_READY -> "Ready"
            TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently disabled"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card I/O error"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Card restricted"
            else -> "Unknown ($state)"
        }
    }

    private fun Int.validInt(): Int? = takeUnless { it == Int.MAX_VALUE || it == Int.MIN_VALUE || it < 0 }

    private fun Long.validLong(): Long? = takeUnless { it == Long.MAX_VALUE || it == Long.MIN_VALUE || it < 0L }

    private fun Int.validSignal(): Int? = takeUnless { it == Int.MAX_VALUE || it == Int.MIN_VALUE }

    private fun String?.orUnknown(): String = this?.ifBlank { null } ?: "Unknown"
}
