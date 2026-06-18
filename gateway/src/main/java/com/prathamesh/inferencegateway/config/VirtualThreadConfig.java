package com.prathamesh.inferencegateway.config;

import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

@Configuration
public class VirtualThreadConfig {

    private static final Logger logger = LoggerFactory.getLogger(VirtualThreadConfig.class);

    @PostConstruct
    public void logVirtualThreadStatus() {
        /*
         * RATIONALE FOR VIRTUAL THREADS:
         * This gateway uses Java 21 Virtual Threads (spring.threads.virtual.enabled=true) 
         * instead of WebFlux. The gateway's hot path is strictly I/O-bound:
         * 1. Postgres query (Idempotency check)
         * 2. Redis query (Rate limit)
         * 3. gRPC network call to Python Model Server
         * 
         * Virtual threads allow us to write simple, blocking-style code while scaling 
         * perfectly under high concurrency, avoiding the cognitive overhead of reactive operators.
         * The actual compute-bound work (tensor math) is offloaded to the Python Model Server.
         */
        logger.info("Virtual Threads are enabled for the ML Inference Gateway.");
    }
}
