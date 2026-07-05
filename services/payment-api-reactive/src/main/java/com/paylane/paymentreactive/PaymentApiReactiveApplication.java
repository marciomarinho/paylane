package com.paylane.paymentreactive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentApiReactiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApiReactiveApplication.class, args);
    }
}
