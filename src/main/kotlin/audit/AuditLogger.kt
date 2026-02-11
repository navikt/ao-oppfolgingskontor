package no.nav.audit

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.*
import io.ktor.server.routing.RoutingContext
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.SystemPrincipal
import no.nav.db.Ident
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Veileder
import no.nav.http.client.poaoTilgang.HarIkkeTilgang
import org.slf4j.LoggerFactory
import kotlin.text.split

private val auditLog = LoggerFactory.getLogger("auditLogger")

/**
 * CEF (Common Event Format) logger for ArcSight audit logging
 * Format: CEF:Version|Device Vendor|Device Product|Device Version|Device Event Class ID|Name|Severity|[Extension]
 */
object AuditLogger {
    private const val CEF_VERSION = "0"
    private const val DEVICE_VENDOR = "ao-oppfolgingskontor"
    private const val DEVICE_PRODUCT = "auditLog"
    private const val DEVICE_VERSION = "1.0"

    /**
     * Extract subject identifier (NavIdent or system name) from principal
     */
    private fun AOPrincipal.getSubjectId(): String {
        return when (this) {
            is NavAnsatt -> this.navIdent.id
            is SystemPrincipal -> this.systemName
        }
    }

    /**
     * Build CEF log message
     */
    private fun buildCefMessage(
        eventClassId: String,
        name: String,
        severity: String,
        extensions: Map<String, String>
    ): String {
        val extensionString = extensions.entries.joinToString(" ") { "${it.key}=${it.value}" }
        return "CEF:$CEF_VERSION|$DEVICE_VENDOR|$DEVICE_PRODUCT|$DEVICE_VERSION|$eventClassId|$name|$severity|$extensionString"
    }

    /**
     * Log access event (read operations)
     */
    private fun logAccess(
        traceId: String,
        principal: AOPrincipal?,
        duid: String,
        decision: Decision,
        description: String,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        val extensions = mutableMapOf(
            "end" to System.currentTimeMillis().toString(),
            "suid" to (principal?.getSubjectId() ?: "UNKNOWN"),
            "duid" to duid,
            "sproc" to traceId,
            "flexString1Label" to "Decision",
            "flexString1" to decision.value
        )

        oppfolgingsperiodeId?.let {
            extensions["flexString2Label"] = "oppfolgingsperiodeId"
            extensions["flexString2"] = it.value.toString()
        }

        val message = buildCefMessage(
            eventClassId = "audit:access",
            name = description,
            severity = "INFO",
            extensions = extensions
        )

        auditLog.info(message)
    }

    /**
     * Log update/create event (write operations)
     */
    fun logUpdate(
        traceId: String,
        principal: AOPrincipal,
        duid: String,
        decision: Decision,
        description: String,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        val extensions = mutableMapOf(
            "end" to System.currentTimeMillis().toString(),
            "suid" to principal.getSubjectId(),
            "duid" to duid,
            "sproc" to traceId,
            "flexString1Label" to "Decision",
            "flexString1" to decision.value
        )

        oppfolgingsperiodeId?.let {
            extensions["flexString2Label"] = "oppfolgingsperiodeId"
            extensions["flexString2"] = it.value.toString()
        }

        val message = buildCefMessage(
            eventClassId = "audit:update",
            name = description,
            severity = "INFO",
            extensions = extensions
        )

        auditLog.info(message)
    }

    /**
     * Log admin operation event
     */
    private fun logAdmin(
        traceId: String,
        principal: AOPrincipal,
        duid: String?,
        decision: Decision,
        eventType: AdminEventType,
        description: String,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        val extensions = mutableMapOf(
            "end" to System.currentTimeMillis().toString(),
            "suid" to principal.getSubjectId(),
            "sproc" to traceId,
            "flexString1Label" to "Decision",
            "flexString1" to decision.value
        )

        duid?.let {
            extensions["duid"] = it
        }

        oppfolgingsperiodeId?.let {
            extensions["flexString2Label"] = "oppfolgingsperiodeId"
            extensions["flexString2"] = it.value.toString()
        }

        val message = buildCefMessage(
            eventClassId = eventType.eventClassId,
            name = description,
            severity = "INFO",
            extensions = extensions
        )

        auditLog.info(message)
    }

    // ===== Utility Functions for Specific Audit Log Types =====

    /**
     * Log when a user sets/changes kontor for a citizen
     * EventClassId: audit:update
     */
    fun logSettKontor(
        auditEntry: AuditEntry,
    ) {
        logUpdate(
            traceId = auditEntry.traceId,
            principal = auditEntry.principal,
            duid = auditEntry.duid.value,
            decision = auditEntry.decision,
            description = "sett kontor",
            oppfolgingsperiodeId = null
        )
    }

    /**
     * Log when a user queries to find which kontor should be assigned to a citizen
     * EventClassId: audit:access
     */
    fun logFinnKontor(
        traceId: String,
        principal: AOPrincipal?,
        duid: Ident,
        decision: Decision,
    ) {
        logAccess(
            traceId = traceId,
            principal = principal,
            duid = duid.value,
            decision = decision,
            description = "finn kontor",
        )
    }

    /**
     * Log when a user reads kontortilhørighet (current kontor assignment) for a citizen
     * EventClassId: audit:access
     */
    fun logLesKontortilhorighet(
        auditEntry: AuditEntry,
    ) {
        logAccess(
            traceId = auditEntry.traceId,
            principal = auditEntry.principal,
            duid = auditEntry.duid.value,
            decision = auditEntry.decision,
            description = "les kontortilhørighet"
        )
    }

    /**
     * Log when a user reads kontorhistorikk (history of kontor changes) for a citizen
     * EventClassId: audit:access
     */
    fun logLesKontorhistorikk(
        traceId: String,
        principal: AOPrincipal,
        duid: Ident,
        decision: Decision
    ) {
        logAccess(
            traceId = traceId,
            principal = principal,
            duid = duid.value,
            decision = decision,
            description = "les kontorhistorikk"
        )
    }

    /**
     * Log admin operation: republish all kontor assignments
     * EventClassId: audit:admin:republish
     */
    fun logAdminRepublishAlleKontorer(
        traceId: String,
        principal: AOPrincipal,
        decision: Decision
    ) {
        logAdmin(
            traceId = traceId,
            principal = principal,
            duid = null,
            decision = decision,
            eventType = AdminEventType.REPUBLISH,
            description = "republiser alle kontorer"
        )
    }

    /**
     * Log admin operation: republish kontor for selected oppfolgingsperioder
     * EventClassId: audit:admin:republish
     */
    fun logAdminRepublishUtvalgtePerioder(
        traceId: String,
        principal: AOPrincipal,
        decision: Decision,
        oppfolgingsperiodeId: OppfolgingsperiodeId
    ) {
        logAdmin(
            traceId = traceId,
            principal = principal,
            duid = null,
            decision = decision,
            eventType = AdminEventType.REPUBLISH,
            description = "republiser utvalgte perioder",
            oppfolgingsperiodeId = oppfolgingsperiodeId
        )
    }

    /**
     * Log admin operation: sync Arena kontor data for a citizen
     * EventClassId: audit:admin:arena-sync
     */
    fun logAdminSyncArenaKontor(
        traceId: String,
        principal: AOPrincipal,
        duid: String,
        decision: Decision
    ) {
        logAdmin(
            traceId = traceId,
            principal = principal,
            duid = duid,
            decision = decision,
            eventType = AdminEventType.ARENA_SYNC,
            description = "sync arena kontor"
        )
    }

    /**
     * Log admin operation: dry-run to find kontor for a citizen (test7ing without side effects)
     * EventClassId: audit:admin:dryrun
     */
    fun logAdminDryrunFinnKontor(
        traceId: String,
        principal: AOPrincipal,
        duid: String,
        decision: Decision,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        logAdmin(
            traceId = traceId,
            principal = principal,
            duid = duid,
            decision = decision,
            eventType = AdminEventType.DRYRUN,
            description = "dry-run finn kontor",
            oppfolgingsperiodeId = oppfolgingsperiodeId
        )
    }

    /**
     * Log admin operation failure when operation affects multiple items
     * EventClassId: varies based on eventType
     */
    fun logAdminOperationFailure(
        traceId: String,
        principal: AOPrincipal,
        eventType: AdminEventType,
        description: String
    ) {
        logAdmin(
            traceId = traceId,
            principal = principal,
            duid = null,
            decision = Decision.DENY,
            eventType = eventType,
            description = description
        )
    }
}

enum class Decision(val value: String) {
    PERMIT("Permit"),
    DENY("Deny")
}

enum class AdminEventType(val eventClassId: String) {
    REPUBLISH("audit:admin:republish"),
    DRYRUN("audit:admin:dryrun"),
    ARENA_SYNC("audit:admin:arena-sync")
}

class AuditEntry(
    val traceId: String,
    val principal: AOPrincipal,
    val duid: Ident,
    val decision: Decision
)

fun HarIkkeTilgang.toAuditEntry(traceId: String): AuditEntry {
    return AuditEntry(
        traceId,
        principal = this.subject,
        this.target,
        Decision.DENY,
    )
}

fun RoutingContext.traceId(): String? {
    return this.call.request.headers["traceparent"]?.split("-")?.getOrNull(1)
}
fun ApplicationCall.traceId(): String? {
    return this.request.headers["traceid"]?.split("-")?.getOrNull(1)
}




