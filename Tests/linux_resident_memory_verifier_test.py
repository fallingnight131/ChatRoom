import importlib.util
import sys
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_linux_resident_memory",
    ROOT / "tools" / "verify_linux_resident_memory.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
M0_SPEC = importlib.util.spec_from_file_location(
    "verify_m0", ROOT / "tools" / "verify_m0.py")
M0 = importlib.util.module_from_spec(M0_SPEC)
assert M0_SPEC.loader is not None
M0_SPEC.loader.exec_module(M0)


class LinuxResidentMemoryVerifierTest(unittest.TestCase):
    def test_pins_matching_gradle_and_jdk_image_by_digest(self):
        self.assertTrue(MODULE.IMAGE.startswith("gradle:8.14.3-jdk21-alpine@sha256:"))
        self.assertEqual(64, len(MODULE.IMAGE.rsplit(":", 1)[1]))
        self.assertEqual("*LinuxResidentMemoryIntegrationTest", MODULE.TEST)
        self.assertEqual("chat-room-gradle-linux", MODULE.CACHE_VOLUME)

    def test_m0_exposes_explicit_linux_rss_gate(self):
        with mock.patch.object(sys, "argv", ["verify_m0.py", "--linux-rss"]):
            self.assertTrue(M0.parse_args().linux_rss)
        with mock.patch.object(M0, "run") as run:
            M0.verify_linux_resident_memory()
        command, cwd = run.call_args.args
        self.assertEqual(ROOT, cwd)
        self.assertEqual(sys.executable, command[0])
        self.assertTrue(command[1].endswith("tools/verify_linux_resident_memory.py"))


if __name__ == "__main__":
    unittest.main()
