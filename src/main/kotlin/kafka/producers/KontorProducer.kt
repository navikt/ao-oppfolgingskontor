package kafka.producers

import Topics
import no.nav.kafka.config.createKafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KontorProducer(val topics: Topics) {

    val kontorTopic = topics.ut.arbeidsoppfolgingskontortilordninger
    val producer = createKafkaProducer()

    fun publiserEndringPåKontor(input: String) {
        val key = "123"
        val value = input
        val record = ProducerRecord(
            kontorTopic.name,
            key,
            value
        )
        producer.send(record)
    }
}