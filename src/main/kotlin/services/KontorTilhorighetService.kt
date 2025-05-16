package no.nav.services

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorKilde
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService
) {
    suspend fun getArbeidsoppfolgingKontorTilhorighet(fnr: Fnr): ArbeidsoppfolgingsKontor? {
        return transaction {
            ArbeidsOppfolgingKontorEntity.findById(fnr)
        }
            ?.let { kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let {
                ArbeidsoppfolgingsKontor(
                    it.kontorNavn,
                    it.kontorId,
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
                .let { kontor ->
                    when (kontor) {
                        is ArbeidsOppfolgingKontorEntity -> kontor.toKontorTilhorighetQueryDto()
                        is ArenaKontorEntity -> kontor.toKontorTilhorighetQueryDto()
                        is GeografiskTilknyttetKontorEntity -> kontor.toKontorTilhorighetQueryDto()
                        else -> null
                    }
                }
        }
    }
}

fun ArbeidsOppfolgingKontorEntity.toKontorTilhorighetQueryDto(): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kilde = KontorKilde.ARBEIDSOPPFOLGING,
        registrant = this.endretAv,
        registrantType = RegistrantTypeDto.valueOf(this.endretAvType),
    )
}
fun ArenaKontorEntity.toKontorTilhorighetQueryDto(): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kilde = KontorKilde.ARENA,
        registrant = "Arena",
        registrantType = RegistrantTypeDto.ARENA,
    )
}
fun GeografiskTilknyttetKontorEntity.toKontorTilhorighetQueryDto(): KontorTilhorighetQueryDto {
    return KontorTilhorighetQueryDto(
        kontorId = this.kontorId,
        kilde = KontorKilde.GEOGRAFISK_TILKNYTNING,
        registrant = "FREG",
        registrantType = RegistrantTypeDto.SYSTEM,
    )
}