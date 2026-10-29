"""Detect changes in assumptions behind the non-executable production schema draft.

These checks do not execute SQL or establish production migration readiness.
"""
import unittest
import subprocess
import sys
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend/platform-server/src/main/resources/db/migration"


class ProductionSchemaInventoryTest(unittest.TestCase):
    def test_current_synthetic_boundary_and_seed_are_explicit(self):
        v1 = (MIGRATIONS / "V1__platform.sql").read_text(encoding="utf-8")
        v4 = (MIGRATIONS / "V4__receipt_scope_and_synthetic_marker.sql").read_text(encoding="utf-8")
        self.assertIn("INSERT INTO tenants", v1)
        self.assertIn("INSERT INTO subjects", v1)
        self.assertIn("INSERT INTO memberships", v1)
        for alias in ("admin", "reviewer", "alice", "bob", "pending"):
            self.assertIn("'" + alias + "'", v1)
        self.assertIn("CREATE TABLE synthetic_boundary", v4)
        self.assertIn("isolated_synthetic_only", v4)

    def test_fixture_dependencies_and_data_backfills_are_explicit(self):
        v2 = (MIGRATIONS / "V2__title_source_and_quota_policy.sql").read_text(encoding="utf-8")
        v5 = (MIGRATIONS / "V5__frozen_quota_accounts.sql").read_text(encoding="utf-8")
        v7 = (MIGRATIONS / "V7__typed_provider_journal.sql").read_text(encoding="utf-8")
        preparation = (ROOT / "backend/platform-server/src/main/java/io/aitest/workbench/execution/PreparationService.java").read_text(encoding="utf-8")
        handover = (ROOT / "backend/platform-server/src/main/java/io/aitest/workbench/handover/HandoverService.java").read_text(encoding="utf-8")
        self.assertIn("INSERT INTO quota_policies", v2)
        self.assertIn("model:fixture-v1", v5)
        self.assertIn("ALTER TABLE fixture_submissions", v7)
        self.assertIn("fixture_submissions", preparation)
        self.assertIn("fixture_events", handover)


    def test_candidate_reproducible_and_ddl_coverage(self):
        draft = ROOT / "docs/development/production-schema-draft"
        result = subprocess.run([sys.executable, str(draft / "generate.py")], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        candidates = draft / "candidate"
        source_files = sorted(MIGRATIONS.glob("V*.sql"))
        self.assertEqual(10, len(source_files))
        for src in source_files:
            output_name = "V4__receipt_scope_and_production_marker.sql" if src.name.startswith("V4__") else src.name
            candidate = (candidates / output_name).read_text(encoding="utf-8")
            original = src.read_text(encoding="utf-8")
            def ddl(value):
                return re.findall(r"(?im)^\s*(CREATE|ALTER)\s+TABLE\s+([a-z_]+)", value)
            original_ddl = [(a.lower(), b.lower()) for a, b in ddl(original) if b.lower() != "synthetic_boundary"]
            candidate_ddl = [(a.lower(), b.lower()) for a, b in ddl(candidate) if b.lower() != "production_boundary"]
            self.assertEqual(original_ddl, candidate_ddl, src.name)
            self.assertNotIn("isolated_synthetic_only", candidate)
            self.assertNotIn("00000000-0000-4000-8000-000000000001", candidate)
            data_writes = re.findall(r"(?im)^\s*(?:INSERT|UPDATE|DELETE|REPLACE|TRUNCATE|DROP)\b[^\n]*", candidate)
            expected = ["INSERT INTO production_boundary (id, mode_name, deployment_id, tenant_id)"] if src.name.startswith("V4__") else []
            self.assertEqual(expected, data_writes, src.name)
        marker = (candidates / "V4__receipt_scope_and_production_marker.sql").read_text(encoding="utf-8")
        self.assertIn("CHECK (mode_name = 'production_uninitialized')", marker)
        self.assertIn("CHECK (deployment_id IS NULL)", marker)
        self.assertIn("CHECK (tenant_id IS NULL)", marker)
        self.assertIn("(1, 'production_uninitialized', NULL, NULL)", marker)

    def test_bootstrap_guard_is_inert_and_matches_consumption_contract(self):
        draft = ROOT / "docs/development/production-schema-draft/candidate"
        guard = (draft / "V11__production_bootstrap_guard.sql").read_text(encoding="utf-8")
        actual = (ROOT / "backend/platform-server/src/main/java/io/aitest/workbench/identity/LocalAdministratorBootstrap.java").read_text(encoding="utf-8")
        for fragment in ("id INT NOT NULL PRIMARY KEY CHECK (id = 1)",
                         "deployment_id BINARY(16) NOT NULL", "token_hash BINARY(32) NULL",
                         "expires_at DATETIME(6) NOT NULL", "tenant_id BINARY(16) NULL",
                         "state = 'provisioned' AND token_hash IS NOT NULL AND tenant_id IS NULL",
                         "state = 'consumed' AND token_hash IS NULL AND tenant_id IS NOT NULL",
                         "tenant_id = UNHEX('00000000000040008000000000000001')"):
            self.assertIn(fragment, guard)
        self.assertNotRegex(guard, r"(?im)^\s*(?:INSERT|UPDATE|DELETE|REPLACE)\b")
        self.assertIn("SELECT deployment_id,token_hash,expires_at,state,tenant_id FROM production_bootstrap_guard WHERE id=1 FOR UPDATE", actual)
        self.assertIn("SET state='consumed',token_hash=NULL,tenant_id=?", actual)
        self.assertIn("expires_at > UTC_TIMESTAMP(6)", actual)
        self.assertIn("connection -> false", actual)
        boundary = (draft / "V4__receipt_scope_and_production_marker.sql").read_text(encoding="utf-8")
        self.assertIn("CHECK (mode_name = 'production_uninitialized')", boundary)
        self.assertIn("CHECK (deployment_id IS NULL)", boundary)
        self.assertIn("CHECK (tenant_id IS NULL)", boundary)

    def test_draft_is_not_a_flyway_migration_or_runtime_resource(self):
        draft = ROOT / "docs/development/production-schema-draft"
        text = (draft / "README.md").read_text(encoding="utf-8")
        self.assertIn("仅供评审的空库迁移候选", text)
        self.assertEqual(11, len(list((draft / "candidate").glob("V*.sql"))))
        self.assertNotIn("production-schema-draft", (ROOT / "backend/platform-server/pom.xml").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
