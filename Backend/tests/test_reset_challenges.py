from __future__ import annotations

import unittest
from unittest.mock import patch

from api.reset_challenges import ResetChallengeError, ResetChallengeStore


class ResetChallengeTests(unittest.TestCase):
    def test_challenge_is_one_use_and_bound_to_admin_identity(self) -> None:
        store = ResetChallengeStore(ttl_seconds=90)
        issue = store.issue(111)
        token = str(issue["reset_token"])
        store.consume(111, token)
        with self.assertRaises(ResetChallengeError):
            store.consume(111, token)

    def test_another_account_cannot_consume_reset_challenge(self) -> None:
        store = ResetChallengeStore()
        token = str(store.issue(111)["reset_token"])
        with self.assertRaises(ResetChallengeError):
            store.consume(222, token)

    def test_expired_challenge_is_rejected(self) -> None:
        store = ResetChallengeStore(ttl_seconds=30)
        with patch("api.reset_challenges.time.time", return_value=1000):
            token = str(store.issue(111)["reset_token"])
        with patch("api.reset_challenges.time.time", return_value=1040):
            with self.assertRaises(ResetChallengeError):
                store.consume(111, token)


if __name__ == "__main__":
    unittest.main()
