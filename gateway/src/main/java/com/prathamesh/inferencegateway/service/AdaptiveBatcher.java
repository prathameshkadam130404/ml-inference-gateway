package com.prathamesh.inferencegateway.service;

import com.prathamesh.inferencegateway.grpc.InferenceGrpcClient;
import com.prathamesh.inferencegateway.grpc.generated.FeatureVector;
import com.prathamesh.inferencegateway.grpc.generated.PredictBatchRequest;
import com.prathamesh.inferencegateway.grpc.generated.PredictBatchResponse;
import com.prathamesh.inferencegateway.grpc.generated.Prediction;
import com.prathamesh.inferencegateway.model.PredictionRequestDto;
import com.prathamesh.inferencegateway.model.PredictionResponseDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class AdaptiveBatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveBatcher.class);

    private final InferenceGrpcClient grpcClient;

    @Value("${gateway.batching.max-batch-size:16}")
    private int maxBatchSize;

    @Value("${gateway.batching.max-batch-wait-ms:15}")
    private long maxBatchWaitMs;

    @Value("${gateway.batching.max-queue-depth:200}")
    private int maxQueueDepth;

    private BlockingQueue<BatchItem> queue;
    private ExecutorService batchWorker;
    private volatile boolean isRunning = true;
    private final MeterRegistry meterRegistry;

    public AdaptiveBatcher(InferenceGrpcClient grpcClient, MeterRegistry meterRegistry) {
        this.grpcClient = grpcClient;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        this.queue = new ArrayBlockingQueue<>(maxQueueDepth);
        
        // Gauge for current queue size
        meterRegistry.gauge("gateway.batcher.queue.size", queue, BlockingQueue::size);
        
        // Use a Virtual Thread to constantly poll the queue and dispatch batches.
        // This thread orchestrates the dispatch of multiple concurrent batches if needed.
        this.batchWorker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        this.batchWorker.submit(this::processQueue);
        
        logger.info("AdaptiveBatcher initialized. maxBatchSize={}, maxBatchWaitMs={}, maxQueueDepth={}",
                maxBatchSize, maxBatchWaitMs, maxQueueDepth);
    }

    @PreDestroy
    public void shutdown() {
        isRunning = false;
        batchWorker.shutdownNow();
    }

    /**
     * Called by Virtual Threads (Controller level).
     * Submits an item to the batcher and blocks until the background batch thread completes the gRPC call.
     */
    public PredictionResponseDto submit(String requestId, PredictionRequestDto requestDto) {
        meterRegistry.counter("gateway.requests.submitted").increment();
        CompletableFuture<PredictionResponseDto> future = new CompletableFuture<>();
        BatchItem item = new BatchItem(requestId, requestDto, future);

        if (!queue.offer(item)) {
            logger.warn("Batch queue is full (maxQueueDepth={}). Rejecting request {}.", maxQueueDepth, requestId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gateway is overwhelmed. Queue is full.");
        }

        try {
            // Because the caller is a Virtual Thread, blocking on a Future is extremely cheap.
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Request interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ResponseStatusException rse) {
                throw rse;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to execute batched prediction", cause);
        }
    }

    private void processQueue() {
        while (isRunning) {
            try {
                List<BatchItem> currentBatch = new ArrayList<>(maxBatchSize);
                
                // Wait for the first item to arrive (blocking)
                BatchItem firstItem = queue.poll(1, TimeUnit.SECONDS);
                if (firstItem == null) {
                    continue; // Timeout, loop again
                }
                currentBatch.add(firstItem);
                
                // Drain up to maxBatchSize - 1, or wait up to maxBatchWaitMs
                long startTime = System.currentTimeMillis();
                while (currentBatch.size() < maxBatchSize) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long remainingTime = maxBatchWaitMs - elapsed;
                    
                    if (remainingTime <= 0) {
                        break; // Max wait time reached
                    }
                    
                    BatchItem nextItem = queue.poll(remainingTime, TimeUnit.MILLISECONDS);
                    if (nextItem != null) {
                        currentBatch.add(nextItem);
                    }
                }
                
                // Execute batch asynchronously so the main poll loop can continue 
                // forming new batches while the gRPC call is in flight.
                Thread.startVirtualThread(() -> dispatchBatch(currentBatch));
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in batch processing loop", e);
            }
        }
    }

    private void dispatchBatch(List<BatchItem> batch) {
        logger.debug("Dispatching batch of size {}", batch.size());
        meterRegistry.summary("gateway.batch.size").record(batch.size());
        
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Map BatchItem to gRPC FeatureVector
            List<FeatureVector> featureVectors = batch.stream()
                    .map(item -> FeatureVector.newBuilder()
                            .setRequestId(item.requestId())
                            .addAllValues(item.requestDto().getFeatures())
                            .build())
                    .toList();

            PredictBatchRequest grpcRequest = PredictBatchRequest.newBuilder()
                    .addAllItems(featureVectors)
                    .build();

            // Execute via GrpcClient (which is protected by Resilience4j Bulkhead & CircuitBreaker)
            PredictBatchResponse grpcResponse = grpcClient.predictBatchInternal(grpcRequest);

            // Correlate results and complete futures
            for (Prediction prediction : grpcResponse.getPredictionsList()) {
                // Find matching item
                BatchItem matchedItem = batch.stream()
                        .filter(i -> i.requestId().equals(prediction.getRequestId()))
                        .findFirst()
                        .orElse(null);

                if (matchedItem != null) {
                    PredictionResponseDto dto = new PredictionResponseDto(
                            prediction.getRequestId(),
                            prediction.getClassProbabilitiesList(),
                            prediction.getPredictedClassIndex(),
                            prediction.getInferenceLatencyMicros()
                    );
                    matchedItem.future().complete(dto);
                    batch.remove(matchedItem);
                }
            }
            
            // Any items left in the batch didn't get a response
            for (BatchItem item : batch) {
                item.future().completeExceptionally(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No prediction returned for this request"));
            }

        } catch (Exception e) {
            logger.error("Batch dispatch failed", e);
            // Fail all futures in this batch
            for (BatchItem item : batch) {
                item.future().completeExceptionally(e);
            }
        } finally {
            sample.stop(meterRegistry.timer("gateway.batch.dispatch.time"));
        }
    }

    private record BatchItem(String requestId, PredictionRequestDto requestDto, CompletableFuture<PredictionResponseDto> future) {}
}
