package kafka.producers

import Topics
import no.nav.kafka.config.NaisKafkaEnv
import no.nav.kafka.config.createKafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KontorProducer(config: NaisKafkaEnv, val topics: Topics) {

    val kontorTopic = topics.ut.arbeidsoppfolgingskontortilordninger
    val producer = createKafkaProducer(config)

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