#!/usr/bin/env python3

from __future__ import annotations

import base64
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "WebClient/e2e/fixtures"


class WebBrowserMediaFixtureTest(unittest.TestCase):
    def decode(self, name: str) -> bytes:
        value = (FIXTURES / name).read_text(encoding="ascii").strip()
        self.assertNotIn("\n", value)
        return base64.b64decode(value, validate=True)

    def test_contains_bounded_ebml_webm_and_ogg_opus_fixtures(self) -> None:
        video = self.decode("tiny.webm.base64")
        audio = self.decode("tiny.ogg.base64")
        self.assertLessEqual(len(video), 1024)
        self.assertLessEqual(len(audio), 2048)
        self.assertEqual(video[:4], bytes.fromhex("1a45dfa3"))
        self.assertIn(b"webm", video.lower())
        self.assertEqual(audio[:4], b"OggS")
        self.assertIn(b"OpusHead", audio)

    def test_documents_reproducible_synthetic_generation(self) -> None:
        readme = (FIXTURES / "README.md").read_text(encoding="utf-8")
        for marker in ("ffmpeg", "color=c=blue:s=16x16", "libvpx-vp9",
                       "sine=frequency=440", "libopus", "no user"):
            self.assertIn(marker, readme)


if __name__ == "__main__":
    unittest.main()
