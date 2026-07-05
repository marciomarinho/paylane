package com.paylane.paymentreactive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class TxConfig {

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager tm) {
        return TransactionalOperator.create(tm);
    }
}
