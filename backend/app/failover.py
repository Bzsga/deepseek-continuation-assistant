"""Bounded, sequential provider attempts for explicitly enabled public service.

Each wire attempt gets a durable dispatch identity before network I/O. A slow
attempt is cancelled and drained before the next provider is started; it remains
an unknown, counted upstream request. No attempt is replayed after recovery.
"""
import asyncio
from dataclasses import dataclass, field, replace
import hashlib
import time

from .failure_artifacts import ProviderTrace
from .provider import ProviderOutcome, organize, prices_configured


@dataclass(frozen=True)
class _Route:
    id: str
    base_url: str
    model: str
    api_key: str = field(repr=False)
    timeout_seconds: float = 90
    context_tokens: int = 64000
    max_output_tokens: int = 4096


# A short health preference, not a task queue or a retry mechanism. Restarting
# clears the preference, while the durable Store ledger still prevents replay.
_COOLDOWNS = {}


def fallback_enabled(settings, snapshot):
    return bool(
        getattr(settings, "fallback_providers", ())
        and snapshot.get("access_mode") == "public"
        and not snapshot.get("mock_mode", settings.mock_mode)
        and not prices_configured(snapshot)
    )


def _configured_routes(settings, snapshot):
    primary = _Route(
        "primary", snapshot["base_url"], snapshot["model"], settings.api_key,
        settings.provider_timeout_seconds, settings.context_tokens,
        settings.max_output_tokens,
    )
    available = {"primary": primary}
    for route in settings.fallback_providers:
        available[route.id] = _Route(
            route.id, route.base_url, route.model, route.api_key,
            route.timeout_seconds, route.context_tokens, route.max_output_tokens,
        )
    order = list(getattr(settings, "provider_route_order", ())) + list(available)
    routes, seen = [], set()
    for route_id in order:
        if route_id in seen or route_id not in available:
            continue
        seen.add(route_id)
        route = available[route_id]
        # Do not send an admitted source scope to a provider with a smaller
        # context/output limit, or silently change the task's output contract.
        if (snapshot["input_bound"] + snapshot["output_bound"] > route.context_tokens
                or snapshot["output_bound"] > route.max_output_tokens):
            continue
        routes.append(route)
    return routes[:3]


def _route_key(route):
    return (route.id, route.base_url, route.model,
            hashlib.sha256(route.api_key.encode("utf-8")).hexdigest())


def _ordered_routes(settings, snapshot):
    now = time.monotonic()
    for key, expires_at in tuple(_COOLDOWNS.items()):
        if expires_at <= now:
            del _COOLDOWNS[key]
    routes = _configured_routes(settings, snapshot)
    # Stable sorting preserves the measured order within healthy/cooling sets.
    # If every route is cooling, this naturally uses the configured order.
    return sorted(routes, key=lambda route: _COOLDOWNS.get(_route_key(route), 0) > now)


def _attempt_timeout(settings, route, *, last):
    limit = 90 if last else settings.fallback_slow_seconds
    return min(float(limit), float(route.timeout_seconds),
               float(settings.provider_timeout_seconds))


def execution_timeout(settings, snapshot):
    """Runner's total bound must cover the serial chain and final ledger I/O."""
    if not fallback_enabled(settings, snapshot):
        return settings.provider_timeout_seconds
    routes = _configured_routes(settings, snapshot)
    if not routes:
        return settings.provider_timeout_seconds
    # The last route may change under the health preference. Use the maximum
    # possible last-route budget rather than relying on a second mutable sort.
    slow = sum(_attempt_timeout(settings, route, last=False) for route in routes)
    extra = max(_attempt_timeout(settings, route, last=True)
                - _attempt_timeout(settings, route, last=False) for route in routes)
    return slow + extra + 5


async def _finish_attempt(store, attempt_id, outcome):
    finishing = asyncio.create_task(asyncio.to_thread(
        store.finish_provider_attempt, attempt_id, outcome))
    try:
        await asyncio.shield(finishing)
    except asyncio.CancelledError:
        # A known reply must be written once even when service shutdown begins
        # while the SQLite thread is committing it.
        await finishing
        raise


def _aggregate(outcome, outcomes, *, message=None):
    known = all(item.usage_known for item in outcomes)
    status = outcome.status
    if status != "completed" and any(item.status == "uncertain" for item in outcomes):
        status = "uncertain"
    return replace(
        outcome, status=status, message=message if message is not None else outcome.message,
        input_tokens=sum(item.input_tokens for item in outcomes),
        output_tokens=sum(item.output_tokens for item in outcomes),
        # This path only serves unpriced count mode. Never apply one route's
        # prices to another route or claim unknown earlier attempts were free.
        cost_micros=0, reservation_floor=0,
        usage_known=known,
        budget_violation=any(item.budget_violation for item in outcomes),
    )


async def organize_with_fallback(settings, snapshot, messages, *, store, job_id,
                                 context_messages=(), trace=None, organize_one=organize):
    if not fallback_enabled(settings, snapshot):
        return await organize_one(settings, snapshot, messages, trace=trace,
                                  **({"context_messages": context_messages} if context_messages else {}))
    trace = trace if trace is not None else ProviderTrace()
    routes = _ordered_routes(settings, snapshot)
    outcomes = []
    for index, route in enumerate(routes):
        metadata = {"ordinal": index, "id": route.id,
                    "base_url": route.base_url, "model": route.model}
        beginning = asyncio.create_task(asyncio.to_thread(
            store.begin_provider_attempt, job_id, metadata))
        try:
            attempt_id = await asyncio.shield(beginning)
        except asyncio.CancelledError:
            # Cancelling to_thread does not roll back a committed dispatch.
            # Drain it and seal that identity, never return it for reuse.
            attempt_id = await beginning
            if attempt_id is not None:
                trace.begin()
                trace.finish("cancelled")
                await _finish_attempt(store, attempt_id, ProviderOutcome(
                    "uncertain", "[attempt_cancelled] 调用准备期间服务已停止，本次派发身份不会重用。", []))
            raise
        if attempt_id is None:
            # Store checks live cancellation/configuration/quota atomically.
            # A denied dispatch must never become an uncounted network call.
            result = ProviderOutcome(
                "failed", "[fallback_stopped] 任务已取消或配置、可用次数发生变化，未发出后续备用调用。", [])
            return _aggregate(result, outcomes) if outcomes else result

        timeout = _attempt_timeout(settings, route, last=index == len(routes) - 1)
        attempt_settings = replace(
            settings, base_url=route.base_url, model=route.model, api_key=route.api_key,
            context_tokens=route.context_tokens, max_output_tokens=route.max_output_tokens,
            provider_timeout_seconds=timeout,
        )
        attempt_snapshot = {**snapshot, "base_url": route.base_url, "model": route.model}
        # Reuse the caller's trace so final failure capture describes the last
        # attempt. Earlier request/response bodies are erased before switching.
        trace.begin()
        try:
            outcome = await asyncio.wait_for(
                organize_one(attempt_settings, attempt_snapshot, messages, trace=trace,
                             **({"context_messages": context_messages} if context_messages else {})),
                timeout=timeout,
            )
        except asyncio.TimeoutError:
            trace.finish("total_timeout")
            outcome = ProviderOutcome(
                "uncertain", "[attempt_timeout] 本线路响应超过切换时限，已停止等待；上游费用仍可能发生。", [])
        except asyncio.CancelledError:
            trace.finish("cancelled")
            await _finish_attempt(store, attempt_id, ProviderOutcome(
                "uncertain", "[attempt_cancelled] 调用期间服务已停止，本次结果尚未确认，不会重发。", []))
            raise
        except Exception:
            # Exception strings may contain request bodies or credentials.
            trace.finish("other")
            outcome = ProviderOutcome(
                "uncertain", "[attempt_interrupted] 本线路调用中断，结果及费用尚未确认。", [])
        confirmed = None
        if outcome.status == "completed":
            _COOLDOWNS.pop(_route_key(route), None)
            message = f"本段使用 {route.model} 完成，共尝试 {len(outcomes) + 1} 条线路。" + outcome.message
            if any(not item.usage_known for item in outcomes):
                message += "此前未确认调用仍已计次，上游费用可能发生。"
            confirmed = _aggregate(outcome, [*outcomes, outcome], message=message)
            # Runner can persist a fully validated reply if shutdown arrives
            # while the attempt-ledger thread is committing its known result.
            # This reference is private, repr-hidden, and reset by trace.begin.
            trace.confirmed_outcome = confirmed
        await _finish_attempt(store, attempt_id, outcome)
        outcomes.append(outcome)
        if confirmed is not None:
            return confirmed
        _COOLDOWNS[_route_key(route)] = time.monotonic() + settings.fallback_cooldown_seconds
        if outcome.budget_violation:
            return _aggregate(outcome, outcomes)
    if outcomes:
        message = f"[fallback_exhausted] 可用线路未返回有效整理结果，本段已尝试 {len(outcomes)} 次并分别计次。"
        if " diagnostic=" in outcomes[-1].message:
            message += " diagnostic=" + outcomes[-1].message.partition(" diagnostic=")[2]
        return _aggregate(outcomes[-1], outcomes, message=message)
    return ProviderOutcome("failed", "[fallback_unavailable] 没有满足本段上下文及输出上限的可用线路，未发出模型请求。", [])
