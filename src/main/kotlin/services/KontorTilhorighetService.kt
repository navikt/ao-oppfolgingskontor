package no.nav.services

import no.nav.AOPrincipal
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
import no.nav.domain.KontorType
import no.nav.domain.KontorNavn
import no.nav.http.client.IdenterFunnet
import no.nav.http.client.IdenterIkkeFunnet
import no.nav.http.client.IdenterOppslagFeil
import no.nav.http.client.IdenterResult
import no.nav.http.client.poaoTilgang.PoaoTilgangKtorHttpClient
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import services.IdentService

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService,
    val poaoTilgangClient: PoaoTilgangKtorHttpClient,
    val hentAlleIdenter: (Ident) -> IdenterResult,
) {
    val log = LoggerFactory.getLogger(KontorTilhorighetService::class.java)

    suspend fun getKontorTilhorigheter(ident: Ident, principal: AOPrincipal): Triple<ArbeidsoppfolgingsKontor?, ArenaKontor?, GeografiskTilknyttetKontor?> {
        val alleIdenter = hentAlleIdenter(ident).getOrThrow()
        val aokontor = getArbeidsoppfolgingKontorTilhorighet(alleIdenter, principal)
        val arenakontor = getArenaKontorTilhorighet(alleIdenter)
        val gtkontor = getGeografiskTilknyttetKontorTilhorighet(alleIdenter)
        return Triple(aokontor, arenakontor, gtkontor)
    }

    suspend fun getArbeidsoppfolgingKontorTilhorighet(ident: Ident, principal: AOPrincipal): ArbeidsoppfolgingsKontor? {
        val alleIdenter = hentAlleIdenter(ident).getOrThrow()
        return getArbeidsoppfolgingKontorTilhorighet(alleIdenter, principal)
    }
    private suspend  fun getArbeidsoppfolgingKontorTilhorighet(ident: IdenterFunnet, principal: AOPrincipal): ArbeidsoppfolgingsKontor? {
        poaoTilgangClient.harLeseTilgang(principal, ident.foretrukketIdent)
        return getArbeidsoppfolgingKontorTilhorighet(ident)
    }
    private suspend fun getArbeidsoppfolgingKontorTilhorighet(ident: IdenterFunnet): ArbeidsoppfolgingsKontor? {
        return transaction { getAOKontor(ident.identer) }
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

    inline fun <reified T> SizedIterable<T>.firstOrNullOrThrow(identer: List<Ident>, identProvider: (T) -> String): T? {
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

    private fun getGTKontor(identer: List<Ident>) = GeografiskTilknyttetKontorEntity
        .find { GeografiskTilknytningKontorTable.id inList(identer.map { it.value } ) }
        .firstOrNullOrThrow(identer) { it.fnr.value }
    private fun getArenaKontor(identer: List<Ident>) = ArenaKontorEntity
        .find { ArenaKontorTable.id inList(identer.map { it.value } ) }
        .firstOrNullOrThrow(identer) { it.fnr.value }
    private fun getAOKontor(identer: List<Ident>) = ArbeidsOppfolgingKontorEntity
        .find { ArbeidsOppfolgingKontorTable.id inList(identer.map { it.value } ) }
        .firstOrNullOrThrow(identer) { it.fnr.value }

    suspend fun getKontorTilhorighet(ident: Ident, principal: AOPrincipal): KontorTilhorighetQueryDto? {
        poaoTilgangClient.harLeseTilgang(principal, ident)
        // TODO: Hent alle identer her og bruk dem i query
        val identer = hentAlleIdenter(ident).getOrThrow()

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
}

fun ArbeidsOppfolgingKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kontorType = KontorType.ARBEIDSOPPFOLGING,
        registrant = this.endretAv,
        registrantType = RegistrantTypeDto.valueOf(this.endretAvType),
        kontorNavn = navn.navn
    )
}
fun ArenaKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kontorType = KontorType.ARENA,
        registrant = "Arena",
        registrantType = RegistrantTypeDto.ARENA,
        kontorNavn = navn.navn
    )
}
fun GeografiskTilknyttetKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kontorType = KontorType.GEOGRAFISK_TILKNYTNING,
        registrant = "FREG",
        registrantType = RegistrantTypeDto.SYSTEM,
        kontorNavn = navn.navn
    )
}

private fun IdenterResult.getOrThrow(): IdenterFunnet {
    return when (this) {
        is IdenterFunnet -> this
        is IdenterIkkeFunnet -> throw Exception("Fikk ikke hentet identer for ident: ${this.message}")
        is IdenterOppslagFeil -> throw Exception("Fikk ikke hentet identer for ident: ${this.message}")
    }
}
