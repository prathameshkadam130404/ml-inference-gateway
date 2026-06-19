# NOTE: Use a glibc-based image (Debian) for the build stage, NOT Alpine.
# The protobuf-maven-plugin downloads pre-built protoc / protoc-gen-grpc-java
# binaries that are dynamically linked against glibc. On Alpine (musl libc)
# they fail with "program not found or is not executable", breaking the build.
FROM maven:3.9.7-eclipse-temurin-21 AS builder

WORKDIR /app

# First copy the proto directory, since the maven plugin depends on it
COPY proto /proto

# Now copy the gateway source code
COPY gateway/pom.xml /app/
COPY gateway/src /app/src/

# Compile and package (skip tests during docker build for speed)
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/inference-gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080 8081

# Note: Using G1GC and enabling preview (Virtual Threads are standard in 21, but preview flag sometimes kept for safety, not needed in 21)
ENTRYPOINT ["java", "-XX:+UseG1GC", "-jar", "app.jar"]
