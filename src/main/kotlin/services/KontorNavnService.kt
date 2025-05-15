package no.nav.services

import no.nav.domain.ArbeidsoppfolgingsKontor
import no.nav.domain.KontorId
import no.nav.domain.KontorNavn
import no.nav.http.client.Norg2Client

class KontorNavnService(
    val norg2Client: Norg2Client
) {
    // Skal på et senere tidspunkt cache alle navn i en tabell men foreløpig bare henter vi den fra Norg løpende
    suspend fun getKontorNavn(kontorId: KontorId): ArbeidsoppfolgingsKontor {
        return norg2Client.hentKontor(kontorId)
            .let {
                ArbeidsoppfolgingsKontor(
                    kontorNavn = KontorNavn(it.navn),
                    kontorId = KontorId(it.kontorId)
                )
            }
    }
}