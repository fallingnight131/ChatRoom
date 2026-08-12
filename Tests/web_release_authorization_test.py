#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from web_promotion_evidence_test import WebPromotionEvidenceTest  # noqa: E402
from web_promotion_evidence import write_promotion_once  # noqa: E402
from web_release_authorization import (  # noqa: E402
    create_authorization, verify_authorization, write_once,
)


class WebReleaseAuthorizationTest(WebPromotionEvidenceTest):
    def setUp(self) -> None:
        super().setUp()
        self.promotion = self.root / "technical-promotion.json"
        write_promotion_once(self.promotion, self._build())
        self.authorization = self.root / "authorization.json"

    def create(self, now=None, lifetime=900):
        return create_authorization(
            self.promotion, self.current, self.current_observation,
            self.route_observation, self.rollback, self.rollback_observation,
            now or self.now, lifetime,
        )

    def verify(self, now=None):
        return verify_authorization(
            self.authorization, self.promotion, self.current,
            self.current_observation, self.route_observation, self.rollback,
            self.rollback_observation, now or self.now,
        )

    def test_creates_write_once_short_lived_closed_authorization(self) -> None:
        value = self.create()
        write_once(self.authorization, value)
        self.assertEqual(self.verify()["environment"], "web-production")
        self.assertEqual(value["schemaVersion"], 2)
        self.assertEqual(value["candidateBaseUrl"],
                         "https://preview.chat.example.test")
        self.assertEqual(value["baseUrl"], "https://chat.example.test")
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.authorization, value)

    def test_rejects_expiry_shape_and_semantic_mutation(self) -> None:
        value = self.create()
        write_once(self.authorization, value)
        with self.assertRaisesRegex(ManifestError, "expired"):
            self.verify(self.now + timedelta(minutes=15))

        for mutate, message in (
            (lambda item: item.update({"unknown": True}), "unsupported shape"),
            (lambda item: item.update({"releaseId": item["rollbackReleaseId"]}),
             "does not match"),
            (lambda item: item.update({"expiresAt": "2026-08-12T03:10:00Z"}),
             "lifetime"),
        ):
            changed = deepcopy(value)
            mutate(changed)
            self.authorization.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaisesRegex(ManifestError, message):
                self.verify()

    def test_rejects_changed_promotion_even_when_authorization_is_unchanged(self) -> None:
        write_once(self.authorization, self.create())
        promotion = json.loads(self.promotion.read_text(encoding="utf-8"))
        promotion["status"] = "published"
        self.promotion.write_text(json.dumps(promotion), encoding="utf-8")
        with self.assertRaises(ManifestError):
            self.verify()

    def test_rejects_duplicate_fields_and_contains_no_execution_adapter(self) -> None:
        self.authorization.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify()
        source = (ROOT / "tools/web_release_authorization.py").read_text(encoding="utf-8")
        for marker in ("import requests", "import subprocess", "urllib.request",
                       "boto3", "cloudflare", "vercel", "ssh", "kubectl"):
            self.assertNotIn(marker, source.lower())

    def test_rejects_invalid_lifetime_and_non_utc_clock(self) -> None:
        with self.assertRaisesRegex(ManifestError, "60 to 900"):
            self.create(lifetime=59)
        with self.assertRaisesRegex(ManifestError, "exact UTC"):
            self.create(now=datetime(2026, 8, 12, 2, 10))
        with self.assertRaisesRegex(ManifestError, "technical promotion approval"):
            self.create(now=self.now + timedelta(minutes=16))


if __name__ == "__main__":
    import unittest
    unittest.main()
