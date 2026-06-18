package com.prathamesh.inferencegateway.loadtest;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import java.time.Duration;

public class InferenceSimulation extends Simulation {

    HttpProtocolBuilder httpProtocol = http
        .baseUrl("http://host.docker.internal:8080")
        .acceptHeader("application/json")
        .contentTypeHeader("application/json");

    Random random = new Random();

    Iterator<Map<String, Object>> featureGenerator = java.util.stream.Stream.generate((Supplier<Map<String, Object>>) () -> {
        Map<String, Object> map = new HashMap<>();
        map.put("idempotencyKey", UUID.randomUUID().toString());
        map.put("clientId", "client-" + random.nextInt(100));
        
        String features = IntStream.range(0, 54)
                .mapToDouble(i -> random.nextFloat())
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        
        map.put("features", features);
        return map;
    }).iterator();

    ScenarioBuilder scn = scenario("Covtype ML Inference Request")
        .feed(featureGenerator)
        .exec(http("Predict Endpoint")
            .post("/api/v1/predict")
            .header("Idempotency-Key", "#{idempotencyKey}")
            .header("X-Client-Id", "#{clientId}")
            .body(StringBody("{\"features\": #{features}}")).asJson()
            .check(status().in(200, 429, 503))
        );

    {
        setUp(
            scn.injectOpen(
                rampUsersPerSec(10).to(500).during(Duration.ofSeconds(30)),
                constantUsersPerSec(500).during(Duration.ofSeconds(60))
            )
        ).protocols(httpProtocol);
    }
}
