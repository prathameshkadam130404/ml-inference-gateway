package com.prathamesh.inferencegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InferenceGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(InferenceGatewayApplication.class, args);
    }

}
