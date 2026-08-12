#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Tests"))
sys.path.insert(0, str(ROOT / "tools"))
from artifact_manifest_common import ManifestError  # noqa: E402
from windows_update_product_trust_evidence import (  # noqa: E402
    build_evidence, verify_evidence, write_once,
)
from windows_update_product_trust_intent import write_once as write_intent  # noqa: E402
from windows_update_product_trust_intent_test import (  # noqa: E402
    WindowsUpdateProductTrustIntentTest,
)


class WindowsUpdateProductTrustEvidenceTest(WindowsUpdateProductTrustIntentTest):
    def setUp(self) -> None:
        super().setUp()
        self.client = self.root / "ChatClient.exe"
        self.client.write_bytes(b"compiled-update-enabled-client")
        self.diagnostic = self.root / "update-trust-diagnostic.json"
        self.evidence = self.root / "product-update-trust-evidence.json"

    def prepare_inputs(self) -> None:
        if not self.intent.exists():
            write_intent(self.intent, self.create(secondary=True))
        intent = json.loads(self.intent.read_text(encoding="utf-8"))
        keys = sorted([
            {"keyId": intent["primaryKey"]["keyId"],
             "publicKeyHex": intent["primaryKey"]["publicKeyHex"]},
            {"keyId": intent["secondaryKey"]["keyId"],
             "publicKeyHex": intent["secondaryKey"]["publicKeyHex"]},
        ], key=lambda value: value["keyId"])
        if not self.diagnostic.exists():
            self.diagnostic.write_text(json.dumps({
                "schemaVersion": 1,
                "product": "chat-room-windows-client",
                "enabled": True,
                "channel": "stable",
                "manifestUrl": self.url,
                "signatureUrl": self.url + ".sig",
                "trustedKeys": keys,
                "error": "",
            }), encoding="utf-8")

    def build(self):
        self.prepare_inputs()
        return build_evidence(
            self.client, self.diagnostic, self.intent, self.version,
            self.revision, "stable", self.url, "windows-update-2026-01",
            self.primary, self.now, "windows-update-2027-01", self.secondary,
        )

    def verify_evidence(self):
        self.prepare_inputs()
        return verify_evidence(
            self.evidence, self.client, self.diagnostic, self.intent,
            self.version, self.revision, "stable", self.url,
            "windows-update-2026-01", self.primary,
            "windows-update-2027-01", self.secondary,
        )

    def test_binds_final_client_diagnostic_and_reviewed_intent(self) -> None:
        value = self.build()
        write_once(self.evidence, value)
        self.assertEqual(value["status"], "compiled-product-update-trust-verified")
        self.assertEqual(self.verify_evidence(), value)
        self.assertEqual(value["keyIds"], [
            "windows-update-2026-01", "windows-update-2027-01"])
        with self.assertRaisesRegex(ManifestError, "already exists"):
            write_once(self.evidence, value)

    def test_rejects_disabled_wrong_key_and_client_mutation(self) -> None:
        self.prepare_inputs()
        original = json.loads(self.diagnostic.read_text(encoding="utf-8"))
        for mutate in (
            lambda value: value.update({"enabled": False}),
            lambda value: value["trustedKeys"][0].update({"publicKeyHex": "0" * 64}),
            lambda value: value.update({"manifestUrl": value["manifestUrl"] + "?x"}),
        ):
            changed = json.loads(json.dumps(original))
            mutate(changed)
            self.diagnostic.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaisesRegex(ManifestError, "does not match"):
                self.build()
        self.diagnostic.write_text(json.dumps(original), encoding="utf-8")
        write_once(self.evidence, self.build())
        self.client.write_bytes(b"changed")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.verify_evidence()

    def test_rejects_duplicate_and_unknown_diagnostic_or_evidence(self) -> None:
        self.prepare_inputs()
        self.diagnostic.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.build()
        self.diagnostic.write_text(json.dumps({"unknown": True}), encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "does not match"):
            self.build()
        self.evidence.write_text(
            '{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "duplicate keys"):
            self.verify_evidence()


if __name__ == "__main__":
    import unittest
    unittest.main()
