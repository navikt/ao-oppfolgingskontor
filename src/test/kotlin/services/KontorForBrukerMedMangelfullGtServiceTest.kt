package services

import domain.gtForBruker.GtLandForBrukerFunnet
import domain.kontorForGt.KontorForGtFantKontorForArbeidsgiverAdresse
import http.client.AaregSuccess
import http.client.Adresse
import http.client.Ansettelsesperiode
import http.client.ArbeidsforholdDto
import http.client.ArbeidsforholdIdent
import http.client.Arbeidssted
import http.client.EregNøkkelinfoDto
import http.client.EregSuccess
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.KontorId
import no.nav.http.client.AdresseFunnet
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningKommuneNr
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.NorgKontorForGtFantKontor
import no.nav.utils.randomFnr
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class KontorForBrukerMedMangelfullGtServiceTest {

    @Test
    fun `Skal bruke kommunenr fra eereg når det er en kommune som ikke krever bydelsnummer`() = runTest {
        val ident = randomFnr()
        val harSkjerming = HarSkjerming(false)
        val harStrengtFortroligAdresse = HarStrengtFortroligAdresse(false)
        val kontorId = KontorId("6666")
        val brukersGt = GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN"))

        val service = KontorForBrukerMedMangelfullGtService(
            { AaregSuccess(
                data = listOf(
                    gammelArbeidsforhold,
                    nåværendeArbeidsforhold
                )
            ) },
            { arbeidsgiverAdresseSomIkkeKreverBydel },
            { a, b, c -> NorgKontorForGtFantKontor(kontorId) },
            { a, b -> throw NotImplementedError("asd") }
        )

        service.finnKontorForGtBasertPåArbeidsforhold(
            ident,
            brukersGt,
            harStrengtFortroligAdresse,
            harSkjerming,
        ) shouldBe KontorForGtFantKontorForArbeidsgiverAdresse(
            kontorId = kontorId,
            harSkjerming,
            harStrengtFortroligAdresse,
            geografiskTilknytningNr = GeografiskTilknytningKommuneNr(
                arbeidsgiverAdresseSomIkkeKreverBydel.data.adresse?.kommunenummer!!
            ),
            brukersGt,
        )
    }

    @Test
    fun `Skal soke opp adresse i PDL for å finne bydelsnummer når eereg git et kommunenr som krever bydelsnummer`() = runTest {
        val ident = randomFnr()
        val harSkjerming = HarSkjerming(false)
        val harStrengtFortroligAdresse = HarStrengtFortroligAdresse(false)
        val bydelsNr = GeografiskTilknytningBydelNr("8585")
        val kontorId = KontorId("6666")
        val brukersGt = GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN"))

        val service = KontorForBrukerMedMangelfullGtService(
            { AaregSuccess(
                data = listOf(
                    gammelArbeidsforhold,
                    nåværendeArbeidsforhold
                )
            ) },
            { arbeidsgiverAdresseSomKreverBydel },
            { a, b, c -> NorgKontorForGtFantKontor(KontorId("6666")) },
            { a, b -> AdresseFunnet(bydelsNr) }
        )

        service.finnKontorForGtBasertPåArbeidsforhold(
            ident,
            brukersGt,
            harStrengtFortroligAdresse,
            harSkjerming,
        ) shouldBe KontorForGtFantKontorForArbeidsgiverAdresse(
            kontorId,
            harSkjerming,
            harStrengtFortroligAdresse,
            bydelsNr,
            brukersGt,
        )
    }

}

val arbeidsgiverAdresseSomIkkeKreverBydel = EregSuccess(
    EregNøkkelinfoDto(
        Adresse(
            kommunenummer = "0302",
            adresselinje1 = "Bondens gate 202",
            type = "Forretningsadresse"
        )
    )
)
val arbeidsgiverAdresseSomKreverBydel = EregSuccess(
    EregNøkkelinfoDto(
        Adresse(
            kommunenummer = "0301",
            adresselinje1 = "Arbeidergata 2",
            type = "Forretningsadresse"
        )
    )
)

val orgNummer = OrgNummer("3131")
val nåværendeArbeidsforhold = ArbeidsforholdDto(
    arbeidssted = Arbeidssted(
        identer = listOf(
            ArbeidsforholdIdent(
                ident = orgNummer.value,
                type = "ORGANISASJONSNUMMER"
            ),
            ArbeidsforholdIdent(
                ident = "1888",
                type = "ANNEN"
            )
        )
    ),
    ansettelsesperiode = Ansettelsesperiode(
        startdato = LocalDateTime.now().minusDays(2).toString(),
        sluttdato = null
    )
)
val gammelArbeidsforhold = ArbeidsforholdDto(
    arbeidssted = Arbeidssted(
        identer = listOf(
            ArbeidsforholdIdent(
                ident = orgNummer.value,
                type = "ORGANISASJONSNUMMER"
            ),
            ArbeidsforholdIdent(
                ident = "1888",
                type = "ANNEN"
            )
        )
    ),
    ansettelsesperiode = Ansettelsesperiode(
        startdato = LocalDateTime.now().minusDays(2).toString(),
        sluttdato = LocalDateTime.now().minusDays(1).toString(),
    )
)