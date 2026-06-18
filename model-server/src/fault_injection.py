import os
import asyncio
import random
import logging
from grpc import StatusCode

logger = logging.getLogger(__name__)

class FaultInjector:
    def __init__(self):
        # Default fault rates if env vars aren't provided
        self._latency_ms = int(os.environ.get("FAULT_LATENCY_MS", "0"))
        self._failure_rate = float(os.environ.get("FAULT_FAILURE_RATE", "0.0"))
        
    async def maybe_inject_faults(self, context):
        """
        Injects faults based on configuration.
        Should be called at the beginning of the inference RPC.
        Raises an exception or sets the context to UNAVAILABLE if a failure is triggered.
        """
        # 1. Failure Injection
        if self._failure_rate > 0.0:
            if random.random() < self._failure_rate:
                logger.warning("Fault injection: simulating service UNAVAILABLE.")
                context.abort(StatusCode.UNAVAILABLE, "Simulated service failure (Fault Injection)")
                
        # 2. Latency Injection
        if self._latency_ms > 0:
            logger.info(f"Fault injection: sleeping for {self._latency_ms} ms.")
            await asyncio.sleep(self._latency_ms / 1000.0)

# Global injector instance
injector = FaultInjector()
