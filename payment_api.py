"""Personal HUEL payment-code API client; no credentials are stored on disk."""
from __future__ import annotations

import argparse
import base64
import getpass
import json
import math
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

BASE_URL = "https://yktwx2.huel.edu.cn"
MAX_RESPONSE_BYTES = 1_048_576
# Public frontend OAuth client identifier, not a user credential.
CLIENT_AUTH = "Basic " + base64.b64encode(
    b"mobile_service_platform:mobile_service_platform_secret"
).decode("ascii")
# Verified visual order of the official Standard keyboard with order=0.
KEYBOARD_LAYOUTS = {
    "numberKeyboard": "0123456789",
    "lowerLetterKeyboard": "qwertyuiopasdfghjklzxcvbnm",
    "upperLetterKeyboard": "QWERTYUIOPASDFGHJKLZXCVBNM",
    "symbolKeyboard": "*\\-[]{}/!,<>?~&@#.:+|`%'$;^\"_",
}


class ApiError(RuntimeError):
    """Sanitized errors never contain credentials, codes, or server error text."""


class CaptchaRequired(ApiError):
    """The server requires human verification; no automatic retries are allowed."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Do not leak a password, Basic header, or custom token on redirects.
        return None


def validate_token(token: str) -> str:
    token = token.strip()
    if token.lower() == "bearer":
        token = ""
    elif token.lower().startswith("bearer "):
        token = token[7:].strip()
    if not token or any(ord(ch) <= 32 or ord(ch) >= 127 for ch in token):
        raise ApiError("A nonempty ASCII login token without whitespace is required.")
    return token


def request_json(opener, request: Request, timeout: float, *, login: bool = False) -> Any:
    try:
        with opener.open(request, timeout=timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ApiError("API response exceeded the size limit.")
            payload = json.loads(raw)
    except HTTPError as exc:
        status = exc.code
        error_code = None
        if login:
            try:
                # Inspect only the numeric error code; never echo response text.
                body = exc.read(MAX_RESPONSE_BYTES + 1)
                if len(body) <= MAX_RESPONSE_BYTES:
                    error = json.loads(body)
                    if isinstance(error, dict):
                        error_code = str(error.get("code"))
            except (ValueError, UnicodeError, OSError):
                pass
        exc.close()
        if login and error_code in ("8002", "8003"):
            raise CaptchaRequired("Captcha required. Complete verification on the official site; no retry was made.") from None
        if status == 429:
            raise ApiError("Rate limited. Wait and retry manually; no automatic retry was made.") from None
        if login:
            raise ApiError(f"Login rejected (HTTP {status}). Check your credentials or the official login page; no retry was made.") from None
        if status in (401, 403):
            raise ApiError("Authentication denied. Log in again.") from None
        raise ApiError(f"API returned HTTP {status}.") from None
    except (URLError, TimeoutError, OSError):
        raise ApiError("HTTPS request failed; check your connection and retry manually.") from None
    except (ValueError, UnicodeError):
        raise ApiError("API returned invalid JSON.") from None
    return payload


def success_data(payload: Any) -> Any:
    if not isinstance(payload, dict) or payload.get("code") != 200:
        raise ApiError("API did not report success; check the official page.")
    return payload.get("data")


@dataclass(frozen=True, repr=False)
class LoginTokens:
    access_token: str = field(repr=False)
    refresh_token: str | None = field(default=None, repr=False)
    expires_in: float | None = None


class PasswordLoginClient:
    def __init__(self, timeout: float = 15):
        self._timeout = timeout
        # No browser, cookie jar, automatic retries, or disabled TLS validation.
        self._opener = build_opener(NoRedirect())

    def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        query = urlencode({**(params or {}), "synAccessSource": "h5"})
        request = Request(f"{BASE_URL}{path}?{query}", headers={
            "synAccessSource": "h5", "Accept": "application/json", "Cache-Control": "no-store",
        })
        return success_data(request_json(self._opener, request, self._timeout))

    def _login_type(self) -> str:
        data = self._get("/berserker-app/frontInfo")
        try:
            config = json.loads(data["getFrontConfig"])
            types = json.loads(config["loginType"])
        except (KeyError, TypeError, ValueError):
            raise ApiError("Unexpected login configuration.") from None
        if not isinstance(types, list):
            raise ApiError("Unexpected login configuration.")
        method = next((item for item in types if isinstance(item, dict) and item.get("key") == "snoNew"), None)
        if not method or method.get("encryption") != "keyboard" or method.get("keyboardType", "1") != "1":
            raise ApiError("Unsupported login configuration; inspect the official page again.")
        if str(method.get("openCaptcha", 0)) not in ("0", "None", ""):
            raise CaptchaRequired("The official login requires a captcha. Complete verification on the official site.")
        return method["key"]

    @staticmethod
    def encode_password(password: str, keyboard: Any) -> str:
        if not isinstance(keyboard, dict):
            raise ApiError("Unexpected keyboard response.")
        uuid = keyboard.get("uuid")
        if not isinstance(uuid, str) or not uuid or any(ord(ch) <= 32 or ord(ch) >= 127 for ch in uuid) or "$" in uuid:
            raise ApiError("Unexpected keyboard identifier.")
        mapping = {}
        for key, layout in KEYBOARD_LAYOUTS.items():
            encoded = keyboard.get(key)
            if not isinstance(encoded, str) or len(encoded) != len(layout) or not encoded.isascii():
                raise ApiError("Unexpected keyboard mapping; inspect the official page again.")
            mapping.update(zip(layout, encoded))
        if not password or any(char not in mapping for char in password):
            raise ApiError("Password contains a character unsupported by the official Standard keyboard.")
        return "".join(mapping[char] for char in password) + "$1$" + uuid

    def login(self, username: str, password: str) -> LoginTokens:
        username = username.strip()
        if not username or not username.isascii() or not username.isdigit():
            raise ApiError("Enter your numeric student/staff ID.")
        if not password or any(char not in "".join(KEYBOARD_LAYOUTS.values()) for char in password):
            raise ApiError("Password contains a character unsupported by the official Standard keyboard.")
        login_type = self._login_type()
        keyboard = self._get("/berserker-secure/keyboard", {"type": "Standard", "order": 0})
        encoded_password = self.encode_password(password, keyboard)
        form = {
            "username": username,
            "password": encoded_password,
            "grant_type": "password",
            "scope": "all",
            "loginFrom": "h5",
            "logintype": login_type,
            "device_token": "h5",
            "synAccessSource": "h5",
        }
        request = Request(f"{BASE_URL}/berserker-auth/oauth/token", data=urlencode(form).encode("ascii"), headers={
            "Authorization": CLIENT_AUTH,
            "Content-Type": "application/x-www-form-urlencoded",
            "synAccessSource": "h5",
            "Accept": "application/json",
            "Cache-Control": "no-store",
        }, method="POST")
        payload = request_json(self._opener, request, self._timeout, login=True)
        if not isinstance(payload, dict):
            raise ApiError("Unexpected login response.")
        if str(payload.get("code")) in ("8002", "8003"):
            raise CaptchaRequired("Captcha required. Complete verification on the official site; no retry was made.")
        token = payload.get("access_token")
        if not isinstance(token, str):
            raise ApiError("Login did not return a token. Check the official login page; no retry was made.")
        if str(payload.get("token_type", "bearer")).lower() != "bearer":
            raise ApiError("Unsupported authentication token type.")
        if payload.get("is_password_expired") in (True, "true") or payload.get("is_first_login") in (True, "true"):
            raise ApiError("Complete password renewal or first-login setup on the official site before using this client.")
        refresh = payload.get("refresh_token")
        lifetime = payload.get("expires_in")
        try:
            lifetime = float(lifetime)
            if not math.isfinite(lifetime) or lifetime <= 0:
                lifetime = None
        except (TypeError, ValueError):
            lifetime = None
        return LoginTokens(validate_token(token), refresh if isinstance(refresh, str) else None, lifetime)


@dataclass(frozen=True, repr=False)
class CodeBatch:
    codes: tuple[str, ...]
    expires_seconds: float
    received_at: float

    def first_code(self) -> str:
        if time.monotonic() - self.received_at >= self.expires_seconds:
            raise ApiError("The code batch is stale. Request a new batch.")
        return self.codes[0]

    def summary(self) -> dict[str, Any]:
        return {
            "success": True,
            "code_count": len(self.codes),
            "code_lengths": sorted({len(code) for code in self.codes}),
            "expires_seconds": self.expires_seconds,
            "payment_code": "[hidden; use --reveal to output]",
        }


class PaymentCodeClient:
    def __init__(self, token: str, timeout: float = 15):
        self._token = validate_token(token)
        self._timeout = timeout
        self._opener = build_opener(NoRedirect())

    def _get(self, path: str, params: dict[str, str] | None = None) -> Any:
        query = urlencode({**(params or {}), "synAccessSource": "h5"})
        request = Request(f"{BASE_URL}{path}?{query}", headers={
            "synjones-auth": f"bearer {self._token}",
            "synAccessSource": "h5", "Accept": "application/json", "Cache-Control": "no-store",
        })
        return success_data(request_json(self._opener, request, self._timeout))

    def payment_methods(self) -> list[dict[str, Any]]:
        data = self._get("/berserker-app/ykt/tsm/codebarPayinfo")
        if not isinstance(data, list) or any(not isinstance(item, dict) for item in data):
            raise ApiError("Unexpected payment-method response.")
        return data

    def fetch_batch(self) -> CodeBatch:
        payment = next((item for item in self.payment_methods()
            if item.get("code") == "ACCOUNT" and str(item.get("status")) == "1"
            and str(item.get("lostflag", 0)) == "0" and str(item.get("freezeflag", 0)) == "0"), None)
        if payment is None:
            raise ApiError("No active unified account is available.")
        params = {}
        for key in ("account", "payacc", "paytype"):
            value = payment.get(key)
            if value is None or value == "":
                raise ApiError("Missing required payment-account parameter.")
            params[key] = str(value)
        started = time.monotonic()
        data = self._get("/berserker-app/ykt/tsm/batchGetBarCodeGet", params)
        if not isinstance(data, dict):
            raise ApiError("Unexpected payment-code response.")
        codes = data.get("barcode")
        if not isinstance(codes, list) or not codes or any(
            not isinstance(code, str) or not code or not code.isascii() or not code.isdigit() for code in codes
        ):
            raise ApiError("No valid payment codes were returned.")
        expires = data.get("expires")
        if isinstance(expires, bool):
            raise ApiError("Unexpected payment-code expiry.")
        try:
            expires = float(expires)
        except (TypeError, ValueError):
            raise ApiError("Unexpected payment-code expiry.") from None
        if not math.isfinite(expires) or expires <= 0:
            raise ApiError("Unexpected payment-code expiry.")
        return CodeBatch(tuple(codes), expires, started)


def hidden_input(prompt: str) -> str:
    # getpass's fallback could echo the password; fail closed when echo control is unavailable.
    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            return getpass.getpass(prompt)
        except getpass.GetPassWarning:
            raise ApiError("Cannot hide terminal input. Use a real interactive terminal.") from None


def main() -> int:
    parser = argparse.ArgumentParser(description="Log in and fetch your payment code directly via the official site's API.")
    parser.add_argument("--login", action="store_true", help="Enter student/staff ID and password locally; no token copying required.")
    parser.add_argument("--reveal", action="store_true", help="Print one FULL payment code; treat it as a payment credential.")
    args = parser.parse_args()
    try:
        if args.login:
            if not sys.stdin.isatty():
                raise ApiError("Password login requires an interactive terminal.")
            username = input("Student/staff ID: ")
            password = hidden_input("Password (hidden): ")
            try:
                tokens = PasswordLoginClient().login(username, password)
            finally:
                # Dropping references is not a secure erasure guarantee for immutable strings.
                password = ""
            token = tokens.access_token
            print("API login succeeded; token is held in memory only.", file=sys.stderr)
        else:
            token = os.environ.get("YKT_ACCESS_TOKEN")
            if not token:
                if not sys.stdin.isatty():
                    raise ApiError("Set YKT_ACCESS_TOKEN or run in an interactive terminal.")
                token = hidden_input("Login access_token (hidden): ")
        batch = PaymentCodeClient(token).fetch_batch()
        print(batch.first_code() if args.reveal else json.dumps(batch.summary(), ensure_ascii=False, indent=2))
    except ApiError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
