import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_haproxy_runtime", ROOT / "tools" / "verify_haproxy_runtime.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class HaproxyRuntimeHostTest(unittest.TestCase):
    def test_uses_docker_host_gateway_alias(self):
        self.assertEqual("host.docker.internal", MODULE.DOCKER_HOST_ALIAS)
        self.assertEqual(
            ["--add-host", "host.docker.internal:host-gateway"],
            MODULE.docker_host_arguments(),
        )


if __name__ == "__main__":
    unittest.main()
