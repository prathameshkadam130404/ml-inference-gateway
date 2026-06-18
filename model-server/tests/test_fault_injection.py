import pytest
import asyncio
import os
import sys
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '../src')))
from unittest.mock import MagicMock
from grpc import StatusCode
from fault_injection import FaultInjector

@pytest.mark.asyncio
async def test_no_faults():
    injector = FaultInjector()
    injector._latency_ms = 0
    injector._failure_rate = 0.0
    
    context = MagicMock()
    await injector.maybe_inject_faults(context)
    context.abort.assert_not_called()

@pytest.mark.asyncio
async def test_failure_injection():
    injector = FaultInjector()
    injector._latency_ms = 0
    injector._failure_rate = 1.0 # 100% failure rate
    
    context = MagicMock()
    await injector.maybe_inject_faults(context)
    context.abort.assert_called_once_with(StatusCode.UNAVAILABLE, "Simulated service failure (Fault Injection)")

@pytest.mark.asyncio
async def test_latency_injection():
    injector = FaultInjector()
    injector._latency_ms = 100 # 100ms
    injector._failure_rate = 0.0
    
    context = MagicMock()
    
    import time
    start = time.time()
    await injector.maybe_inject_faults(context)
    elapsed = time.time() - start
    
    assert elapsed >= 0.1
    context.abort.assert_not_called()
