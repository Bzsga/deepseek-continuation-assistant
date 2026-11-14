from dataclasses import replace

from fastapi.testclient import TestClient
import pytest

from app.config import Settings
from app.main import create_app
from account_fixtures import registered_session


def message(content, role="user", source="conversation-" + "a" * 64 + ":message:" + "b" * 64):
    return {"id": source, "role": role, "content": content}


@pytest.fixture
def settings(tmp_path):
    return Settings(db_path=str(tmp_path / "compatibility.sqlite3"),
                    mock_mode=True, public_enabled=True, worker_enabled=False, context_tokens=64000)


def session(client):
    return {"Authorization": "Bearer " + registered_session(client)["token"]}


@pytest.mark.parametrize("messages,key", [
    ([message("x" * 6001)], "client-task-6001"),
    ([message("聊天内容。" * 1300)], "client-task-chinese"),
    ([message("a" * 3500), message("b" * 3500, source="other")], "client-task-total"),
    ([message("说话方尚未核实", role="unknown")], "client-task-unknown"),
    ([message("来源中保存的开发者消息", role="developer")], "client-task-developer"),
    ([message("范围内的来源编号", source="a" * 512)], "k" * 200),
])
def test_current_android_requests_can_resume_without_spending_an_extra_slot(settings, messages, key):
    with TestClient(create_app(settings)) as client:
        headers = session(client)
        body = {"idempotency_key": key, "messages": messages}
        submitted = client.post("/v1/jobs", headers=headers, json=body)
        assert submitted.status_code == 200, submitted.text
        job_id = submitted.json()["id"]
        replay = client.post("/v1/jobs", headers=headers, json=body)
        assert replay.status_code == 200
        assert replay.json()["id"] == job_id
        quota = client.get("/v1/quota", headers=headers).json()
        assert (quota["daily_used"], quota["daily_reserved"], quota["daily_remaining"]) == (0, 1, 49)
        assert quota["user_daily_remaining"] == 4
        assert client.post(f"/v1/jobs/{job_id}/cancel", headers=headers).status_code == 200
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_remaining"] == 50
        assert quota["user_daily_remaining"] == 5


def test_message_count_follows_configured_context_instead_of_old_200_item_limit(settings):
    with TestClient(create_app(replace(settings, context_tokens=65536))) as client:
        headers = session(client)
        messages = [message("片段", source=f"source-{index}") for index in range(201)]
        response = client.post("/v1/jobs", headers=headers,
                               json={"idempotency_key": "many-client-messages", "messages": messages})
        assert response.status_code == 200, response.text


@pytest.mark.parametrize("messages", [
    [message("x" * 40001)],
    [message("x" * 20001), message("x" * 20000, source="other")],
    [message("private transcript", role="invalid-role")],
    [message("private transcript", source="a" * 513)],
])
def test_invalid_shapes_have_safe_errors_and_do_not_use_daily_quota(settings, messages):
    with TestClient(create_app(settings)) as client:
        headers = session(client)
        response = client.post("/v1/jobs", headers=headers,
                               json={"idempotency_key": "invalid-client-request", "messages": messages})
        assert response.status_code == 422
        assert "private transcript" not in response.text
        assert "6000" not in response.json()["detail"]
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["daily_reserved"] == 0
        assert quota["user_daily_remaining"] == 5


def test_expanded_shape_still_obeys_the_actual_context_budget(settings):
    with TestClient(create_app(settings)) as client:
        headers = session(client)
        response = client.post("/v1/jobs", headers=headers,
                               json={"idempotency_key": "context-guard-client", "messages": [message("x" * 40000)]})
        assert response.status_code == 413
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["daily_reserved"] == 0
        assert quota["user_daily_remaining"] == 5
