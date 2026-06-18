package com.prathamesh.inferencegateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.prathamesh.inferencegateway.grpc.generated.InferenceServiceGrpc;

@Configuration
public class GrpcClientConfig {

    @Value("${gateway.grpc.target:localhost:50051}")
    private String grpcTarget;

    @Bean
    public ManagedChannel managedChannel() {
        // In a real prod environment with multiple replicas, we'd use a naming resolver
        return ManagedChannelBuilder.forTarget(grpcTarget)
                .usePlaintext() // No TLS for internal service-to-service communication
                .build();
    }

    @Bean
    public InferenceServiceGrpc.InferenceServiceBlockingStub inferenceServiceStub(ManagedChannel channel) {
        return InferenceServiceGrpc.newBlockingStub(channel);
    }
}
