package com.prathamesh.inferencegateway.grpc;

import com.prathamesh.inferencegateway.grpc.generated.FeatureVector;
import com.prathamesh.inferencegateway.grpc.generated.InferenceServiceGrpc;
import com.prathamesh.inferencegateway.grpc.generated.PredictBatchRequest;
import com.prathamesh.inferencegateway.grpc.generated.PredictBatchResponse;
import com.prathamesh.inferencegateway.grpc.generated.Prediction;
import com.prathamesh.inferencegateway.model.PredictionRequestDto;
import com.prathamesh.inferencegateway.model.PredictionResponseDto;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class InferenceGrpcClient {

    private static final Logger logger = LoggerFactory.getLogger(InferenceGrpcClient.class);
    private final InferenceServiceGrpc.InferenceServiceBlockingStub inferenceServiceStub;

    public InferenceGrpcClient(InferenceServiceGrpc.InferenceServiceBlockingStub inferenceServiceStub) {
        this.inferenceServiceStub = inferenceServiceStub;
    }

    /**
     * Executes the gRPC call with Resilience4j aspects applied.
     * Order of aspects: Bulkhead -> CircuitBreaker -> Retry
     */
    @Bulkhead(name = "inferenceService")
    @CircuitBreaker(name = "inferenceService")
    @Retry(name = "inferenceService")
    public PredictionResponseDto predictSingle(String requestId, PredictionRequestDto requestDto) {
        FeatureVector featureVector = FeatureVector.newBuilder()
                .setRequestId(requestId)
                .addAllValues(requestDto.getFeatures())
                .build();

        PredictBatchRequest grpcRequest = PredictBatchRequest.newBuilder()
                .addItems(featureVector)
                .build();

        try {
            PredictBatchResponse grpcResponse = inferenceServiceStub.predictBatch(grpcRequest);

            if (grpcResponse.getPredictionsCount() == 0) {
                logger.error("Received empty predictions from model server for request {}", requestId);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Model Server returned empty prediction");
            }

            Prediction prediction = grpcResponse.getPredictions(0);

            return new PredictionResponseDto(
                    prediction.getRequestId(),
                    prediction.getClassProbabilitiesList(),
                    prediction.getPredictedClassIndex(),
                    prediction.getInferenceLatencyMicros()
            );

        } catch (StatusRuntimeException e) {
            logger.error("gRPC call failed for request {}: {}", requestId, e.getStatus());
            throw translateGrpcException(e);
        }
    }

    /**
     * Executes the actual batch request. 
     * Applies Resilience4j aspects to the entire batch rather than per-item.
     */
    @Bulkhead(name = "inferenceService")
    @CircuitBreaker(name = "inferenceService")
    @Retry(name = "inferenceService")
    public PredictBatchResponse predictBatchInternal(PredictBatchRequest grpcRequest) {
        try {
            return inferenceServiceStub.predictBatch(grpcRequest);
        } catch (StatusRuntimeException e) {
            logger.error("gRPC batch call failed: {}", e.getStatus());
            throw translateGrpcException(e);
        }
    }

    private ResponseStatusException translateGrpcException(StatusRuntimeException e) {
        HttpStatus status = switch (e.getStatus().getCode()) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return new ResponseStatusException(status, "Model Server Error: " + e.getStatus().getDescription(), e);
    }
}
