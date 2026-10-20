import io
import json
import time
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from payment_api import ApiError, CodeBatch, NoRedirect, PaymentCodeClient, main


class FakeResponse:
    def __init__(self, data):
        self.raw = json.dumps(data).encode()

    def read(self, size):
        return self.raw[:size]

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class PaymentApiTests(unittest.TestCase):
    def client_with_responses(self, responses):
        client = PaymentCodeClient("test-token")
        calls = []

        def open_request(request, timeout):
            calls.append(request)
            return FakeResponse(responses.pop(0))

        client._opener.open = open_request
        return client, calls

    def test_fetch_and_no_cookies(self):
        client, calls = self.client_with_responses([
            {"code": 200, "data": [{"code": "ACCOUNT", "status": 1, "account": "demo", "payacc": "000", "paytype": "1"}]},
            {"code": 200, "data": {"barcode": ["12345678901234567890"], "expires": 60}},
        ])
        batch = client.fetch_batch()
        self.assertEqual(batch.first_code(), "12345678901234567890")
        self.assertNotIn(batch.first_code(), json.dumps(batch.summary()))
        self.assertNotIn(batch.first_code(), repr(batch))
        self.assertIn("account=demo", calls[1].full_url)
        self.assertIn("payacc=000", calls[1].full_url)
        self.assertEqual(calls[0].get_header("Synjones-auth"), "bearer test-token")
        self.assertFalse(calls[0].has_header("Cookie"))
        self.assertFalse(any(key.lower() == "authorization" for key in calls[0].headers))

    def test_no_active_account(self):
        client, calls = self.client_with_responses([
            {"code": 200, "data": [{"code": "ACCOUNT", "status": 0}]},
        ])
        with self.assertRaises(ApiError):
            client.fetch_batch()
        self.assertEqual(len(calls), 1)

    def test_frozen_account(self):
        client, calls = self.client_with_responses([
            {"code": 200, "data": [{"code": "ACCOUNT", "status": 1, "freezeflag": "1"}]},
        ])
        with self.assertRaises(ApiError):
            client.fetch_batch()
        self.assertEqual(len(calls), 1)

    def test_error_is_sanitized(self):
        client, _ = self.client_with_responses([{"code": 401, "msg": "secret-token"}])
        with self.assertRaises(ApiError) as error:
            client.payment_methods()
        self.assertNotIn("secret-token", str(error.exception))

    def test_empty_batch(self):
        client = PaymentCodeClient("test-token")
        client.payment_methods = lambda: [{"code": "ACCOUNT", "status": 1, "account": "demo", "payacc": "000", "paytype": "1"}]
        client._get = lambda *args: {"barcode": [], "expires": 60}
        with self.assertRaises(ApiError):
            client.fetch_batch()

    def test_reject_bad_expiry(self):
        for expires in (True, 0, -1, "NaN", "Infinity", None):
            with self.subTest(expires=expires):
                client = PaymentCodeClient("test-token")
                client.payment_methods = lambda: [{"code": "ACCOUNT", "status": 1, "account": "demo", "payacc": "000", "paytype": "1"}]
                client._get = lambda *args: {"barcode": ["123"], "expires": expires}
                with self.assertRaises(ApiError):
                    client.fetch_batch()

    def test_stale_batch(self):
        with self.assertRaises(ApiError):
            CodeBatch(("123",), 1, time.monotonic() - 2).first_code()

    def test_token_validation(self):
        self.assertEqual(PaymentCodeClient("Bearer test-token")._token, "test-token")
        for token in ("", "Bearer ", "bad\r\nheader", "bad token", "非ASCII"):
            with self.subTest(token=token), self.assertRaises(ApiError):
                PaymentCodeClient(token)

    def test_redirect_blocked(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.com"))

    def test_cli_hides_code(self):
        batch = CodeBatch(("12345678901234567890",), 60, time.monotonic())
        output = io.StringIO()
        with patch("sys.argv", ["payment_api.py"]), patch.dict("os.environ", {"YKT_ACCESS_TOKEN": "test-token"}), patch.object(PaymentCodeClient, "fetch_batch", return_value=batch), redirect_stdout(output):
            self.assertEqual(main(), 0)
        self.assertNotIn(batch.codes[0], output.getvalue())
        self.assertTrue(json.loads(output.getvalue())["success"])

    def test_cli_reveal_is_explicit(self):
        batch = CodeBatch(("12345678901234567890",), 60, time.monotonic())
        output = io.StringIO()
        with patch("sys.argv", ["payment_api.py", "--reveal"]), patch.dict("os.environ", {"YKT_ACCESS_TOKEN": "test-token"}), patch.object(PaymentCodeClient, "fetch_batch", return_value=batch), redirect_stdout(output):
            self.assertEqual(main(), 0)
        self.assertEqual(output.getvalue().strip(), batch.codes[0])


if __name__ == "__main__":
    unittest.main()
