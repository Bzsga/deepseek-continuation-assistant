import base64
import secrets
import struct
import time
import zlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.feedback import LEASE_SECONDS, MAX_FEEDBACK_BODY_BYTES, MAX_IMAGE_BYTES
from app.main import create_app


def png(size=None):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    header = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
    ending = chunk(b"IDAT", zlib.compress(b"\x00\x01\x02\x03")) + chunk(b"IEND", b"")
    if size is not None:
        padding = size - len(header) - len(ending) - 12
        header += chunk(b"tEXt", b"note\x00" + b"x" * (padding - 5))
    return header + ending


def image(raw=None, mime="image/png"):
    return {"mime_type": mime, "data_base64": base64.b64encode(png() if raw is None else raw).decode()}


# A 1x1 red bitmap encoded by Windows System.Drawing; no imaging dependency at runtime.
JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDi6KKK+ZP3E//Z"
)


def payload(key="feedback-00001", **changes):
    return {"idempotency_key": key, "content": "希望改善续聊入口", "contact_type": "qq",
            "contact": "synthetic-contact-123456", "app_version": "test-1.0", "images": [], **changes}


def bearer(token):
    return {"Authorization": "Bearer " + token}


@pytest.fixture
def inbox(tmp_path):
    settings = Settings(db_path=str(tmp_path / "feedback.sqlite3"), worker_enabled=False,
                        admin_token=secrets.token_urlsafe(32), feedback_worker_token=secrets.token_urlsafe(32),
                        daily_limit=0, user_daily_limit=0, public_enabled=False)
    app = create_app(settings)
    with TestClient(app) as client:
        owner = bearer(client.post("/v1/sessions").json()["token"])
        other = bearer(client.post("/v1/sessions").json()["token"])
        yield app, client, owner, other, bearer(settings.admin_token), bearer(settings.feedback_worker_token)


def submit(client, owner, **changes):
    response = client.post("/v1/feedback", headers=owner, json=payload(**changes))
    assert response.status_code == 200, response.text
    return response.json()


def approve(client, admin, entry, **changes):
    response = client.patch(f"/v1/admin/feedback/{entry['id']}", headers=admin,
                            json={"status": "approved", "admin_note": "只改善入口布局", **changes})
    assert response.status_code == 200, response.text
    return response.json()


def claim(client, worker, entry, worker_id="test-worker"):
    return client.post(f"/v1/feedback-worker/{entry['id']}/claim", headers=worker,
                       json={"revision": entry["revision"], "worker_id": worker_id})


def completion(claimed, **changes):
    return {"revision": claimed["revision"], "claim_token": claimed["claim_token"],
            "result_status": "completed", "result_note": "已实现并完成定向验证", **changes}


def test_authentication_roles_and_owner_image_isolation(inbox):
    _, client, owner, other, admin, worker = inbox
    assert client.post("/v1/feedback", json=payload()).status_code == 401
    assert client.get("/v1/feedback").status_code == 401
    for headers in (owner, other, worker):
        assert client.get("/v1/admin/feedback", headers=headers).status_code == 401
        assert client.get("/v1/admin/config", headers=headers).status_code == 401
    for headers in (owner, other, admin):
        assert client.get("/v1/feedback-worker/queue", headers=headers).status_code == 401
    assert client.get("/v1/feedback", headers=worker).status_code == 401
    entry = submit(client, owner, images=[image()])
    image_id = entry["images"][0]["id"]
    owner_path = f"/v1/feedback/{entry['id']}/images/{image_id}"
    assert client.get(owner_path, headers=other).status_code == 404
    assert client.get(owner_path, headers=worker).status_code == 401
    response = client.get(owner_path, headers=owner)
    assert response.content == png()
    assert response.headers["content-type"] == "image/png"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["cache-control"] == "no-store"
    assert client.get(f"/v1/admin/feedback/{entry['id']}/images/{image_id}", headers=admin).content == png()
    assert client.get(f"/v1/feedback/{entry['id']}/images/not-a-path", headers=owner).status_code == 404
    assert client.get("/v1/feedback", headers=other).json()["items"] == []


def test_real_jpeg_and_png_pixel_data_are_checked(inbox):
    _, client, owner, _, _, _ = inbox
    entry = submit(client, owner, images=[image(JPEG, "image/jpeg")])
    jpeg_id = entry["images"][0]["id"]
    downloaded = client.get(f"/v1/feedback/{entry['id']}/images/{jpeg_id}", headers=owner)
    assert downloaded.content == JPEG and downloaded.headers["content-type"] == "image/jpeg"
    raw = png()
    at = raw.index(b"IDAT")
    length = int.from_bytes(raw[at - 4:at], "big")
    empty_data = raw[:at - 4] + b"\x00\x00\x00\x00IDAT" + struct.pack(">I", zlib.crc32(b"IDAT")) + raw[at + 4 + length + 4:]
    for invalid, mime in ((empty_data, "image/png"), (bytes.fromhex("ffd8ffc00008080001000103ffda0002ffd9"), "image/jpeg")):
        assert client.post("/v1/feedback", headers=owner, json=payload("bad-pixels-001", images=[image(invalid, mime)])).status_code == 422


def test_idempotency_and_daily_limit_are_atomic_and_independent_of_model_quota(inbox):
    app, client, owner, _, _, _ = inbox
    def repeated(_):
        return client.post("/v1/feedback", headers=owner, json=payload(images=[image()]))
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses = list(pool.map(repeated, range(6)))
    assert all(response.status_code == 200 for response in responses)
    assert len({response.json()["id"] for response in responses}) == 1
    assert len({response.json()["created_at"] for response in responses}) == 1
    assert client.post("/v1/feedback", headers=owner, json=payload(content="修改正文")).status_code == 409
    def distinct(index):
        return client.post("/v1/feedback", headers=owner, json=payload(f"feedback-new-{index}"))
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses = list(pool.map(distinct, range(6)))
    assert sum(response.status_code == 200 for response in responses) == 2
    assert sum(response.status_code == 429 for response in responses) == 4
    assert repeated(0).status_code == 200
    with app.state.store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM feedback").fetchone()[0] == 3
        assert db.execute("SELECT COUNT(*) FROM feedback_images").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 0
    quota = client.get("/v1/quota", headers=owner).json()
    assert quota["daily_used"] == quota["daily_reserved"] == 0


@pytest.mark.parametrize("changes", [
    {"contact": ""}, {"contact": "  "}, {"contact_type": "email"}, {"contact": "x" * 129},
    {"content": "  "}, {"content": "x" * 5001}, {"content": "bad\x00text"},
    {"images": [image()] * 4}, {"images": [image(b"<svg></svg>", "image/png")]},
    {"images": [image(b"MZexe", "image/jpeg")]}, {"images": [image(b"\xff\xd8\xff\xd9", "image/jpeg")]},
    {"images": [{"mime_type": "image/svg+xml", "data_base64": "PHN2Zz4="}]},
    {"images": [{"mime_type": "image/png", "data_base64": "not base64"}]},
    {"images": [image(png()[:-1] + b"x")]}, {"images": [image(png(MAX_IMAGE_BYTES + 1))]},
])
def test_invalid_feedback_is_generic_and_does_not_partially_persist(inbox, changes):
    app, client, owner, _, _, _ = inbox
    response = client.post("/v1/feedback", headers=owner, json=payload(**changes))
    assert response.status_code == 422
    assert "synthetic-contact" not in response.text
    assert "希望改善" not in response.text
    with app.state.store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM feedback").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM feedback_images").fetchone()[0] == 0


def test_three_maximum_images_and_body_limit_are_route_specific(inbox):
    _, client, owner, _, admin, _ = inbox
    data = png(MAX_IMAGE_BYTES)
    assert len(data) == MAX_IMAGE_BYTES
    entry = submit(client, owner, images=[image(data)] * 3)
    assert [item["byte_size"] for item in entry["images"]] == [MAX_IMAGE_BYTES] * 3
    assert client.post("/v1/feedback", headers=owner, content=b" " * (MAX_FEEDBACK_BODY_BYTES + 1)).status_code == 413
    assert client.post("/v1/jobs", headers=owner, content=b" " * 65537).status_code == 413
    assert client.post("/v1/feedback/anything", headers=owner, content=b" " * 65537).status_code == 413
    assert client.patch(f"/v1/admin/feedback/{entry['id']}", headers=admin, content=b" " * 65537).status_code == 413


def test_admin_review_revision_pagination_and_private_note(inbox):
    _, client, owner, _, admin, _ = inbox
    first = submit(client, owner)
    second = submit(client, owner, key="feedback-second")
    detail = client.get(f"/v1/admin/feedback/{first['id']}", headers=admin).json()
    assert detail["revision"] == 1
    approved = approve(client, admin, first, expected_revision=1)
    assert approved["revision"] == 2
    assert approved["admin_note"] == "只改善入口布局"
    assert "admin_note" not in client.get("/v1/feedback", headers=owner).text
    assert "只改善入口布局" not in client.get("/v1/feedback", headers=owner).text
    conflict = client.patch(f"/v1/admin/feedback/{first['id']}", headers=admin,
                            json={"status": "ignored", "expected_revision": 1})
    assert conflict.status_code == 409
    listing = client.get("/v1/admin/feedback?status=approved&limit=1", headers=admin).json()
    assert listing["total"] == 1 and listing["items"][0]["id"] == first["id"]
    page = client.get("/v1/feedback?limit=1&offset=1", headers=owner).json()
    assert page["total"] == 2 and page["items"][0]["id"] == first["id"]
    same = approve(client, admin, approved)
    assert same["revision"] == 2
    cleared = client.patch(f"/v1/admin/feedback/{first['id']}", headers=admin, json={"status": "pending", "admin_note": ""})
    assert cleared.status_code == 200 and cleared.json()["revision"] == 3
    assert client.patch(f"/v1/admin/feedback/{second['id']}", headers=admin, json={"admin_note": None}).status_code == 422


def test_worker_approved_only_claim_lease_and_idempotent_completion(inbox):
    app, client, owner, _, admin, worker = inbox
    entry = submit(client, owner, images=[image()])
    image_id = entry["images"][0]["id"]
    assert client.get("/v1/feedback-worker/queue", headers=worker).json()["items"] == []
    assert client.post(f"/v1/feedback-worker/{entry['id']}/claim", headers=worker,
                       json={"revision": 1, "worker_id": "test"}).status_code == 409
    assert client.get(f"/v1/feedback-worker/{entry['id']}/images/{image_id}?revision=1", headers=worker).status_code == 409
    approved = approve(client, admin, entry)
    queue = client.get("/v1/feedback-worker/queue", headers=worker)
    assert len(queue.json()["items"]) == 1
    for private in ("contact", "contact_type", "owner_id", "synthetic-contact-123456"):
        assert private not in queue.text
    assert queue.json()["items"][0]["admin_note"] == "只改善入口布局"
    assert client.get(f"/v1/feedback-worker/{entry['id']}/images/{image_id}?revision=2", headers=worker).content == png()
    claimed = claim(client, worker, approved).json()
    again = claim(client, worker, approved).json()
    assert again["claim_token"] == claimed["claim_token"]
    assert again["lease_expires_at"] == claimed["lease_expires_at"]
    assert claim(client, worker, approved, "other-worker").status_code == 409
    assert client.get("/v1/feedback-worker/queue", headers=worker).json()["items"] == []
    with app.state.store.connection() as db:
        row = db.execute("SELECT * FROM feedback_processing").fetchone()
        assert row["lease_expires_at"] - row["claimed_at"] == LEASE_SECONDS
    url = f"/v1/feedback-worker/{entry['id']}/complete"
    done = client.post(url, headers=worker, json=completion(claimed))
    assert done.status_code == 200
    assert client.post(url, headers=worker, json=completion(claimed)).json() == done.json()
    assert client.post(url, headers=worker, json=completion(claimed, result_note="different")).status_code == 409
    assert claim(client, worker, approved).status_code == 409
    assert client.get("/v1/feedback-worker/queue", headers=worker).json()["items"] == []
    assert client.get("/v1/feedback", headers=owner).json()["items"][0]["processing"]["result_status"] == "completed"


def test_expired_lease_can_be_reclaimed_and_old_token_cannot_complete(inbox):
    app, client, owner, _, admin, worker = inbox
    approved = approve(client, admin, submit(client, owner))
    first = claim(client, worker, approved).json()
    with app.state.store.connection(write=True) as db:
        db.execute("UPDATE feedback_processing SET lease_expires_at=?", (time.time() - 1,))
    url = f"/v1/feedback-worker/{approved['id']}/complete"
    assert client.post(url, headers=worker, json=completion(first)).status_code == 409
    assert len(client.get("/v1/feedback-worker/queue", headers=worker).json()["items"]) == 1
    second = claim(client, worker, approved, "new-worker").json()
    assert second["claim_token"] != first["claim_token"]
    assert client.post(url, headers=worker, json=completion(first)).status_code == 409
    assert client.post(url, headers=worker, json=completion(second, result_status="needs_input")).status_code == 200


def test_admin_revision_invalidates_claim_and_preserves_previous_results(inbox):
    _, client, owner, _, admin, worker = inbox
    approved = approve(client, admin, submit(client, owner, images=[image()]))
    first = claim(client, worker, approved).json()
    updated = approve(client, admin, approved, admin_note="只修正入口按钮文案")
    assert updated["revision"] == approved["revision"] + 1
    url = f"/v1/feedback-worker/{approved['id']}/complete"
    assert client.post(url, headers=worker, json=completion(first)).status_code == 409
    image_id = updated["images"][0]["id"]
    assert client.get(f"/v1/feedback-worker/{approved['id']}/images/{image_id}?revision=2", headers=worker).status_code == 409
    new_claim = claim(client, worker, updated).json()
    assert client.post(url, headers=worker, json=completion(new_claim, result_status="failed")).status_code == 200
    ignored = client.patch(f"/v1/admin/feedback/{approved['id']}", headers=admin, json={"status": "ignored"}).json()
    assert ignored["processing"]["revision"] == updated["revision"]
    assert len(ignored["processing_history"]) == 1
    assert client.post(url, headers=worker, json=completion(new_claim)).status_code == 409
    assert client.get(f"/v1/feedback-worker/{approved['id']}/images/{image_id}?revision={ignored['revision']}", headers=worker).status_code == 409


def test_feedback_persists_and_worker_secret_is_private(inbox):
    app, client, owner, _, admin, _ = inbox
    entry = submit(client, owner, images=[image()])
    assert app.state.store.settings.feedback_worker_token not in repr(app.state.store.settings)
    config = client.get("/v1/admin/config", headers=admin).text
    assert "feedback_worker_token" not in config
    assert app.state.store.settings.feedback_worker_token not in config
    with pytest.raises(ValueError):
        replace(app.state.store.settings, feedback_worker_token=app.state.store.settings.admin_token).validate()
    unconfigured = create_app(replace(app.state.store.settings, feedback_worker_token=""))
    # Store objects share a persisted SQLite file; no second running service is started here.
    restored = unconfigured.state.feedback.detail(entry["id"])
    assert restored["contact"] == payload()["contact"]
    assert restored["images"] == entry["images"]

