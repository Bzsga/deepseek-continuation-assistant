"""Registered identities for tests whose subject is jobs rather than account APIs."""

import uuid

from app.store import digest


def _registered(store):
    identity = uuid.uuid4().hex
    return store.accounts.register(f"fixture_{identity[:24]}", "synthetic-fixture-password",
                                   client_ip=identity)


def registered_session(client):
    return _registered(client.app.state.store)


def register_owner(store):
    body = _registered(store)
    return body["user"]["id"], body["token"]


def registered_job(store, owner_id, token, request):
    return store.create_job(owner_id, request, account_session_hash=digest(token))
