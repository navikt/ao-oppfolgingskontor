package services

import kafka.producers.KontorEndringProducer

class KontorRepubliseringService(
    val kafkaProducer: KontorEndringProducer
) {

    fun republiserKontorer() {

        // Hent alle arbeidsoppfølgingskontorer
        // Map til melding for Kafka publisering (TODO: meldingen bør inneholde et tidspunkt for når kontoret ble satt?)
        kafkaProducer.publiserEndringPåKontor()
    }
}