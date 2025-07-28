package services

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Sensitivitet
import no.nav.domain.events.AOKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.AOKontorEndretPgaSkjermingEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetSensitivKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrOppslagFeil
import no.nav.http.client.FnrResult
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseIkkeFunnet
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingIkkeFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.arbeidssogerregisteret.HentProfileringsResultat
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringIkkeFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.http.client.toDefaultGtKontorFunnet
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.AutomatiskKontorRutingService.Companion.VIKAFOSSEN
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtFantLand
import no.nav.services.KontorForGtFeil
import no.nav.services.KontorForGtResultat
import no.nav.services.KontorForGtSuccess
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class AutomatiskKontorRutingServiceTest: DescribeSpec({

    describe("Endring i oppfolgingsperiode") {
        val aktorId = "223456789"

        describe("start oppfolgingsperiode ") {
            it("skal sette AO kontor til lokalkontor for unge brukere (under 30)") {
                gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedGodeMuligheter)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(ungBrukerMedGodeMuligheter.fnr(),  ungBrukerMedGodeMuligheter.gtKontor(), ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()),
                        ingenSensitivitet
                    )
                )
            }

            it("skal sette AO kontor til NOE hvis gode muligheter og over 30 år") {
                gitt(eldreBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(eldreBrukerMedGodeMuligheter)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsperiodeStartetNoeTilordning(
                        eldreBrukerMedGodeMuligheter.fnr(),
                        eldreBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                    )
                )
            }

            it("skal sette AO kontor til fallback (it avdelingen) hvis gt ikke finnes") {
                gitt(brukerSomManglerGt).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerSomManglerGt)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetFallbackKontorTilordning(
                        brukerSomManglerGt.fnr(),
                        brukerSomManglerGt.oppfolgingsperiodeId(),
                        Sensitivitet(
                            HarSkjerming(false),
                            HarStrengtFortroligAdresse(false)
                        )
                    )
                )
            }

            it("skal sette AO kontor til adressebeskyttet kontor hvis adressebeskyttet bruker") {
                gitt(adressebeskyttetBruker).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(adressebeskyttetBruker)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(adressebeskyttetBruker.fnr(), adressebeskyttetBruker.gtKontor(), adressebeskyttetBruker.oppfolgingsperiodeId()),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true)),
                        adressebeskyttetBruker.gtKontor as KontorForGtNrFantDefaultKontor
                    )
                )
            }

            it("skal sette AO kontor til et skjermet kontor hvis skjermet bruker") {
                gitt(skjermetBruker).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(skjermetBruker)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(skjermetBruker.fnr(), skjermetBruker.gtKontor(), skjermetBruker.oppfolgingsperiodeId()),
                        Sensitivitet(
                            HarSkjerming(true),
                            HarStrengtFortroligAdresse(false)
                        ),
                        skjermetBruker.gtKontor as KontorForGtNrFantKontor
                    )
                )
            }

            it("skal sette AO kontor til lokalkontor hvis har antatt behov for veiledening") {
                gitt(ungBrukerMedbehovForVeiledning).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedbehovForVeiledning)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(ungBrukerMedbehovForVeiledning.fnr(), ungBrukerMedbehovForVeiledning.gtKontor(), ungBrukerMedbehovForVeiledning.oppfolgingsperiodeId()),
                        ingenSensitivitet
                    )
                )
            }

            it("skal bruke fallbackkontor hvis bruker har landskode som gt") {
                gitt(brukerMedLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedLandskode)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetFallbackKontorTilordning(
                        brukerMedLandskode.fnr(),
                        brukerMedLandskode.oppfolgingsperiodeId(),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(false))
                    )
                )
            }

            it("skal rute til vikafossen hvis bruker har landskode som gt, men adressebeskyttelse") {
                gitt(brukerMedAdressebeskyttelseOgLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedAdressebeskyttelseOgLandskode)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(brukerMedAdressebeskyttelseOgLandskode.fnr(), VIKAFOSSEN, brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true)),
                        brukerMedAdressebeskyttelseOgLandskode.gtKontor as KontorForGtFantLand
                    )
                )
            }

            it("skal rute til vikafossen hvis bruker mangler gt, men har adressebeskyttelse") {
                gitt(brukerMedAdressebeskyttelseSomManglerGt).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedAdressebeskyttelseSomManglerGt)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(brukerMedAdressebeskyttelseSomManglerGt.fnr(), VIKAFOSSEN, brukerMedAdressebeskyttelseSomManglerGt.oppfolgingsperiodeId()),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true)),
                        brukerMedAdressebeskyttelseSomManglerGt.gtKontor as KontorForGtSuccess
                    )
                )
            }

            it("skal throwe hvis bruker har landskode som gt, men er skjermet") {
                gitt(skjermetBrukerMedLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(skjermetBrukerMedLandskode)
                ) shouldBe TilordningFeil("Feil ved tilordning av kontor: Vi håndterer ikke skjermede brukere uten geografisk tilknytning")
            }

        }

        it("avsluttet oppfolgingsperiode skal ikke sette ao kontor") {
            gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                OppfolgingsperiodeAvsluttet(ungBrukerMedGodeMuligheter.fnr(), ZonedDateTime.now(), OppfolgingsperiodeId(UUID.randomUUID()))
            ) shouldBe TilordningSuccessIngenEndring
        }
    }

    describe("Endring i adressebeskyttelse") {
        it("skal bare endre GT kontor når bruker ikke har strengt fortrolig adresse") {
            gitt(ungBrukerMedGodeMuligheter)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(ungBrukerMedGodeMuligheter.fnr(), Gradering.FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(listOf(
                    GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarStrengtFortroligAdresse(false),
                        ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                    )
                ))
        }

        it("skal sette AO og GT kontor når bruker får strengt fortrolig adresse") {
            gitt(ungBrukerMedGodeMuligheter)
                .handterEndringForAdressebeskyttelse(
                AdressebeskyttelseEndret(ungBrukerMedGodeMuligheter.fnr(), Gradering.STRENGT_FORTROLIG)
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor(),
                        ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                    ),
                    HarStrengtFortroligAdresse(true),
                    ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor(),
                        ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                    )
                )
            ))
        }

        it("skal sette AO og GT kontor når bruker får strengt fortrolig adresse også når bruker har landskode") {
            gitt(brukerMedAdressebeskyttelseOgLandskode)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedAdressebeskyttelseOgLandskode.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseOgLandskode.fnr(),
                        VIKAFOSSEN,
                        brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                    ),
                    HarStrengtFortroligAdresse(true),
                    brukerMedAdressebeskyttelseOgLandskode.gtForBruker as GtForBrukerFunnet
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseOgLandskode.fnr(),
                        VIKAFOSSEN,
                        brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                    )
                )
            ))
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerIkkeUnderOppfolging.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(emptyList())
        }

        it("skal sette ao-kotor og gt-kontor på brukere som mangler GT og har adressebeskyttelse") {
            gitt(brukerMedAdressebeskyttelseSomManglerGt)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedAdressebeskyttelseSomManglerGt.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseSomManglerGt.fnr(),
                        VIKAFOSSEN,
                        brukerMedAdressebeskyttelseSomManglerGt.oppfolgingsperiodeId()
                    ),
                    HarStrengtFortroligAdresse(true),
                    brukerMedAdressebeskyttelseSomManglerGt.gtForBruker as GtForBrukerIkkeFunnet
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseSomManglerGt.fnr(),
                        VIKAFOSSEN,
                        brukerMedAdressebeskyttelseSomManglerGt.oppfolgingsperiodeId()
                    )
                )
            ))
        }
    }

    describe("Endring i skjermingstatus") {

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(true),
                        ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                    ),
                    AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        )
                    ),
                )
            ))
        }

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet også når bruker har landskode") {
            gitt(brukerMedLandskode).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    brukerMedLandskode.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskode.fnr(),
                            INGEN_GT_KONTOR_FALLBACK,
                            brukerMedLandskode.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(true),
                        brukerMedLandskode.gtForBruker as GtLandForBrukerFunnet
                    ),
                    AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskode.fnr(),
                            INGEN_GT_KONTOR_FALLBACK,
                            brukerMedLandskode.oppfolgingsperiodeId()
                        )
                    ),
                )
            ))
        }

        it("skal bare sette GT kontor når bruker blir av-skjermet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(false)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(false),
                        ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                    )
                )
            ))
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging).handterEndringISkjermingStatus(
                SkjermetStatusEndret(brukerIkkeUnderOppfolging.fnr(), HarSkjerming(true))
            ) shouldBe Result.success(EndringISkjermingResult(emptyList()))
        }

        it("skal sette hardkodet-fallback kontor (navit) på ao-kontor og gt-kontor hvis gt ikke finnes og fallback til arbeidsforedeling heller ikke finner kontor og skjerming er true") {
            gitt(brukerSomManglerGt).handterEndringISkjermingStatus(
                SkjermetStatusEndret(brukerSomManglerGt.fnr(), HarSkjerming(true))
            ) shouldBe Result.success(EndringISkjermingResult(listOf(
                GTKontorEndret.endretPgaSkjermingEndret(
                    KontorTilordning(
                        brukerSomManglerGt.fnr(),
                        INGEN_GT_KONTOR_FALLBACK,
                        brukerSomManglerGt.oppfolgingsperiodeId()
                    ),
                    HarSkjerming(true),
                    brukerSomManglerGt.gtForBruker as GtForBrukerIkkeFunnet
                ),
                AOKontorEndretPgaSkjermingEndret(
                    KontorTilordning(
                        brukerSomManglerGt.fnr(),
                        INGEN_GT_KONTOR_FALLBACK,
                        brukerSomManglerGt.oppfolgingsperiodeId()
                    )
                ),
            )))
        }
    }

    describe("Endring i bostedsadresse") {
        it("skal bare sette gt kontor ved adresseendring for bruker uten sensitivitet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringForBostedsadresse(
                BostedsadresseEndret(ungBrukerMedGodeMuligheter.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor(),
                        ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                    ),
                    ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                )
            ))
        }

        it("skal bare sette gt kontor ved adresseendring for bruker med strengt fortrolig adresse") {
            gitt(adressebeskyttetBruker).handterEndringForBostedsadresse(
                BostedsadresseEndret(adressebeskyttetBruker.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        adressebeskyttetBruker.fnr(),
                        adressebeskyttetBruker.gtKontor(),
                        adressebeskyttetBruker.oppfolgingsperiodeId()
                    ),
                    adressebeskyttetBruker.gtForBruker as GtForBrukerFunnet
                )
            ))
        }

        it("skal bare sette gt kontor ved adresseendring for bruker med skjerming") {
            gitt(skjermetBruker).handterEndringForBostedsadresse(
                BostedsadresseEndret(skjermetBruker.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        skjermetBruker.fnr(),
                        skjermetBruker.gtKontor(),
                        skjermetBruker.oppfolgingsperiodeId()
                    ),
                    skjermetBruker.gtForBruker as GtForBrukerFunnet
                )
            ))
        }

        it("skal synce gt kontor med norg for brukere med gt-landskode også") {
            gitt(brukerMedLandskode).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerMedLandskode.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        brukerMedLandskode.fnr(),
                        INGEN_GT_KONTOR_FALLBACK,
                        brukerMedLandskode.oppfolgingsperiodeId()
                    ),
                    brukerMedLandskode.gtForBruker as GtForBrukerFunnet
                )
            ))
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerIkkeUnderOppfolging.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(emptyList())
        }

        it("skal sette hardkodet-fallback kontor hvis gt ikke finner og fallback til arbeidsforedeling også feiler") {
            gitt(brukerSomManglerGt).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerSomManglerGt.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        brukerSomManglerGt.fnr(),
                        INGEN_GT_KONTOR_FALLBACK,
                        brukerSomManglerGt.oppfolgingsperiodeId()
                    ),
                    brukerSomManglerGt.gtForBruker as GtForBrukerIkkeFunnet
                ))
            )
        }
    }

    describe("Feilhåndtering") {
        val fnr = Fnr("12345678901")
        describe("tilordneKontorAutomatisk") {
            feilendeBrukere.map { bruker ->
                gitt(bruker).tilordneKontorAutomatisk(oppfolgingsperiodeStartet(fnr))
            } shouldBe listOf(
                TilordningFeil("Feil ved oppslag på fnr: feil i fnr"),
                TilordningFeil("Kunne ikke hente alder: feil i alder"),
                TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(
                            brukerMedFeilendeProfilering.fnr(),
                            brukerMedFeilendeProfilering.gtKontor(),
                            brukerMedFeilendeProfilering.oppfolgingsperiodeId()
                        ),
                        ingenSensitivitet
                    )
                ),
                TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: feil i skjerming"),
                TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: feil i adressebeskyttelse"),
                TilordningFeil("Feil ved henting av gt-kontor: Feil i gt-kontor oppslag"),
            )
        }

        describe("handterEndringISkjermingStatus") {
            it("handterEndringISkjermingStatus - feil ved henting av adressebeskyttelse skal returnere feil") {
                gitt(brukerMedFeilendeAdressebeskyttelse).handterEndringISkjermingStatus (
                    SkjermetStatusEndret(brukerMedFeilendeAdressebeskyttelse.fnr(), HarSkjerming(true))
                ).isFailure shouldBe true
            }
            it("handterEndringISkjermingStatus - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringISkjermingStatus (
                    SkjermetStatusEndret(brukerMedFeilendeKontorForGt.fnr(), HarSkjerming(true))
                ).isFailure shouldBe true
            }
        }

        describe("handterEndringForAdressebeskyttelse") {
            it("handterEndringForAdressebeskyttelse - feil ved henting av skjerming skal returnere feil") {
                gitt(brukerMedFeilendeSkjerming).handterEndringForAdressebeskyttelse (
                    AdressebeskyttelseEndret(brukerMedFeilendeSkjerming.fnr(), Gradering.STRENGT_FORTROLIG)
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForAdressebeskyttelse - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringForAdressebeskyttelse (
                    AdressebeskyttelseEndret(brukerMedFeilendeKontorForGt.fnr(), Gradering.STRENGT_FORTROLIG)
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
        }

        describe("handterEndringForBostedsadresse") {
            it("handterEndringForBostedsadresse - feil ved henting av adressebeskyttelse skal returnere feil") {
                gitt(brukerMedFeilendeAdressebeskyttelse).handterEndringForBostedsadresse (
                    BostedsadresseEndret(brukerMedFeilendeAdressebeskyttelse.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForBostedsadresse - feil ved henting av skjerming skal returnere feil") {
                gitt(brukerMedFeilendeSkjerming).handterEndringForBostedsadresse (
                    BostedsadresseEndret(brukerMedFeilendeSkjerming.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForAdressebeskyttelse - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringForBostedsadresse (
                    BostedsadresseEndret(brukerMedFeilendeKontorForGt.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
        }
    }
})

fun oppfolgingsperiodeStartet(bruker: Bruker) = oppfolgingsperiodeStartet(bruker.fnr())

fun oppfolgingsperiodeStartet(fnr: Ident): OppfolgingsperiodeStartet {
    return OppfolgingsperiodeStartet(
        fnr,
        ZonedDateTime.now(),
        OppfolgingsperiodeId(UUID.randomUUID())
    )
}

fun gitt(bruker: Bruker): AutomatiskKontorRutingService {
    return AutomatiskKontorRutingService(
        {},
        { _, strengtFortroligAdresse, skjerming ->
            when (bruker.gtKontor) {
                is KontorForGtNrFantKontor -> {
                    val gtNr = when (val gtResult = bruker.gtForBruker) {
                            is GtNummerForBrukerFunnet -> gtResult.gtNr
                            is GtLandForBrukerFunnet -> return@AutomatiskKontorRutingService KontorForGtFantLand(gtResult.land, skjerming, strengtFortroligAdresse)
                            is GtForBrukerIkkeFunnet -> return@AutomatiskKontorRutingService KontorForGtFinnesIkke(skjerming, strengtFortroligAdresse, bruker.gtForBruker)
                            is GtForBrukerOppslagFeil -> return@AutomatiskKontorRutingService KontorForGtFeil("asdsa")
                    }
                    bruker.gtKontor.kontorId.toDefaultGtKontorFunnet(
                        strengtFortroligAdresse,
                        skjerming,
                        gtNr)
                }
                else -> bruker.gtKontor
            }
        },
        { bruker.alder },
        { bruker.profilering },
        { bruker.skjerming },
        { bruker.strengtFortroligAdresse },
        { bruker.oppfolgingsPeriodeResult }
    )
}

fun defaultOppfolgingsperiodeOppslagResult(fnr: FnrResult): OppfolgingsperiodeOppslagResult {
    return when (fnr) {
        is FnrFunnet -> return AktivOppfolgingsperiode(fnr.ident, OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now())
        is FnrIkkeFunnet -> OppfolgingperiodeOppslagFeil(fnr.message)
        is FnrOppslagFeil -> OppfolgingperiodeOppslagFeil(fnr.message)
    }
}

data class Bruker(
    val fnr: FnrResult,
    val alder: AlderResult,
    val profilering: HentProfileringsResultat,
    val gtKontor: KontorForGtResultat,
    val gtForBruker: GtForBrukerResult,
    val skjerming: SkjermingResult,
    val strengtFortroligAdresse: HarStrengtFortroligAdresseResult,
    val oppfolgingsPeriodeResult: OppfolgingsperiodeOppslagResult = defaultOppfolgingsperiodeOppslagResult(fnr)
) {
    fun fnr(): Ident {
        if (fnr is FnrFunnet) {
            return fnr.ident
        }
        throw IllegalStateException("Fnr is ${this.fnr}")
    }
    fun gtKontor(): KontorId {
        if (gtKontor is KontorForGtNrFantKontor) {
            return gtKontor.kontorId
        }
        throw IllegalStateException("gtKontor is ${this.gtKontor}")
    }
    fun oppfolgingsperiodeId(): OppfolgingsperiodeId {
        if (oppfolgingsPeriodeResult is AktivOppfolgingsperiode) {
            return oppfolgingsPeriodeResult.periodeId
        }
        throw IllegalStateException("OppfolgingsperiodeResult is ${this.oppfolgingsPeriodeResult}")
    }
}

val ungBrukerMedGodeMuligheter = Bruker(
    FnrFunnet(Fnr("12345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val eldreBrukerMedGodeMuligheter = Bruker(
    FnrFunnet(Fnr("22345678901")),
    AlderFunnet(31),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val ungBrukerMedbehovForVeiledning = Bruker(
    FnrFunnet(Fnr("32345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_BEHOV_FOR_VEILEDNING),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerSomManglerGt = Bruker(
    FnrFunnet(Fnr("42345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFinnesIkke(HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GtForBrukerIkkeFunnet("Denne brukeren mangler gt")
    ),
    GtForBrukerIkkeFunnet("Denne brukeren mangler gt"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val skjermetBruker = Bruker(
    FnrFunnet(Fnr("52345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(true), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val adressebeskyttetBruker = Bruker(
    FnrFunnet(Fnr("62345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(true), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val skjermetBrukerMedLandskode = Bruker(
    FnrFunnet(Fnr("72345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(true), HarStrengtFortroligAdresse(false)),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)))
val brukerMedLandskode = Bruker(
    FnrFunnet(Fnr("82345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedAdressebeskyttelseOgLandskode = Bruker(
    FnrFunnet(Fnr("92345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(true)),
    GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val brukerMedAdressebeskyttelseSomManglerGt = Bruker(
    FnrFunnet(Fnr("11345678901")),
    AlderFunnet(31), // Hadde blitt rutet til NOE hvis ikke bruker hadde adressebeskytelse
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFinnesIkke(HarSkjerming(false), HarStrengtFortroligAdresse(true), GtForBrukerIkkeFunnet("GT ikke funnet")),
    GtForBrukerIkkeFunnet("GT ikke funnet"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val brukerIkkeUnderOppfolging = Bruker(
    FnrFunnet(Fnr("93345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("4141"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
    NotUnderOppfolging
)

/* Brukere med feil */
val brukerMedFeilendeFnr = Bruker(
    FnrIkkeFunnet("feil i fnr"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
)
val brukerMedFeilendeAlder = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderIkkeFunnet("feil i alder"),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeProfilering = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringIkkeFunnet("feil i profilering"),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeSkjerming = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingIkkeFunnet("feil i skjerming"),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeAdressebeskyttelse = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false), GeografiskTilknytningBydelNr("1111")),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseIkkeFunnet("feil i adressebeskyttelse")
)
val brukerMedFeilendeKontorForGt = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFeil("Feil i gt-kontor oppslag"),
    GtForBrukerOppslagFeil("Testbruker som har feilende gt"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val feilendeBrukere = listOf(
    brukerMedFeilendeFnr,
    brukerMedFeilendeAlder,
    brukerMedFeilendeProfilering,
    brukerMedFeilendeSkjerming,
    brukerMedFeilendeAdressebeskyttelse,
    brukerMedFeilendeKontorForGt
)

val ingenSensitivitet = Sensitivitet(
    HarSkjerming(false),
    HarStrengtFortroligAdresse(false)
)