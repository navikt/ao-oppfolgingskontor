
    const schema = {
  "asyncapi": "3.0.0",
  "info": {
    "title": "Endringer i arbeidsoppfølgingskontor",
    "version": "0.0.1",
    "description": "Endringer i arbeidsoppfølgingskontor\n",
    "contact": {
      "name": "Team Dab",
      "url": "https://nav-it.slack.com/archives/C04HS60F283"
    }
  },
  "defaultContentType": "application/json",
  "channels": {
    "arbeidsoppfolgingskontortilordninger-v1": {
      "address": "arbeidsoppfolgingskontortilordninger-v1",
      "description": "## Endringer på brukers arbeidsoppfølgingskontor\nKey er oppfolgingsperiodeId, en intern uuid som er identifiserer en oppfølgingsperiode tilhørende en bruker. Samme bruker kan dukke opp flere ganger hvis man leser meldinger før de har blitt compacted, men tilslutt vil det maksimalt være 1 kontor per bruker. Når oppfølgingen til en bruker avsluttes (periode får start-dato) så blir meldingen tombstoned (hele verdien blir null) \nTopicen er **compacted** og har **evig retention**.\nDet kommer melding når:\n- Bruker får tilordnet kontor ved oppfølgings startet\n- Bruker blir manuelt flyttet til nytt kontor av en veileder\n- Bruker blir automatisk flyttet fordi hen ble skjermet eller adressebeskyttet\n- Tombstone når en oppfølgingsperiode blir avsluttet (hele valuen til meldingen er null)\n",
      "messages": {
        "kontortilordning": {
          "name": "KontorTilordningMeldingDto",
          "title": "Kontortilordning",
          "summary": "Trenger mer info??",
          "tags": [
            {
              "name": "arbeidsoppfolgingskontortilordninger-v1",
              "description": "Arbeidsoppfolgingskontortilordninger v1"
            }
          ],
          "payload": {
            "required": [
              "kontorNavn",
              "kontorId",
              "oppfolgingsperiodeId",
              "aktorId",
              "ident",
              "tilordningstype"
            ],
            "properties": {
              "kontorNavn": {
                "type": "string"
              },
              "kontorId": {
                "type": "string"
              },
              "oppfolgingsperiodeId": {
                "type": "string"
              },
              "aktorId": {
                "type": "string"
              },
              "ident": {
                "type": "string"
              },
              "tilordningstype": {
                "type": "string",
                "enum": [
                  "KONTOR_VED_OPPFOLGINGSPERIODE_START",
                  "ENDRET_KONTOR"
                ]
              }
            },
            "x-parser-schema-id": "KontorTilordning"
          },
          "x-parser-unique-object-id": "kontortilordning"
        }
      },
      "bindings": {
        "kafka": {
          "topic": "arbeidsoppfolgingskontortilordninger-v1"
        }
      },
      "x-parser-unique-object-id": "arbeidsoppfolgingskontortilordninger-v1"
    }
  },
  "operations": {
    "arbeidsoppfolgingskontortilordninger-v1": {
      "description": "Kontorendringer innad EN oppfølgingperiode compacted på oppfolgingsperiodeId",
      "action": "send",
      "channel": "$ref:$.channels.arbeidsoppfolgingskontortilordninger-v1",
      "x-parser-unique-object-id": "arbeidsoppfolgingskontortilordninger-v1"
    }
  },
  "components": {
    "schemas": {
      "KontorTilordning": "$ref:$.channels.arbeidsoppfolgingskontortilordninger-v1.messages.kontortilordning.payload"
    },
    "messages": {
      "KontorTilordningMeldingDto": "$ref:$.channels.arbeidsoppfolgingskontortilordninger-v1.messages.kontortilordning"
    }
  },
  "x-parser-spec-parsed": true,
  "x-parser-api-version": 3,
  "x-parser-spec-stringified": true
};
    const config = {"show":{"sidebar":true},"sidebar":{"showOperations":"byDefault"}};
    const appRoot = document.getElementById('root');
    AsyncApiStandalone.render(
        { schema, config, }, appRoot
    );
  