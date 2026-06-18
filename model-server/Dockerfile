FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Install dependencies
COPY model-server/pyproject.toml .
RUN pip install --no-cache-dir .

# Copy proto definitions and run protoc
COPY proto/ /proto/
RUN mkdir -p /app/src && python -m grpc_tools.protoc -I/proto --python_out=/app/src --grpc_python_out=/app/src /proto/inference.proto

# Copy application code
COPY model-server/src/ /app/src/

# Expose gRPC port
EXPOSE 50051

# Environment variables
ENV PYTHONPATH=/app/src
ENV PORT=50051

# Run the server
CMD ["python", "src/server.py"]
