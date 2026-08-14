#!/usr/bin/env python3

import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))
from gateway_crash_performance_result import EvidenceError  # noqa: E402
from multi_edge_reconnect_ladder_result import build, validate  # noqa: E402
from Tests.multi_edge_reconnect_result_test import (  # noqa: E402
    authentication_saturation,
    event_loop_saturation,
    evidence,
    postgres_pool_saturation,
    process_resource_saturation,
)


def run(profile="step-12"):
    workloads = {
        "step-12": (18, 12, 6, 3),
        "step-24": (30, 24, 6, 6),
        "step-48": (54, 48, 6, 12),
    }
    connections, affected, surviving, batch = workloads[profile]
    value = evidence()
    value["schemaVersion"] = 6
    value["scenario"].update(
        workloadProfile=profile,
        connections=connections,
        failedEdgeConnections=affected,
        survivingEdgeConnections=surviving,
        reconnectBatchSize=batch,
    )
    value["results"].update(
        reconnectAttempts=affected,
        reconnectSuccesses=affected,
        secondaryGatewayAuthenticationBefore=surviving,
        secondaryGatewayAuthenticationAfter=connections,
        authenticationSaturation=authentication_saturation(),
        postgresPoolSaturation=postgres_pool_saturation(),
        eventLoopSaturation=event_loop_saturation(),
        processResourceSaturation=process_resource_saturation(),
    )
    value["results"]["eventLoopSaturation"]["pendingTasksMaximum"] = 0
    for field in ("sessionResumeLatencyMicros", "scheduledStartJitterMicros"):
        value["results"][field]["samples"] = affected
    return value


def ladder_runs():
    return {
        profile: [copy.deepcopy(run(profile)) for _ in range(3)]
        for profile in ("step-12", "step-24", "step-48")
    }


class MultiEdgeReconnectLadderResultTest(unittest.TestCase):
    def test_accepts_three_clean_runs_per_fixed_profile(self):
        value = build(ladder_runs())
        validate(value, "a" * 40, require_clean=True)
        self.assertEqual(
            "no-pressure-knee-observed-within-ladder",
            value["analysis"]["conclusion"],
        )

    def test_identifies_repeated_pressure_at_lowest_observed_step(self):
        runs = ladder_runs()
        for sample in runs["step-24"][:2]:
            sample["results"]["authenticationSaturation"]["queuedWorkMaximum"] = 1
        value = build(runs)
        validate(value, "a" * 40, require_clean=True)
        self.assertEqual(
            "repeated-pressure-first-observed-at-step-24",
            value["analysis"]["conclusion"],
        )

    def test_identifies_latency_candidate_only_after_absolute_and_ratio_growth(self):
        runs = ladder_runs()
        for sample in runs["step-48"]:
            distribution = sample["results"]["sessionResumeLatencyMicros"]
            distribution.update(p95=70_000, p99=70_000, max=70_000, mean=30_000.0)
        value = build(runs)
        validate(value, "a" * 40, require_clean=True)
        self.assertEqual(
            "latency-knee-candidate-at-step-48",
            value["analysis"]["conclusion"],
        )

    def test_rejects_missing_misplaced_or_tampered_runs(self):
        for mutate in (
            lambda value: value["runEvidence"]["step-12"].pop(),
            lambda value: value["runEvidence"]["step-24"].__setitem__(
                0, copy.deepcopy(value["runEvidence"]["step-12"][0])),
            lambda value: value["analysis"].update(
                conclusion="production-capacity-proven"),
        ):
            value = build(ladder_runs())
            mutate(value)
            with self.subTest(value=value), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_reconciles_dirty_child_and_clean_requirement(self):
        runs = ladder_runs()
        runs["step-48"][2]["worktreeDirty"] = True
        value = build(runs)
        self.assertTrue(value["worktreeDirty"])
        validate(value, "a" * 40)
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)


if __name__ == "__main__":
    unittest.main()
