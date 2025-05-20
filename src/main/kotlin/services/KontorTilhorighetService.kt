package no.nav.services

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.ArenaKontor
import no.nav.domain.GeografiskTilknyttetKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorKilde
import no.nav.domain.KontorNavn
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService
) {
    suspend fun getArbeidsoppfolgingKontorTilhorighet(fnr: Fnr): ArbeidsoppfolgingsKontor? {
        return transaction { ArbeidsOppfolgingKontorEntity.findById(fnr) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) ->
                ArbeidsoppfolgingsKontor(
                    kontorNavn,
                    kontor.getKontorId(),
                )
            }
    }

    suspend fun getArenaKontorTilhorighet(fnr: Fnr): ArenaKontor? {
        return transaction { ArenaKontorEntity.findById(fnr) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) ->
                ArenaKontor(
                    kontorNavn,
                    kontor.getKontorId(),
                )
            }
    }

    suspend fun getGeografiskTilknyttetKontorTilhorighet(fnr: Fnr): GeografiskTilknyttetKontor? {
        return transaction { GeografiskTilknyttetKontorEntity.findById(fnr) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) ->
                GeografiskTilknyttetKontor(
                    kontorNavn,
                    kontor.getKontorId(),
                )
            }
    }

    fun getGTKontor(fnr: Fnr) = GeografiskTilknyttetKontorEntity.findById(fnr)
    fun getArenaKontor(fnr: Fnr) = ArenaKontorEntity.findById(fnr)
    fun getAOKontor(fnr: Fnr) = ArbeidsOppfolgingKontorEntity.findById(fnr)

    suspend fun getKontorTilhorighet(fnr: Fnr): KontorTilhorighetQueryDto? {
        return newSuspendedTransaction {
            val kontorer = coroutineScope {
                awaitAll( /* The ordering is important! */
                    async { getAOKontor(fnr) },
                    async { getArenaKontor(fnr) },
                    async { getGTKontor(fnr) },
                )
            }
            kontorer.firstOrNull { it != null }
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
}

fun ArbeidsOppfolgingKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kilde = KontorKilde.ARBEIDSOPPFOLGING,
        registrant = this.endretAv,
        registrantType = RegistrantTypeDto.valueOf(this.endretAvType),
        kontorNavn = navn.navn
    )
}
fun ArenaKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kilde = KontorKilde.ARENA,
        registrant = "Arena",
        registrantType = RegistrantTypeDto.ARENA,
        kontorNavn = navn.navn
    )
}
fun GeografiskTilknyttetKontorEntity.toKontorTilhorighetQueryDto(navn: KontorNavn): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kilde = KontorKilde.GEOGRAFISK_TILKNYTNING,
        registrant = "FREG",
        registrantType = RegistrantTypeDto.SYSTEM,
        kontorNavn = navn.navn
    )
}