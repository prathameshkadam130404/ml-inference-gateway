# Limitations & Future Work

While this gateway is robust and production-ready for basic tabular inference, several limitations exist that should be addressed before a massive-scale deployment.

### 1. Hardcoded Feature Dimensions
Currently, the gRPC `FeatureVector` and `PredictionRequestDto` strictly enforce a 54-dimensional float array tailored to the Forest Covertype dataset. 
**Future Fix**: Generalize the protobuf schema and Java DTOs to accept dynamic shapes, or use standard `TensorProto` definitions.

### 2. Basic In-Memory Python Batching
The Python server directly maps the repeated protobuf items to a NumPy array for inference. While effective, it does not use advanced dynamic batching libraries like NVIDIA Triton or ONNX Runtime Server. 
**Future Fix**: For GPU-based deployments, replacing the custom Python server with Triton Inference Server would yield significantly higher throughput.

### 3. Redis Single Point of Failure
The `RateLimiterService` relies on a single Redis node. If Redis goes down, the gateway will fail all requests or require a circuit breaker fallback.
**Future Fix**: Deploy Redis in a Highly Available (HA) cluster and implement a fallback mechanism in `RateLimiterService` to allow traffic through if Redis is temporarily unreachable.

### 4. Idempotency Cleanup
The `IdempotencyService` relies on a Spring `@Scheduled` cron job to delete records older than 24 hours. Under extreme load, this could lock the `idempotency_keys` table.
**Future Fix**: Use Postgres Table Partitioning by date and drop partitions, or switch the idempotency store to Redis with automatic TTLs.

### 5. Authentication & Authorization
There is no auth mechanism in the current Spring Boot setup, assuming it runs behind an API Gateway (like Kong or AWS API Gateway).
**Future Fix**: Add Spring Security with OAuth2/JWT validation.
