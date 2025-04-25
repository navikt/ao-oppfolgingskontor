package kafka.config

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import no.nav.kafka.KafkaStreamsInstance
import no.nav.kafka.config.StreamsLifecycleManager
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi // Nødvendig for test dispatcher
class StreamsLifecycleManagerTest {

 private lateinit var streamMock: KafkaStreams
 private lateinit var manager: StreamsLifecycleManager
 private lateinit var kafkaStreamsInstance: KafkaStreamsInstance

 // Bruk en test dispatcher for å kontrollere tid i coroutines
 private val testDispatcher = StandardTestDispatcher()
 private lateinit var testScope: TestScope // Håndterer test dispatcher

 private lateinit var stateListenerSlot: CapturingSlot<(KafkaStreams.State, KafkaStreams.State) -> Unit>
 private lateinit var uncaughtExceptionHandlerSlot: CapturingSlot<(Throwable) -> StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse>

 @BeforeEach
 fun setUp() {
  testScope = TestScope(testDispatcher) // Opprett scope med test dispatcher
  Dispatchers.setMain(testDispatcher) // Sett hoved-dispatcher (kan være relevant for noen Ktor/coroutine aspekter)

  // Mock KafkaStreams-instanser
  streamMock = mockk(relaxed = true) // relaxed = true ignorerer kall vi ikke har spikret

  // Capture listeners for å kunne trigge dem
  stateListenerSlot = slot()
  uncaughtExceptionHandlerSlot = slot()


  /*
  Mocking med argument capture blir litt mer komplisert fordi setStateListener blir behandlet som en kotlin.jvm.functions.Function2,
  mens den faktiske metoden er en Java lambda, som ikke kan castes til Function2.
  Vi løser det med å bruke any() for å matche argumentet og deretter manuelt caste det til riktig type.
   */
  //every { streamsApp1Mock.setStateListener(capture(stateListenerSlotApp1)) } just Runs
  every { streamMock.setStateListener(any()) } answers {
   val listener = arg<KafkaStreams.StateListener>(0)
   stateListenerSlot.captured = { newState, oldState ->
    listener.onChange(newState, oldState)
   }
  }
  /*
  Samme problem som over
   */
  //every { streamsApp1Mock.setUncaughtExceptionHandler(capture(uncaughtExceptionHandlerSlotApp1)) } returns Unit
  every { streamMock.setUncaughtExceptionHandler(any()) } answers {
   val handler = arg<StreamsUncaughtExceptionHandler>(0)
   uncaughtExceptionHandlerSlot.captured = { throwable ->
    handler.handle(throwable)
   }
  }

  // Opprett manageren (bruk testScope for dens interne scope for å ha kontroll på tiden i dispatcheren),
  // Her bruker vi standard Dispatchers.IO, men kontrollerer tid med testDispatcher
  manager = spyk(StreamsLifecycleManager(testDispatcher), recordPrivateCalls = true) // Spy for å verifisere private metodekall

  kafkaStreamsInstance = KafkaStreamsInstance("App1", streamMock, AtomicBoolean(false))

  // La initialize opprette mocks (eller kall den manuelt hvis du ikke spionerer)
  manager.setupStreamsInstanceLifecycleHandler(kafkaStreamsInstance) // Kaller setStateListener etc. på mockene
 }

 @AfterEach
 fun tearDown() {
  Dispatchers.resetMain() // Rydd opp dispatcher
  // Sikre at alle coroutines i scope'et er ferdige eller kansellert
  testScope.cancel()
 }

 @Test
 fun `initialize should set state listeners`() = testScope.runTest {
  // initialisering ble kalt i setUp
  verify { streamMock.setStateListener(any()) }
  verify { streamMock.setUncaughtExceptionHandler(any()) }
 }

 @Test
 fun `startStreamsApp should call start on streams`() = testScope.runTest {
  manager.startStreamsApp(kafkaStreamsInstance)
  coVerify { streamMock.start() }
  // Verifiser isRunning flag
  assertTrue(kafkaStreamsInstance.isRunningFlag.get())
 }

 @Test
 fun `stopStreamsApp should call close on streams and cancel scope`() = testScope.runTest {
  // Gi en mock state for å unngå NPE hvis state() kalles internt i closeStreamsInstance
  every { streamMock.state() } returns KafkaStreams.State.RUNNING
  kafkaStreamsInstance.isRunningFlag.set(true) // Simuler at den kjører

  manager.closeStreamsInstance(kafkaStreamsInstance)

  // Bruk timeout her siden close() kalles med Duration
  verify(timeout = 1000) { streamMock.close(any<Duration>()) }
  assertFalse(manager.lifecycleScope.isActive)
  // Verifiser at scope ble kansellert (indirekte ved å sjekke jobben eller via logikk)
 // assertFalse((manager invoke "lifecycleScope" as CoroutineScope).isActive)
 }


 @Test
 fun `should schedule restart when state changes to ERROR`() = testScope.runTest {
  val initialRunningFlag = kafkaStreamsInstance.isRunningFlag
  initialRunningFlag.set(true) // Anta at den kjører først

  // Simuler ERROR state
  stateListenerSlot.captured(KafkaStreams.State.ERROR, KafkaStreams.State.RUNNING)

  // Verifiser at scheduleRestart ble kalt (enten direkte eller via spy)
  coVerify { manager.scheduleRestart(kafkaStreamsInstance) }
  assertFalse(initialRunningFlag.get()) // Skal være satt til false
 }

 @Test
 fun `scheduleRestart performs close, delay, and start with backoff`() = testScope.runTest {

  val runningFlag = kafkaStreamsInstance.isRunningFlag
  val initialDelay = manager.initialDelayMillis
  val maxRestarts = manager.maxRestarts

  // --- Første feil og restart ---
  every { streamMock.state() } returns KafkaStreams.State.ERROR // Gi en state for close
  every { streamMock.close(any<Duration>()) } returns true // Simuler vellykket close
  every { streamMock.start() } returns Unit // Simuler vellykket start

  // Utløs restart
  manager.scheduleRestart(kafkaStreamsInstance)

  // Hopp frem i tid forbi den *første* delayen
  advanceTimeBy(initialDelay + 100) // + litt ekstra margin
  runCurrent() // Kjør jobben som ble utsatt

  coVerifyOrder {
   streamMock.close(any<Duration>())
   streamMock.start()
  }
  assertTrue(runningFlag.get()) // Skal være true etter vellykket start
  assertEquals(1, manager.restartCounters.get("App1")?.get())

  // --- Andre feil og restart (simuler at start feilet igjen) ---
  runningFlag.set(false) // Marker som ikke kjørende igjen
  every { streamMock.start() } throws IllegalStateException("Simulated start failure") // Få start til å feile

  // Utløs restart igjen (enten via state listener eller direkte kall for test)
  manager.scheduleRestart(kafkaStreamsInstance)

  // Hopp frem i tid forbi den *andre* delayen (med backoff)
  val secondDelay = (initialDelay * manager.backoffMultiplier).toLong()
  advanceTimeBy(secondDelay + 100)
  runCurrent()

  // Close ble kalt igjen, start feilet (verifisert av exception i mock), prøver neste restart
  coVerify(exactly = 2) { streamMock.close(any<Duration>()) }
  coVerify(exactly = 2) { streamMock.start() } // Første gangen
  // Verifiser at start ble kalt en gang *til* som feilet (eller sjekk logikk)
  // Verifiser at scheduleRestart ble kalt på nytt pga feilen
  coVerify(exactly = 3) { manager.scheduleRestart(kafkaStreamsInstance) }

  // --- Gi opp etter max restarts ---
  for (i in 3..maxRestarts + 1) {
   advanceTimeBy((initialDelay * manager.backoffMultiplier.pow(i - 1)).toLong() + 100)
   runCurrent()
  }

  // Verifiser at scheduleRestart *ikke* ble kalt flere enn maxRestarts+1 ganger (initielle kall + feilende kall)
  coVerify(exactly = maxRestarts) { manager.scheduleRestart(kafkaStreamsInstance) }
  assertFalse(runningFlag.get()) // Skal fortsatt være false
  // Sjekk logger for "Giving up" melding hvis mulig
 }
}