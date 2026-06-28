package com.bems.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BEMS Spring Boot entry point.
 *
 * <p>The {@code @SpringBootApplication} annotation enables component scanning from the
 * {@code com.bems} root package, auto-configuration, and configuration property scanning.
 */
@SpringBootApplication(scanBasePackages = "com.bems")
public class BemsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BemsApplication.class, args);
    }
}
