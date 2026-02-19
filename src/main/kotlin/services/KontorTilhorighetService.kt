package no.nav.services

import domain.ArenaKontorUtvidet
import domain.IdenterFunnet
import domain.IdenterResult
import no.nav.db.Ident
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.finnForetrukketIdent
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.ArenaKontor
import no.nav.domain.GeografiskTilknyttetKontor
import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import no.nav.http.graphql.queries.toKontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService,
    val hentAlleIdenter: suspend (Ident) -> IdenterResult,
) {
    val log = LoggerFactory.getLogger(KontorTilhorighetService::class.java)

    suspend fun getKontorTilhorigheter(alleIdenter: IdenterFunnet): Triple<ArbeidsoppfolgingsKontor?, ArenaKontor?, GeografiskTilknyttetKontor?> {
        val aokontor = getArbeidsoppfolgingKontorTilhorighet(alleIdenter)
        val arenakontor = getArenaKontorTilhorighet(alleIdenter)
        val gtkontor = getGeografiskTilknyttetKontorTilhorighet(alleIdenter)
        return Triple(aokontor, arenakontor, gtkontor)
    }

    suspend fun getArbeidsoppfolgingKontorTilhorighet(ident: Ident)
        = getArbeidsoppfolgingKontorTilhorighet(hentAlleIdenter(ident).getOrThrow())

    suspend fun getArenaKontorTilhorighet(ident: Ident)
        = getArenaKontorTilhorighet(hentAlleIdenter(ident).getOrThrow())

    /* Nåværedne arena-kontor med oppfølgingsperiode og sistEndretDatoArena */
    suspend fun getArenaKontorMedOppfolgingsperiode(ident: Ident): ArenaKontorUtvidet? {
        val alleIdenter = hentAlleIdenter(ident).getOrThrow()
        val arenaKontor = getArenaKontor(alleIdenter.identer) ?: return null
        val oppfolgingsperiode = transaction {
            arenaKontor.historikkEntry?.oppfolgingsperiode?.let { OppfolgingsperiodeId(it) }
        }
        return ArenaKontorUtvidet(
            KontorId(arenaKontor.kontorId),
             oppfolgingsperiode,
            arenaKontor.sistEndretDatoArena
        )
    }

    suspend fun getKontorTilhorighet(identer: IdenterFunnet): KontorTilhorighetQueryDto? {
        val kontorer = transaction {
            /* The ordering is important! */
            listOf(
                getAOKontor(identer.identer),
                getArenaKontor(identer.identer),
                getGTKontor(identer.identer),
            )
        }
        return kontorer.firstOrNull { it != null }
            ?.let {
                val kontorNavn = kontorNavnService.getKontorNavn(it.getKontorId())
                it to kontorNavn
            }
            ?.let { (kontor, kontorNavn) ->
                when (kontor) {
                    is ArbeidsOppfolgingKontorEntity -> kontor.toKontorTilhorighetQueryDto(kontorNavn)
                    is ArenaKontorEntity -> kontor.toKontorTilhorighetQueryDto(kontorNavn)
                    is GeografiskTilknyttetKontorEntity -> kontor.toKontorTilhorighetQueryDto(kontorNavn)
                }
            }
    }

    private suspend fun getArbeidsoppfolgingKontorTilhorighet(identer: IdenterFunnet): ArbeidsoppfolgingsKontor? {
        return transaction { getAOKontor(identer.identer) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) -> ArbeidsoppfolgingsKontor(kontorNavn,kontor.getKontorId()) }
    }
    private suspend fun getArenaKontorTilhorighet(ident: IdenterFunnet): ArenaKontor? {
        return transaction { getArenaKontor(ident.identer) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) -> ArenaKontor(kontorNavn, kontor.getKontorId()) }
    }
    private suspend fun getGeografiskTilknyttetKontorTilhorighet(ident: IdenterFunnet): GeografiskTilknyttetKontor? {
        return transaction { getGTKontor(ident.identer) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) -> GeografiskTilknyttetKontor(kontorNavn,kontor.getKontorId()) }
    }

    private inline fun <reified T> SizedIterable<T>.firstOrNullOrThrow(identer: List<Ident>, identProvider: (T) -> String): T? {
        val historiskeIdenter = identer.filter { it.historisk == Ident.HistoriskStatus.HISTORISK }.map { it.value }
        val withoutHistorisk = this.filter { !historiskeIdenter.contains(identProvider(it)) }
        return when (withoutHistorisk.size) {
            0 -> null
            1 ->  this.first()
            else -> { // Har flere nåværende kontor på en person
                log.error("Fant flere ressurser på en person, ressurstype ${T::class.simpleName}")
                return identer.finnForetrukketIdent()
                    ?.let { foretrukketIdent -> this.firstOrNull { identProvider(it) == foretrukketIdent.value } }
                    ?: throw IllegalStateException("Fant flere ressurser på 1 person men ingen av dem bruker foretrukket ident, ressurstype:${T::class.simpleName}")
            }
        }
    }

    private fun getGTKontor(identer: List<Ident>) = transaction { GeografiskTilknyttetKontorEntity
        .find { GeografiskTilknytningKontorTable.id inList(identer.map { it.value } ) }
        .firstOrNullOrThrow(identer) { it.fnr.value }
    }
    private fun getArenaKontor(identer: List<Ident>) = transaction {
        ArenaKontorEntity
            .find { ArenaKontorTable.id inList (identer.map { it.value }) }
            .firstOrNullOrThrow(identer) { it.fnr.value }
    }
    private fun getAOKontor(identer: List<Ident>) = transaction {
        ArbeidsOppfolgingKontorEntity
            .find { ArbeidsOppfolgingKontorTable.id inList (identer.map { it.value }) }
            .firstOrNullOrThrow(identer) { it.fnr.value }
    }
}

