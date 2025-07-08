package http.client.arbeidssogerregisteret;

import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import no.nav.db.Fnr
import no.nav.http.client.arbeidssogerregisteret.ArbeidssokerregisterClient
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringIkkeFunnet
import kotlin.test.Test

class ArbeidssokerregisterClientTest {

    @Test
    fun `skal poste til riktig url`() = testApplication {
        val url = "http://arbeidssoker-oppslag.test"
        externalServices {
            hosts(url) {
                routing {
                    post("/api/v1/veileder/arbeidssoekerperioder-aggregert") {
                        call.respondText( """
                                [
                                  {
                                    "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                    "startet": {
                                      "tidspunkt": "2021-09-29T11:22:33.444Z",
                                      "utfoertAv": {
                                        "type": "UKJENT_VERDI",
                                        "id": "12345678910"
                                      },
                                      "kilde": "string",
                                      "aarsak": "string",
                                      "tidspunktFraKilde": {
                                        "tidspunkt": "2021-09-29T11:20:33.444Z",
                                        "avviksType": "UKJENT_VERDI"
                                      }
                                    },
                                    "avsluttet": {
                                      "tidspunkt": "2021-09-29T11:22:33.444Z",
                                      "utfoertAv": {
                                        "type": "UKJENT_VERDI",
                                        "id": "12345678910"
                                      },
                                      "kilde": "string",
                                      "aarsak": "string",
                                      "tidspunktFraKilde": {
                                        "tidspunkt": "2021-09-29T11:20:33.444Z",
                                        "avviksType": "UKJENT_VERDI"
                                      }
                                    },
                                    "opplysningerOmArbeidssoeker": [
                                      {
                                        "opplysningerOmArbeidssoekerId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "sendtInnAv": {
                                          "tidspunkt": "2021-09-29T11:22:33.444Z",
                                          "utfoertAv": {
                                            "type": "UKJENT_VERDI",
                                            "id": "12345678910"
                                          },
                                          "kilde": "string",
                                          "aarsak": "string",
                                          "tidspunktFraKilde": {
                                            "tidspunkt": "2021-09-29T11:20:33.444Z",
                                            "avviksType": "UKJENT_VERDI"
                                          }
                                        },
                                        "utdanning": {
                                          "nus": "string",
                                          "bestaatt": "JA",
                                          "godkjent": "JA"
                                        },
                                        "helse": {
                                          "helsetilstandHindrerArbeid": "JA"
                                        },
                                        "annet": {
                                          "andreForholdHindrerArbeid": "JA"
                                        },
                                        "jobbsituasjon": [
                                          {
                                            "beskrivelse": "UKJENT_VERDI",
                                            "detaljer": {
                                              "prosent": "25"
                                            }
                                          }
                                        ],
                                        "profilering": {
                                          "egenvurderinger": [],
                                          "profileringId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "opplysningerOmArbeidssoekerId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "sendtInnAv": {
                                            "tidspunkt": "2021-09-29T11:22:33.444",
                                            "utfoertAv": {
                                              "type": "UKJENT_VERDI",
                                              "id": "12345678910"
                                            },
                                            "kilde": "string",
                                            "aarsak": "string",
                                            "tidspunktFraKilde": {
                                              "tidspunkt": "2021-09-29T11:20:33.444Z",
                                              "avviksType": "UKJENT_VERDI"
                                            }
                                          },
                                          "profilertTil": "UKJENT_VERDI",
                                          "jobbetSammenhengendeSeksAvTolvSisteManeder": true,
                                          "alder": 0,
                                          "egenvurdering": [
                                            {
                                              "profileringId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "egenvurdering": "ANTATT_GODE_MULIGHETER",
                                              "sendtInnAv": {
                                                "tidspunkt": "2021-09-29T11:22:33.444Z",
                                                "utfoertAv": {
                                                  "type": "UKJENT_VERDI",
                                                  "id": "12345678910"
                                                },
                                                "kilde": "string",
                                                "aarsak": "string",
                                                "tidspunktFraKilde": {
                                                  "tidspunkt": "2021-09-29T11:20:33.444Z",
                                                  "avviksType": "UKJENT_VERDI"
                                                }
                                              }
                                            }
                                          ]
                                        }
                                      }
                                    ],
                                    "bekreftelser": [
                                      {
                                        "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "bekreftelsesloesning": "UKJENT_VERDI",
                                        "svar": {
                                          "sendtInnAv": {
                                            "tidspunkt": "2021-09-29T11:22:33.444Z",
                                            "utfoertAv": {
                                              "type": "UKJENT_VERDI",
                                              "id": "12345678910"
                                            },
                                            "kilde": "string",
                                            "aarsak": "string",
                                            "tidspunktFraKilde": {
                                              "tidspunkt": "2021-09-29T11:20:33.444Z",
                                              "avviksType": "UKJENT_VERDI"
                                            }
                                          },
                                          "gjelderFra": "2025-06-18T10:06:50.810Z",
                                          "gjelderTil": "2025-06-18T10:06:50.810Z",
                                          "harJobbetIDennePerioden": true,
                                          "vilFortsetteSomArbeidssoeker": true
                                        }
                                      }
                                    ]
                                  }
                                ]
                            """ .trim(), ContentType.Application.Json)
                    }
                }
            }
        }

        val testClient = createClient {
            install(Logging)
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val client = ArbeidssokerregisterClient(url, testClient)
        val reponse = client.hentProfilering(Fnr("12345678901"))

        (reponse is ProfileringIkkeFunnet) shouldBe true
    }

    @Test
    fun `skal gi profileringfunnet resultat hvis det finnes profilering`() = testApplication {
        val url = "http://arbeidssoker-oppslag.test"
        externalServices {
            hosts(url) {
                routing {
                    post("/api/v1/veileder/arbeidssoekerperioder-aggregert") {
                        call.respondText( """
                                [
                                  {
                                    "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                    "startet": {
                                      "tidspunkt": "2021-09-29T11:22:33.444Z",
                                      "utfoertAv": {
                                        "type": "UKJENT_VERDI",
                                        "id": "12345678910"
                                      },
                                      "kilde": "string",
                                      "aarsak": "string",
                                      "tidspunktFraKilde": {
                                        "tidspunkt": "2021-09-29T11:20:33.444Z",
                                        "avviksType": "UKJENT_VERDI"
                                      }
                                    },
                                    "opplysningerOmArbeidssoeker": [
                                      {
                                        "opplysningerOmArbeidssoekerId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "sendtInnAv": {
                                          "tidspunkt": "2021-09-29T11:22:33.444Z",
                                          "utfoertAv": {
                                            "type": "UKJENT_VERDI",
                                            "id": "12345678910"
                                          },
                                          "kilde": "string",
                                          "aarsak": "string",
                                          "tidspunktFraKilde": {
                                            "tidspunkt": "2021-09-29T11:20:33.444Z",
                                            "avviksType": "UKJENT_VERDI"
                                          }
                                        },
                                        "utdanning": {
                                          "nus": "string",
                                          "bestaatt": "JA",
                                          "godkjent": "JA"
                                        },
                                        "helse": {
                                          "helsetilstandHindrerArbeid": "JA"
                                        },
                                        "annet": {
                                          "andreForholdHindrerArbeid": "JA"
                                        },
                                        "jobbsituasjon": [
                                          {
                                            "beskrivelse": "UKJENT_VERDI",
                                            "detaljer": {
                                              "prosent": "25"
                                            }
                                          }
                                        ],
                                        "profilering": {
                                          "egenvurderinger": [],
                                          "profileringId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "opplysningerOmArbeidssoekerId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "sendtInnAv": {
                                            "tidspunkt": "2021-09-29T11:22:33.444",
                                            "utfoertAv": {
                                              "type": "UKJENT_VERDI",
                                              "id": "12345678910"
                                            },
                                            "kilde": "string",
                                            "aarsak": "string",
                                            "tidspunktFraKilde": {
                                              "tidspunkt": "2021-09-29T11:20:33.444Z",
                                              "avviksType": "UKJENT_VERDI"
                                            }
                                          },
                                          "profilertTil": "ANTATT_BEHOV_FOR_VEILEDNING",
                                          "jobbetSammenhengendeSeksAvTolvSisteManeder": true,
                                          "alder": 0,
                                          "egenvurdering": [
                                            {
                                              "profileringId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "egenvurdering": "ANTATT_GODE_MULIGHETER",
                                              "sendtInnAv": {
                                                "tidspunkt": "2021-09-29T11:22:33.444Z",
                                                "utfoertAv": {
                                                  "type": "UKJENT_VERDI",
                                                  "id": "12345678910"
                                                },
                                                "kilde": "string",
                                                "aarsak": "string",
                                                "tidspunktFraKilde": {
                                                  "tidspunkt": "2021-09-29T11:20:33.444Z",
                                                  "avviksType": "UKJENT_VERDI"
                                                }
                                              }
                                            }
                                          ]
                                        }
                                      }
                                    ],
                                    "bekreftelser": [
                                      {
                                        "periodeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "bekreftelsesloesning": "UKJENT_VERDI",
                                        "svar": {
                                          "sendtInnAv": {
                                            "tidspunkt": "2021-09-29T11:22:33.444Z",
                                            "utfoertAv": {
                                              "type": "UKJENT_VERDI",
                                              "id": "12345678910"
                                            },
                                            "kilde": "string",
                                            "aarsak": "string",
                                            "tidspunktFraKilde": {
                                              "tidspunkt": "2021-09-29T11:20:33.444Z",
                                              "avviksType": "UKJENT_VERDI"
                                            }
                                          },
                                          "gjelderFra": "2025-06-18T10:06:50.810Z",
                                          "gjelderTil": "2025-06-18T10:06:50.810Z",
                                          "harJobbetIDennePerioden": true,
                                          "vilFortsetteSomArbeidssoeker": true
                                        }
                                      }
                                    ]
                                  }
                                ]
                            """ .trim(), ContentType.Application.Json)
                    }
                }
            }
        }

        val testClient = createClient {
            install(Logging)
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val client = ArbeidssokerregisterClient(url, testClient)
        val reponse = client.hentProfilering(Fnr("12345678901"))

        (reponse is ProfileringFunnet) shouldBe true
    }
}
