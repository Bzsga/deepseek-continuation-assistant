import asyncio
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings
from .admin_access import build_admin_router, initialize_admin
from .feedback import FeedbackStore, MAX_FEEDBACK_BODY_BYTES, build_feedback_router
from .diagnostics import DiagnosticsStore, MAX_DIAGNOSTIC_BODY_BYTES, build_diagnostics_router
from .runner import Runner, ServiceLease
from .schemas import JobRequest, RedeemRequest, MAX_SOURCE_CHARACTERS, MAX_SOURCE_MESSAGES
from .store import ServiceError, Store


class BoundedBodyMiddleware:
    def __init__(self, app, max_bytes):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] not in {"POST", "PUT", "PATCH"}:
            return await self.app(scope, receive, send)
        content = bytearray()
        max_bytes = (MAX_FEEDBACK_BODY_BYTES if scope["method"] == "POST" and scope.get("path") == "/v1/feedback"
                     else self.max_bytes)
        if scope["method"] == "POST" and scope.get("path") == "/v1/diagnostics":
            max_bytes = min(max_bytes, MAX_DIAGNOSTIC_BODY_BYTES)
        while True:
            try:
                event = await asyncio.wait_for(receive(), timeout=15)
            except asyncio.TimeoutError:
                return await JSONResponse({"detail": "上传请求超时"}, status_code=408)(scope, receive, send)
            if event["type"] == "http.disconnect":
                return
            chunk = event.get("body", b"")
            if len(content) + len(chunk) > max_bytes:
                return await JSONResponse({"detail": "请求内容超过大小上限，请分段整理"}, status_code=413)(scope, receive, send)
            content.extend(chunk)
            if not event.get("more_body", False):
                break
        delivered = False

        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(content), "more_body": False}
            return await receive()

        await self.app(scope, bounded_receive, send)


def create_app(settings=None):
    settings = settings or Settings.from_env()
    settings.validate()
    store = Store(settings)
    feedback = FeedbackStore(store)
    diagnostics = DiagnosticsStore(store)
    runner = Runner(settings, store)
    lease = ServiceLease(settings.db_path)

    @asynccontextmanager
    async def lifespan(app):
        lease.acquire()
        diagnostics_cleanup = None
        try:
            store.initialize()
            initialize_admin(store)
            feedback.initialize()
            diagnostics.initialize()
            store.recover_running()
            store.purge_expired()
            runner.start()
            diagnostics_cleanup = asyncio.create_task(diagnostics.cleanup())
            yield
        finally:
            if diagnostics_cleanup is not None:
                diagnostics_cleanup.cancel()
                await asyncio.gather(diagnostics_cleanup, return_exceptions=True)
            await runner.stop()
            lease.release()

    app = FastAPI(title="Deepseek续聊助手配套服务", version="1.0.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(BoundedBodyMiddleware, max_bytes=settings.max_body_bytes)
    app.state.store = store
    app.state.runner = runner
    app.state.feedback = feedback
    app.state.diagnostics = diagnostics
    app.include_router(build_admin_router(store))

    @app.exception_handler(ServiceError)
    async def service_error(request, error):
        return JSONResponse({"detail": error.message}, status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        if request.url.path == "/v1/diagnostics":
            return JSONResponse({"detail": "诊断日志格式不正确；仅接受受支持的技术信息"}, status_code=422)
        if request.url.path.startswith(("/v1/feedback", "/v1/admin/feedback")):
            return JSONResponse({"detail": "反馈格式不正确；请填写正文及 QQ 或微信，最多附 3 张 JPEG/PNG 图片，每张不超过 1 MiB"}, status_code=422)
        if request.url.path.startswith("/v1/admin/"):
            return JSONResponse({"detail": "管理请求格式或范围不正确，请检查填写内容"}, status_code=422)
        detail = "提交的消息格式不正确，请检查消息编号、说话方和正文。"
        for issue in error.errors():
            location = issue.get("loc", ())
            if len(location) >= 3 and location[:2] == ("body", "messages") and isinstance(location[2], int):
                number = location[2] + 1
                field = location[3] if len(location) > 3 else ""
                if field == "content":
                    detail = f"第 {number} 条消息正文格式不正确，单条上限为 {MAX_SOURCE_CHARACTERS} 字符。"
                elif field == "role":
                    detail = f"第 {number} 条消息的说话方格式不受支持，请核对原文中的角色。"
                elif field == "id":
                    detail = f"第 {number} 条消息缺少有效来源编号，编号上限为 512 字符。"
                break
            if location == ("body", "messages"):
                detail = f"单次消息数量须为 1 到 {MAX_SOURCE_MESSAGES} 条，请缩小整理范围。"
            elif location == ("body", "idempotency_key"):
                detail = "整理任务编号格式不正确，请重新准备此任务。"
            elif issue.get("type") == "value_error":
                detail = f"消息正文或来源关系未通过校验，单次正文上限为 {MAX_SOURCE_CHARACTERS} 字符。"
        return JSONResponse({"detail": detail}, status_code=422)

    def owner(authorization: str | None = Header(default=None)):
        parts = (authorization or "").split(" ", 1)
        if len(parts) != 2 or parts[0].lower() != "bearer":
            raise ServiceError(401, "请先连接整理服务并提供会话凭据")
        return store.authenticate(parts[1])

    app.include_router(build_feedback_router(store, feedback, owner))
    app.include_router(build_diagnostics_router(store, diagnostics, owner))

    @app.get("/health")
    def health():
        worker_ok = not settings.worker_enabled or (
            len(runner.tasks) == settings.worker_concurrency and all(not task.done() for task in runner.tasks)
        )
        healthy = worker_ok and runner.cleanup_ok and runner.cleanup_task is not None and not runner.cleanup_task.done()
        return JSONResponse({"status": "ok" if healthy else "degraded",
                             "service": "deepseek-continuation-assistant", "mock_mode": settings.mock_mode},
                            status_code=200 if healthy else 503)

    @app.post("/v1/invitations/redeem")
    def redeem(body: RedeemRequest):
        token, quota = store.redeem(body.code)
        return {"token": token, "quota": quota}

    @app.post("/v1/sessions")
    def create_session():
        token, quota = store.create_session()
        return {"token": token, "quota": quota}

    @app.get("/v1/quota")
    def quota(owner_id=Depends(owner)):
        return store.quota(owner_id)

    @app.post("/v1/jobs")
    async def submit(body: JobRequest, owner_id=Depends(owner)):
        result = await asyncio.to_thread(store.create_job, owner_id, body)
        runner.notify()
        return result

    @app.get("/v1/jobs/{job_id}")
    def get_job(job_id: str, owner_id=Depends(owner)):
        return store.get_job(owner_id, job_id)

    @app.post("/v1/jobs/{job_id}/cancel")
    def cancel(job_id: str, owner_id=Depends(owner)):
        return store.cancel(owner_id, job_id)

    return app


app = create_app()
