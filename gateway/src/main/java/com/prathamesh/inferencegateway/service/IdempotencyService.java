package com.prathamesh.inferencegateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prathamesh.inferencegateway.model.IdempotencyRecord;
import com.prathamesh.inferencegateway.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);
    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the given logic with idempotency.
     * @param idempotencyKey The unique idempotency key provided by the client.
     * @param requestBody The raw or serialized request body used to compute the hash.
     * @param targetType The expected response type class.
     * @param logic The actual work to execute if there is a cache miss.
     * @return The cached or newly computed response.
     */
    public <T> T executeWithIdempotency(String idempotencyKey, Object requestBody, Class<T> targetType, Supplier<T> logic) {
        String requestHash = computeHash(requestBody);

        Optional<IdempotencyRecord> recordOpt = repository.findById(idempotencyKey);

        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            if (!record.getRequestHash().equals(requestHash)) {
                logger.warn("Idempotency key collision with different payload: {}", idempotencyKey);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for a different request payload");
            }
            logger.info("Idempotency cache hit for key: {}", idempotencyKey);
            try {
                return objectMapper.readValue(record.getResponseBody(), targetType);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize cached response for key: {}", idempotencyKey, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read cached response");
            }
        }

        logger.info("Idempotency cache miss for key: {}. Executing logic...", idempotencyKey);
        T result = logic.get();

        try {
            String responseJson = objectMapper.writeValueAsString(result);
            IdempotencyRecord newRecord = new IdempotencyRecord(idempotencyKey, requestHash, responseJson, 200, Instant.now());
            repository.save(newRecord);
        } catch (DataIntegrityViolationException e) {
            // Race condition: another thread inserted the same key while we were processing.
            // We can just log it and return our result, effectively treating it as a success.
            logger.warn("Idempotency key race condition detected for key: {}", idempotencyKey);
        } catch (Exception e) {
            logger.error("Failed to save idempotency record for key: {}", idempotencyKey, e);
            // We don't fail the request if we just fail to cache the response.
        }

        return result;
    }

    private String computeHash(Object requestBody) {
        try {
            String json = objectMapper.writeValueAsString(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to compute request hash", e);
        }
    }

    // Cleanup job to remove idempotency keys older than 24 hours
    @Scheduled(fixedRateString = "PT1H")
    @Transactional
    public void cleanupOldRecords() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            logger.info("Cleaned up {} old idempotency records.", deleted);
        }
    }
}
