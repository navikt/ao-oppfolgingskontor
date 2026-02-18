package audit

import no.nav.NavAnsatt
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import java.time.ZonedDateTime

private const val CEF_VERSION = "0"
private const val DEVICE_VENDOR = "ao-oppfolgingskontor"
private const val DEVICE_PRODUCT = "auditLog"
private const val DEVICE_VERSION = "1.0"

data class CefEvent(
    val description: String,
    val navAnsatt: NavAnsatt, // to principal.getSubjectId(),
    val target: Ident,
    val sproc: String, // traceId
    val decision: Decision,
    val oppfolgingsperiodeId: OppfolgingsperiodeId? = null,
) {
    override fun toString(): String {
        val extensionString = mapOf(
            "end" to ZonedDateTime.now().toEpochSecond(),
            "suid" to navAnsatt.navIdent.id,
            "duid" to target.value,
            "flexString1Label" to "Decision",
            "flexString1" to decision.name,
            "sproc" to sproc
        ).entries.joinToString(" ") { "${it.key}=${it.value}" }
        return "CEF:$CEF_VERSION|$DEVICE_VENDOR|$DEVICE_PRODUCT|$DEVICE_VERSION|audit:access|${description}|${Severity.INFO.name}|$extensionString"
    }
}

enum class Decision {
    Permit,
    Deny
}
enum class Severity {
    INFO
}