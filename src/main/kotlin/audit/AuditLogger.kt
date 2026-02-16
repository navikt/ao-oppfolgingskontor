package no.nav.audit

import audit.CefEvent
import audit.Decision
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingContext
import no.nav.AOPrincipal
import no.nav.NavAnsatt
import no.nav.db.Ident
import no.nav.db.IdentSomKanLagres
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.client.poaoTilgang.HarIkkeTilgang
import no.nav.http.client.poaoTilgang.PersonHarTilgang
import no.nav.http.client.poaoTilgang.SystemHarTilgang
import no.nav.http.client.poaoTilgang.TilgangOppslagFeil
import no.nav.http.client.poaoTilgang.TilgangResult
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.text.split

private val auditLog = LoggerFactory.getLogger("auditLogger")

/**
 * CEF (Common Event Format) logger for ArcSight audit logging
 * Format: CEF:Version|Device Vendor|Device Product|Device Version|Device Event Class ID|Name|Severity|[Extension]
 */
object AuditLogger {
    /**
     * Log access event (read operations)
     */
    private fun logAccess(
        traceId: String,
        principal: NavAnsatt,
        duid: Ident,
        decision: Decision,
        description: String,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        auditLog.info(CefEvent(
            description = description,
            navAnsatt = principal,
            target = duid,
            sproc = traceId,
            decision = decision,
            oppfolgingsperiodeId = oppfolgingsperiodeId,
        ).toString())
    }

    /**
     * Log update/create event (write operations)
     */
    fun logUpdate(
        traceId: String,
        principal: NavAnsatt,
        ident: Ident,
        decision: Decision,
        description: String,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        auditLog.info(CefEvent(
            description = description,
            navAnsatt = principal,
            target = ident,
            sproc = traceId,
            decision = decision,
            oppfolgingsperiodeId
        ).toString())
    }

    /**
     * Log admin operation event
     */
    private fun logAdmin(
        traceId: String,
        principal: NavAnsatt,
        duid: IdentSomKanLagres,
        decision: Decision,
        description: String,
        oppfolgingsperiodeId: OppfolgingsperiodeId? = null
    ) {
        val message = CefEvent(
            description = description,
            navAnsatt = principal,
            target = duid,
            sproc = traceId,
            decision = decision,
            oppfolgingsperiodeId
        ).toString()
        auditLog.info(message)
    }

    // ===== Utility Functions for Specific Audit Log Types =====

    /**
     * Log when a user sets/changes kontor for a citizen
     * EventClassId: audit:update
     */
    fun logSettKontor(
        auditEntry: AuditEntry?,
    ) {
        if (auditEntry == null) return
        if (auditEntry.principal !is NavAnsatt) return
        logUpdate(
            traceId = auditEntry.traceId,
            principal = auditEntry.principal,
            ident = auditEntry.duid,
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
        principal: AOPrincipal,
        duid: Ident,
        decision: Decision,
    ) {
        if (principal !is NavAnsatt) return
        logAccess(
            traceId = traceId,
            principal = principal,
            duid = duid,
            decision = decision,
            description = "finn kontor",
        )
    }

    /**
     * Log when a user reads kontortilhørighet (current kontor assignment) for a citizen
     * EventClassId: audit:access
     */
    fun logLesKontortilhorighet(
        auditEntry: AuditEntry?,
    ) {
        if (auditEntry == null) return
        if (auditEntry.principal !is NavAnsatt) return
        logAccess(
            traceId = auditEntry.traceId,
            principal = auditEntry.principal,
            duid = auditEntry.duid,
            decision = auditEntry.decision,
            description = "les kontortilhørighet"
        )
    }

    /**
     * Log when a user reads kontorhistorikk (history of kontor changes) for a citizen
     * EventClassId: audit:access
     */
    fun logLesKontorhistorikk(
        auditEntry: AuditEntry?
    ) {
        if (auditEntry == null) return
        if (auditEntry.principal !is NavAnsatt) return
        logAccess(
            traceId = auditEntry.traceId,
            principal = auditEntry.principal,
            duid = auditEntry.duid,
            decision = auditEntry.decision,
            description = "les kontorhistorikk"
        )
    }

    /**
     * Log admin operation: dry-run to find kontor for a citizen (testing without side effects)
     * EventClassId: audit:admin:dryrun
     */
    fun logAdminDryrunFinnKontor(
        traceId: String,
        principal: NavAnsatt,
        ident: IdentSomKanLagres,
        decision: Decision
    ) {
        logAdmin(
            traceId = traceId,
            principal = principal,
            duid = ident,
            decision = decision,
            description = "finn kontor",
        )
    }
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

fun TilgangResult.toAuditEntry(traceId: String): AuditEntry? {
    return when (this) {
        is HarIkkeTilgang -> return AuditEntry(
            traceId,
            principal = this.subject,
            this.target,
            Decision.Deny,
        )
        is PersonHarTilgang -> AuditEntry(
            traceId = traceId,
            principal = this.subject,
            duid = this.target,
            Decision.Permit,
        )
        is SystemHarTilgang -> null
        is TilgangOppslagFeil -> null
    }
}

fun RoutingContext.traceId(): String {
    return this.call.request.headers["traceparent"]?.split("-")?.getOrNull(1) ?: UUID.randomUUID().toString()
}
fun ApplicationCall.traceId(): String {
    return this.request.headers["traceid"]?.split("-")?.getOrNull(1) ?: UUID.randomUUID().toString()
}
