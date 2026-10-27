package com.ledger.custody.persistence

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration
class JdbcConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun transactionTemplate(manager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(manager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }
}
