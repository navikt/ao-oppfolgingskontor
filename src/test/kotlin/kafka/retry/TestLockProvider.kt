package kafka.retry

import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import java.util.Optional

object TestLockProvider: LockProvider {
    override fun lock(lockConfiguration: LockConfiguration): Optional<SimpleLock> {
       return Optional.of(
           SimpleLock { }
       )
    }
}