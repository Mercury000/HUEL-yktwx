import io
import json
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs

from payment_api import (
    ApiError, CaptchaRequired, CLIENT_AUTH, KEYBOARD_LAYOUTS,
    LoginTokens, PasswordLoginClient, PaymentCodeClient, main,
)
from test_payment_api import FakeResponse


class PasswordLoginTests(unittest.TestCase):
    def keyboard(self):
        # Artificial substitution. No real password, UUID, or token is used.
        result = {key: layout[::-1] for key, layout in KEYBOARD_LAYOUTS.items()}
        result['uuid'] = 'test-keyboard-uuid'
        return result

    def fake_login_client(self, final_response=None, error=None):
        client = PasswordLoginClient()
        keyboard = self.keyboard()
        responses = [
            {'code': 200, 'data': {'getFrontConfig': json.dumps({'loginType': json.dumps([
                {'key': 'snoNew', 'encryption': 'keyboard', 'openCaptcha': 0},
            ])})}},
            {'code': 200, 'data': keyboard},
            final_response or {'access_token': 'demo-token', 'token_type': 'bearer', 'refresh_token': 'demo-refresh', 'expires_in': 60},
        ]
        calls = []

        def open_request(request, timeout):
            calls.append(request)
            if error and len(calls) == 3:
                raise error
            return FakeResponse(responses.pop(0))

        client._opener.open = open_request
        return client, calls, keyboard

    def test_fixed_layout_lengths(self):
        self.assertEqual({key: len(layout) for key, layout in KEYBOARD_LAYOUTS.items()}, {
            'numberKeyboard': 10, 'lowerLetterKeyboard': 26, 'upperLetterKeyboard': 26, 'symbolKeyboard': 29,
        })
        alphabet = ''.join(KEYBOARD_LAYOUTS.values())
        self.assertEqual(len(set(alphabet)), len(alphabet))

    def test_encoding_all_characters(self):
        keyboard = self.keyboard()
        password = ''.join(KEYBOARD_LAYOUTS.values())
        expected = ''.join(keyboard[key] for key in KEYBOARD_LAYOUTS) + '$1$test-keyboard-uuid'
        self.assertEqual(PasswordLoginClient.encode_password(password, keyboard), expected)

    def test_login_request_matches_frontend(self):
        client, calls, keyboard = self.fake_login_client()
        password = 'Qwer123!'
        result = client.login('123456789012', password)
        self.assertEqual(result.access_token, 'demo-token')
        self.assertEqual(result.refresh_token, 'demo-refresh')
        self.assertNotIn('demo-token', repr(result))
        self.assertNotIn('demo-refresh', repr(result))
        post = calls[-1]
        form = parse_qs(post.data.decode())
        self.assertEqual(form['password'], [PasswordLoginClient.encode_password(password, keyboard)])
        self.assertNotIn(password, post.data.decode())
        self.assertEqual(post.get_method(), 'POST')
        self.assertEqual(post.full_url, 'https://yktwx2.huel.edu.cn/berserker-auth/oauth/token')
        self.assertEqual(post.get_header('Authorization'), CLIENT_AUTH)
        self.assertFalse(post.has_header('Cookie'))
        self.assertFalse(post.has_header('Synjones-auth'))
        self.assertEqual(form['logintype'], ['snoNew'])
        self.assertEqual(form['grant_type'], ['password'])
        self.assertEqual(form['device_token'], ['h5'])
        self.assertEqual(form['synAccessSource'], ['h5'])
        self.assertIn('order=0', calls[1].full_url)

    def test_rejected_credentials_no_retry_or_leak(self):
        error = HTTPError('https://example.invalid', 400, 'Error', {}, io.BytesIO(json.dumps({
            'code': 400, 'message': 'secret-password demo-token',
        }).encode()))
        client, calls, _ = self.fake_login_client(error=error)
        with self.assertRaises(ApiError) as result:
            client.login('123', 'Demo123')
        self.assertEqual(len(calls), 3)
        self.assertNotIn('secret-password', str(result.exception))
        self.assertNotIn('demo-token', str(result.exception))

    def test_captcha_error_stops(self):
        error = HTTPError('https://example.invalid', 400, 'Error', {}, io.BytesIO(b'{"code":8002}'))
        client, calls, _ = self.fake_login_client(error=error)
        with self.assertRaises(CaptchaRequired):
            client.login('123', 'Demo123')
        self.assertEqual(len(calls), 3)

    def test_config_captcha_stops_before_login(self):
        client = PasswordLoginClient()
        calls = []

        def fake_get(*args):
            calls.append(args)
            return {'getFrontConfig': json.dumps({'loginType': json.dumps([
                {'key': 'snoNew', 'encryption': 'keyboard', 'openCaptcha': 1},
            ])})}

        client._get = fake_get
        with self.assertRaises(CaptchaRequired):
            client.login('123', 'Demo123')
        self.assertEqual(len(calls), 1)

    def test_bad_keyboard_stops(self):
        for key in KEYBOARD_LAYOUTS:
            keyboard = self.keyboard()
            keyboard[key] = ''
            with self.subTest(key=key), self.assertRaises(ApiError):
                PasswordLoginClient.encode_password('Demo123', keyboard)

    def test_bad_inputs_make_no_requests(self):
        client, calls, _ = self.fake_login_client()
        for username, password in [('abc', 'Demo123'), ('123', ''), ('123', 'space password'), ('123', 'nonascii密码')]:
            with self.subTest(username=username), self.assertRaises(ApiError):
                client.login(username, password)
        self.assertEqual(calls, [])

    def test_non_bearer_response_rejected(self):
        client, _, _ = self.fake_login_client({'access_token': 'demo-token', 'token_type': 'other'})
        with self.assertRaises(ApiError):
            client.login('123', 'Demo123')

    def test_password_renewal_requires_official_site(self):
        for flag in ('is_password_expired', 'is_first_login'):
            client, _, _ = self.fake_login_client({'access_token': 'demo-token', flag: True})
            with self.subTest(flag=flag), self.assertRaises(ApiError):
                client.login('123', 'Demo123')

    def test_cli_login_noninteractive_stops(self):
        errors = io.StringIO()
        with patch('sys.argv', ['payment_api.py', '--login']), patch('sys.stdin.isatty', return_value=False), \
            patch.object(PasswordLoginClient, 'login') as login, redirect_stderr(errors):
            self.assertEqual(main(), 1)
        login.assert_not_called()

    def test_cli_login_no_token_copy_or_logging(self):
        from payment_api import CodeBatch
        import time
        batch = CodeBatch(('12345678901234567890',), 60, time.monotonic())
        output, errors = io.StringIO(), io.StringIO()
        with patch('sys.argv', ['payment_api.py', '--login']), patch('sys.stdin.isatty', return_value=True), \
            patch('builtins.input', return_value='123'), patch('payment_api.hidden_input', return_value='Demo123'), \
            patch.object(PasswordLoginClient, 'login', return_value=LoginTokens('demo-token', 'demo-refresh')) as login, \
            patch.object(PaymentCodeClient, 'fetch_batch', return_value=batch), redirect_stdout(output), redirect_stderr(errors):
            self.assertEqual(main(), 0)
        login.assert_called_once_with('123', 'Demo123')
        for sensitive in ['Demo123', 'demo-token', 'demo-refresh', batch.codes[0]]:
            self.assertNotIn(sensitive, output.getvalue() + errors.getvalue())
        self.assertTrue(json.loads(output.getvalue())['success'])

    def test_hidden_input_fails_closed(self):
        from payment_api import hidden_input
        import getpass
        import warnings

        def warn(*args):
            warnings.warn('Cannot hide input', getpass.GetPassWarning)
            return 'secret'

        with patch('getpass.getpass', side_effect=warn), self.assertRaises(ApiError):
            hidden_input('Password: ')


if __name__ == '__main__':
    unittest.main()
