from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import aiohttp

from .push_store import push_store


logger = logging.getLogger(__name__)
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class PushDelivery:
    def __init__(self) -> None:
        self.project_id = os.environ.get("FIREBASE_PROJECT_ID", "").strip()
        self.credentials_file = os.environ.get("FIREBASE_SERVICE_ACCOUNT_FILE", "").strip()
        self._credentials: Any = None
        self._credentials_lock = asyncio.Lock()

    @property
    def configured(self) -> bool:
        return bool(self.project_id and self.credentials_file and os.path.isfile(self.credentials_file))

    async def _access_token(self) -> str:
        if not self.configured:
            raise RuntimeError("Firebase push delivery is not configured.")
        async with self._credentials_lock:
            def refresh() -> str:
                import google.auth.transport.requests
                from google.oauth2 import service_account

                if self._credentials is None:
                    self._credentials = service_account.Credentials.from_service_account_file(
                        self.credentials_file,
                        scopes=[FCM_SCOPE],
                    )
                if not self._credentials.valid:
                    request = google.auth.transport.requests.Request()
                    self._credentials.refresh(request)
                token = self._credentials.token
                if not token:
                    raise RuntimeError("Firebase OAuth did not return an access token.")
                return str(token)

            return await asyncio.to_thread(refresh)

    async def send_to_user(
        self,
        user_id: int,
        title: str,
        body: str,
        event: str,
        data: dict[str, Any] | None = None,
    ) -> int:
        """Send a visible notification to all registered devices of one server-selected user."""
        devices = push_store.devices_for(user_id)
        if not devices:
            return 0
        if not self.configured:
            logger.info("Push delivery skipped: Firebase credentials are not configured.")
            return 0

        try:
            access_token = await self._access_token()
        except Exception:
            logger.exception("Could not obtain Firebase messaging credentials.")
            return 0

        delivered = 0
        timeout = aiohttp.ClientTimeout(total=12)
        url = f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send"
        async with aiohttp.ClientSession(timeout=timeout) as session:
            for device in devices:
                token = device.get("token", "")
                if not token:
                    continue
                payload_data = {"event": event, "screen": "profile"}
                if data:
                    for key, value in data.items():
                        if value is not None:
                            payload_data[str(key)] = str(value)[:500]
                message = {
                    "message": {
                        "token": token,
                        "notification": {
                            "title": str(title)[:100],
                            "body": str(body)[:1000],
                        },
                        "data": payload_data,
                        "android": {
                            "priority": "HIGH",
                            "notification": {
                                "channel_id": "firecoins_events",
                                "sound": "default",
                            },
                        },
                        "apns": {
                            "headers": {"apns-priority": "10"},
                            "payload": {"aps": {"sound": "default"}},
                        },
                    }
                }
                try:
                    async with session.post(
                        url,
                        json=message,
                        headers={
                            "Authorization": f"Bearer {access_token}",
                            "Content-Type": "application/json; charset=utf-8",
                        },
                    ) as response:
                        raw = await response.text()
                        if 200 <= response.status < 300:
                            delivered += 1
                            continue
                        try:
                            error = json.loads(raw).get("error", {})
                        except (TypeError, json.JSONDecodeError):
                            error = {}
                        error_status = str(error.get("status", ""))
                        if error_status == "UNREGISTERED" or (
                            response.status == 404 and error_status in {"NOT_FOUND", ""}
                        ):
                            push_store.remove_key(device.get("key", ""))
                        logger.warning(
                            "FCM delivery failed: status=%s error=%s",
                            response.status,
                            error_status or "unspecified",
                        )
                except (aiohttp.ClientError, asyncio.TimeoutError):
                    logger.exception("FCM network delivery failed.")
        return delivered


push_delivery = PushDelivery()


async def notify_account(
    user_id: int,
    title: str,
    body: str,
    event: str,
    data: dict[str, Any] | None = None,
) -> None:
    try:
        await push_delivery.send_to_user(user_id, title, body, event, data)
    except Exception:
        # Push delivery is best effort: it must never roll back a committed
        # balance operation, note, dispute, or withdrawal.
        logger.exception("Push notification dispatch failed.")
