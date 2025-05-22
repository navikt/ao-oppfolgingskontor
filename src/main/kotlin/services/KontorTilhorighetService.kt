package no.nav.services

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.db.Fnr
import no.nav.db.entity.ArbeidsOppfolgingKontorEntity
import no.nav.db.entity.ArenaKontorEntity
import no.nav.db.entity.GeografiskTilknyttetKontorEntity
import no.nav.db.table.KontorhistorikkTable.fnr
import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.ArenaKontor
import no.nav.domain.GeografiskTilknyttetKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorKilde
import no.nav.domain.KontorNavn
import no.nav.domain.KontorTilhorighet
import no.nav.domain.Registrant
import no.nav.http.graphql.schemas.KontorTilhorighetQueryDto
import no.nav.http.graphql.schemas.RegistrantTypeDto
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import no.nav.db.table.ArbeidsOppfolgingKontorTable
import no.nav.db.table.ArbeidsOppfolgingKontorTable.updatedAt
import no.nav.db.table.ArenaKontorTable
import no.nav.db.table.GeografiskTilknytningKontorTable
import no.nav.db.table.KontorhistorikkTable
import no.nav.domain.Kontor
import no.nav.domain.events.AOKontorEndret
import no.nav.domain.events.ArenaKontorEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.KontorEndretEvent
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpsertStatement
import java.time.ZonedDateTime

class KontorTilhorighetService(
    val kontorNavnService: KontorNavnService
) {
    suspend fun getArbeidsoppfolgingKontorTilhorighet(fnr: Fnr): ArbeidsoppfolgingsKontor? {
        return transaction { getAOKontor(fnr) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) -> ArbeidsoppfolgingsKontor(kontorNavn,kontor.getKontorId()) }
    }

    suspend fun getArenaKontorTilhorighet(fnr: Fnr): ArenaKontor? {
        return transaction { getArenaKontor(fnr) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) -> ArenaKontor(kontorNavn, kontor.getKontorId()) }
    }

    suspend fun getGeografiskTilknyttetKontorTilhorighet(fnr: Fnr): GeografiskTilknyttetKontor? {
        return transaction { getGTKontor(fnr) }
            ?.let { it to kontorNavnService.getKontorNavn(KontorId(it.kontorId)) }
            ?.let { (kontor, kontorNavn) -> GeografiskTilknyttetKontor(kontorNavn,kontor.getKontorId()) }
    }

    suspend fun settKontorTilhorighet(kontorEndring: KontorEndretEvent) {
        val kontorTilhorighet = kontorEndring.tilhorighet
        transaction {
            when (kontorEndring) {
                is AOKontorEndret -> {
                    ArbeidsOppfolgingKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[fnr] = kontorTilhorighet.fnr
                        it[endretAv] = kontorEndring.registrant.getIdent()
                        it[endretAvType] = kontorEndring.registrant.getType()
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                    }
                }
                is ArenaKontorEndret -> {
                    ArenaKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[fnr] = kontorTilhorighet.fnr
                        it[kafkaOffset] = kontorEndring.offset.toInt()
                        it[kafkaPartition] = kontorEndring.partition.toInt()
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                    }
                }
                is GTKontorEndret -> {
                    GeografiskTilknytningKontorTable.upsert {
                        it[kontorId] = kontorTilhorighet.kontorId.id
                        it[fnr] = kontorTilhorighet.fnr
                        it[updatedAt] = ZonedDateTime.now().toOffsetDateTime()
                    }
                }
            }
            settKontorIHistorikk(kontorEndring)
        }
    }

    private fun settKontorIHistorikk(
        kontorEndring: KontorEndretEvent
    ): InsertStatement<Number> {
        val historikkInnslag = kontorEndring.toHistorikkInnslag()
        return KontorhistorikkTable.insert {
            it[kontorId] = historikkInnslag.kontorId.id
            it[fnr] = historikkInnslag.fnr
            it[endretAv] = historikkInnslag.registrant.getIdent()
            it[endretAvType] = historikkInnslag.registrant.getType()
            it[kontorendringstype] = historikkInnslag.kontorendringstype.name
        }
    }

    private fun getGTKontor(fnr: Fnr) = GeografiskTilknyttetKontorEntity.findById(fnr)
    private fun getArenaKontor(fnr: Fnr) = ArenaKontorEntity.findById(fnr)
    private fun getAOKontor(fnr: Fnr) = ArbeidsOppfolgingKontorEntity.findById(fnr)

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