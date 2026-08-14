import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_linux_resident_memory",
    ROOT / "tools" / "verify_linux_resident_memory.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class LinuxResidentMemoryVerifierTest(unittest.TestCase):
    def test_pins_matching_gradle_and_jdk_image_by_digest(self):
        self.assertTrue(MODULE.IMAGE.startswith("gradle:8.14.3-jdk21-alpine@sha256:"))
        self.assertEqual(64, len(MODULE.IMAGE.rsplit(":", 1)[1]))
        self.assertEqual("*LinuxResidentMemoryIntegrationTest", MODULE.TEST)
        self.assertEqual("chat-room-gradle-linux", MODULE.CACHE_VOLUME)


if __name__ == "__main__":
    unittest.main()
