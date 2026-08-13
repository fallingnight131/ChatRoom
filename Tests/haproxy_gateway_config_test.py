#!/usr/bin/env python3

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from render_haproxy_gateway import Gateway, gateway, render  # noqa: E402


class HaproxyGatewayConfigTest(unittest.TestCase):
    def test_renders_tls_headers_active_readiness_and_bounded_gateways(self):
        content = render("127.0.0.1", 8443, "/run/lb.pem", "/run/gateway-ca.pem",
                         "chat.example.com", [
            Gateway("gateway-a", "10.0.0.11", 9443, "gateway.internal"),
            Gateway("gateway-b", "10.0.0.12", 9443, "gateway.internal"),
        ])
        self.assertIn("bind 127.0.0.1:8443 ssl crt /run/lb.pem alpn http/1.1", content)
        self.assertIn("http-request del-header X-Forwarded-For", content)
        self.assertIn("http-request set-header X-Forwarded-Proto https", content)
        self.assertIn("http-check send meth GET uri /health/ready", content)
        self.assertIn("http-check expect status 200", content)
        self.assertEqual(2, content.count(" verify required ca-file "))
        self.assertNotIn("no-check-ssl", content)
        self.assertIn("hdr Host chat.example.com", content)
        self.assertIn("balance leastconn", content)
        self.assertIn("timeout tunnel 5m", content)

    def test_rejects_injection_invalid_ports_duplicates_and_empty_set(self):
        for value in [
            "Bad_Name,10.0.0.1,9443,gateway.internal",
            "gateway-a,10.0.0.1\nserver evil,9443,gateway.internal",
            "gateway-a,10.0.0.1,0,gateway.internal",
            "gateway-a,10.0.0.1,9443,gateway.internal,extra",
        ]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                gateway(value)
        with self.assertRaisesRegex(ValueError, "at least one"):
            render("127.0.0.1", 443, "/run/lb.pem", "/run/ca.pem",
                   "chat.example.com", [])
        duplicate = Gateway("gateway-a", "10.0.0.1", 9443, "gateway.internal")
        with self.assertRaisesRegex(ValueError, "unique"):
            render("127.0.0.1", 443, "/run/lb.pem", "/run/ca.pem", "chat.example.com",
                   [duplicate, duplicate])
        with self.assertRaisesRegex(ValueError, "absolute POSIX"):
            render("127.0.0.1", 443, "relative.pem", "/run/ca.pem",
                   "chat.example.com", [duplicate])


if __name__ == "__main__":
    unittest.main()
