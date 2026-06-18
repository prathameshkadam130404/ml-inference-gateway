package com.prathamesh.inferencegateway.controller;

import com.prathamesh.inferencegateway.grpc.InferenceGrpcClient;
import com.prathamesh.inferencegateway.model.PredictionRequestDto;
import com.prathamesh.inferencegateway.model.PredictionResponseDto;
import com.prathamesh.inferencegateway.service.IdempotencyService;
import com.prathamesh.inferencegateway.service.RateLimiterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/predict")
public class PredictionController {

    private final IdempotencyService idempotencyService;
    private final RateLimiterService rateLimiterService;
    private final AdaptiveBatcher batcher;

    public PredictionController(IdempotencyService idempotencyService,
                                RateLimiterService rateLimiterService,
                                AdaptiveBatcher batcher) {
        this.idempotencyService = idempotencyService;
        this.rateLimiterService = rateLimiterService;
        this.batcher = batcher;
    }

    @PostMapping
    public ResponseEntity<PredictionResponseDto> predict(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId,
            @Valid @RequestBody PredictionRequestDto requestDto) {

        // 1. Rate Limiting (Fast fail before DB or Network I/O)
        rateLimiterService.checkRateLimit(clientId);

        // 2. Execute via Idempotency Service
        PredictionResponseDto response = idempotencyService.executeWithIdempotency(
                idempotencyKey,
                requestDto,
                PredictionResponseDto.class,
                () -> {
                    // 3. Inference Logic (If Cache Miss)
                    // We generate a trace-level request ID to correlate backend logs
                    String backendRequestId = UUID.randomUUID().toString();
                    return batcher.submit(backendRequestId, requestDto);
                }
        );

        return ResponseEntity.ok()
                .header("Idempotency-Key", idempotencyKey)
                .body(response);
    }
}
