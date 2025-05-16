package no.nav.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.entity.KontorEntity
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorKilde
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import no.nav.http.logger
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService
) {
    suspend fun getArbeidsoppfolgingKontorTilhorighet(fnr: Fnr): ArbeidsoppfolgingsKontor? {
        return newSuspendedTransaction {
            ArbeidsOppfolgingKontorEntity.findById(fnr)
                ?.let { kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
                ?.let {
                    ArbeidsoppfolgingsKontor(
                        it.kontorNavn,
                        it.kontorId,
                    )
                }
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
            logger.info("kontorer liste: ${kontorer.size}")
            logger.info("kontorer liste: ${kontorer.filterNotNull().size}")
            logger.info("kontorer liste: ${kontorer}")
            kontorer.firstOrNull { it != null }
                .let { kontor ->
                    when (kontor) {
                        is ArbeidsOppfolgingKontorEntity ->
                            KontorTilhorighetQueryDto(
                                kontorId = kontor.kontorId,
                                kilde = KontorKilde.ARBEIDSOPPFOLGING,
                                registrant = kontor.endretAv,
                                registrantType = RegistrantTypeDto.valueOf(kontor.endretAvType),
                            )
                        is ArenaKontorEntity ->
                            KontorTilhorighetQueryDto(
                                kontorId = kontor.kontorId,
                                kilde = KontorKilde.ARENA,
                                registrant = "Arena",
                                registrantType = RegistrantTypeDto.ARENA,
                            )
                        is GeografiskTilknyttetKontorEntity ->
                            KontorTilhorighetQueryDto(
                                kontorId = kontor.kontorId,
                                kilde = KontorKilde.GEOGRAFISK_TILKNYTNING,
                                registrant = "FREG",
                                registrantType = RegistrantTypeDto.SYSTEM,
                            )
                        else -> null
                    }
                }
        }
    }
}