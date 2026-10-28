"""Inspect inert portable-bootstrap contracts. No SQL, binary or credential executes."""
import configparser
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETUP = ROOT / 'backend/distribution/setup'
GUARD = '{{APPROVED_RENDER_ONLY}}'
DML = {'SELECT', 'INSERT', 'UPDATE', 'DELETE'}
MIGRATION = DML | {'CREATE', 'ALTER', 'INDEX', 'REFERENCES'}


class PortableBootstrapTemplateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql_raw = (SETUP / 'bootstrap.sql.template').read_text()
        cls.sql = '\n'.join(line for line in cls.sql_raw.splitlines() if not line.lstrip().startswith('--'))
        cls.statements = [statement.strip() for statement in cls.sql.split(';') if statement.strip()]
        cls.plan = json.loads((SETUP / 'bootstrap-actions.json').read_text())
        cls.actions = {row['id']: row for row in cls.plan['actions']}
        parser = configparser.ConfigParser(interpolation=None)
        parser.read(SETUP / 'my.ini.template')
        cls.options = parser['mysqld']
        initial = configparser.ConfigParser(interpolation=None)
        initial.read(SETUP / 'initialize.ini.template')
        cls.initial = initial['mysqld']

    def test_every_sql_mutation_has_a_nonliteral_refusal_guard(self):
        self.assertEqual(len(self.statements), 10)
        for statement in self.statements:
            with self.subTest(statement=statement.splitlines()[0]):
                self.assertTrue(statement.startswith(GUARD + ' '))
                self.assertNotIn("'" + GUARD + "'", statement)
                self.assertRegex(statement[len(GUARD):].lstrip(), r'^(ALTER USER|CREATE DATABASE|CREATE USER|GRANT)\b')
        self.assertEqual(self.sql.count(GUARD), len(self.statements))
        self.assertFalse(self.plan['rendering']['unfilledTemplateExecutable'])

    def test_template_can_only_create_the_two_fixed_schemas(self):
        schemas = re.findall(r'CREATE DATABASE `([^`]+)`', self.sql)
        self.assertEqual(schemas, ['workbench_platform', 'workbench_runner'])
        self.assertEqual(self.plan['fixedScope']['schemas'], schemas)
        self.assertNotIn('IF NOT EXISTS', self.sql.upper())

    def test_three_new_accounts_are_loopback_only_and_tls_required(self):
        accounts = re.findall(r"CREATE USER '([^']+)'@'([^']+)'\s+IDENTIFIED WITH caching_sha2_password BY \{\{[A-Z_]+\}\}\s+REQUIRE SSL", self.sql)
        self.assertEqual(accounts, [('workbench', '127.0.0.1'), ('workbench_runner', '127.0.0.1'), ('workbench_migration', '127.0.0.1')])
        self.assertNotIn("'%'", self.sql)
        self.assertNotIn("'root'@'127.0.0.1'", self.sql)
        self.assertTrue(all(row['requireTls'] for row in self.plan['fixedScope']['accounts']))

    def test_vendor_localhost_root_is_rotated_before_creating_any_object(self):
        first = self.statements[0]
        self.assertRegex(first, r"^\{\{APPROVED_RENDER_ONLY\}\} ALTER USER 'root'@'localhost'")
        self.assertIn('{{ROOT_PASSWORD_SQL_LITERAL}}', first)
        self.assertNotRegex(self.sql, r"CREATE USER\s+'root'")
        self.assertIn('never a Web or Runner', self.plan['fixedScope']['administrativeIdentity'])

    def test_password_slots_are_unfilled_complete_literals_and_never_examples(self):
        slots = self.plan['rendering']['passwordTokens']
        self.assertEqual(set(slots), {'ROOT_PASSWORD_SQL_LITERAL', 'PLATFORM_PASSWORD_SQL_LITERAL', 'RUNNER_PASSWORD_SQL_LITERAL', 'MIGRATION_PASSWORD_SQL_LITERAL'})
        for name in slots:
            token = '{{' + name + '}}'
            self.assertEqual(self.sql.count(token), 1)
            self.assertNotIn("'" + token + "'", self.sql)
        self.assertFalse(self.plan['credentials']['generatedNow'])
        self.assertNotRegex(self.sql, r"\bBY\s+['\"]")

    def grants(self):
        return [(set(p.strip() for p in privileges.split(',')), schema, account, host)
                for privileges, schema, account, host in re.findall(r"GRANT\s+([A-Z,\s]+?)\s+ON\s+`([^`]+)`\.\*\s+TO\s+'([^']+)'@'([^']+)'", self.sql)]

    def test_runtime_grants_do_not_cross_schemas_or_add_ddl(self):
        grants = self.grants()
        runtime = [(p, schema, user, host) for p, schema, user, host in grants if user != 'workbench_migration']
        self.assertEqual(runtime, [(DML, 'workbench_platform', 'workbench', '127.0.0.1'), (DML, 'workbench_runner', 'workbench_runner', '127.0.0.1')])

    def test_migration_grants_are_explicit_and_limited_to_current_schema_work(self):
        migration = [(p, schema, host) for p, schema, user, host in self.grants() if user == 'workbench_migration']
        self.assertEqual(migration, [(MIGRATION, 'workbench_platform', '127.0.0.1'), (MIGRATION, 'workbench_runner', '127.0.0.1')])
        self.assertNotRegex(self.sql.upper(), r'\bGRANT\s+ALL\b|WITH GRANT OPTION|\bON\s+\*\.\*|SET\s+GLOBAL')
        self.assertNotIn('DROP', MIGRATION)
        self.assertNotIn('CREATE USER', MIGRATION)

    def test_no_destructive_repair_or_existing_instance_adoption_statements(self):
        for statement in self.statements:
            body = statement.removeprefix(GUARD).lstrip()
            self.assertNotRegex(body, r'^(DROP|TRUNCATE|DELETE|UPDATE|REPLACE|REPAIR|RESET)\b')
        self.assertFalse(self.plan['fixedScope']['existingDatabaseReuse'])

    def test_server_template_has_an_explicit_unknown_option_refusal_guard(self):
        self.assertEqual(self.options['workbench-template-not-rendered'], 'REFUSE_UNAPPROVED_TEMPLATE')
        self.assertNotIn('loose-workbench-template-not-rendered', self.options)
        self.assertEqual(self.plan['rendering']['serverRefusalOption'], 'workbench-template-not-rendered')
        self.assertTrue(self.plan['rendering']['doNotHandEditToEnable'])

    def test_server_only_binds_ipv4_loopback_and_disables_other_entry_points(self):
        self.assertEqual(self.options['bind-address'], '127.0.0.1')
        self.assertEqual(self.options['port'], '13306')
        for name in ['mysqlx', 'named-pipe', 'shared-memory', 'local-infile']:
            self.assertEqual(self.options[name], 'OFF')
        self.assertEqual(self.options['skip-name-resolve'], 'ON')
        self.assertEqual(self.options['secure-file-priv'], 'NULL')

    def test_normal_runtime_requires_tls_and_local_unfilled_certificate_paths(self):
        self.assertEqual(self.options['require-secure-transport'], 'ON')
        self.assertEqual(self.options['tls-version'], 'TLSv1.2,TLSv1.3')
        for name in ['ssl-ca', 'ssl-cert', 'ssl-key']:
            self.assertRegex(self.options[name], r'^"\{\{PRIVATE_[A-Z_]+_MYSQL_PATH\}\}"$')
        self.assertNotIn('ssl-mode=DISABLED', (SETUP / 'my.ini.template').read_text())

    def test_engine_timezone_isolation_strict_and_durability_are_explicit(self):
        self.assertEqual(self.options['default-time-zone'], '"+00:00"')
        self.assertEqual(self.options['transaction-isolation'], 'READ-COMMITTED')
        self.assertEqual(self.options['default-storage-engine'], 'InnoDB')
        self.assertIn('STRICT_TRANS_TABLES', self.options['sql-mode'])
        self.assertEqual(self.options['innodb-flush-log-at-trx-commit'], '1')
        self.assertEqual(self.options['sync-binlog'], '1')
        self.assertEqual(self.options['binlog-format'], 'ROW')
        self.assertEqual(self.options['general-log'], 'OFF')
        self.assertEqual(self.options['slow-query-log'], 'OFF')

    def test_all_data_log_temp_and_binary_paths_are_unfilled_private_slots(self):
        names = ['basedir', 'datadir', 'tmpdir', 'pid-file', 'log-error', 'log-bin']
        for name in names:
            with self.subTest(option=name):
                self.assertRegex(self.options[name], r'^"\{\{[A-Z_]+_MYSQL_PATH\}\}"$')
        self.assertIn('reject symlink/UNC', self.plan['rendering']['pathPolicy'])
        self.assertIn('NUL/newlines', self.plan['rendering']['pathPolicy'])

    def test_plan_cannot_execute_or_grant_approval_to_itself(self):
        self.assertEqual(self.plan['format'], 'workbench-portable-bootstrap-plan-v1')
        self.assertEqual(self.plan['status'], 'offline_templates_only')
        for name in ['executable', 'executorIncluded', 'executionAuthorized']:
            self.assertIs(self.plan[name], False)
        self.assertFalse(self.plan['userFlowIsImplemented'])
        self.assertEqual(len({a['id'] for a in self.plan['actions']}), len(self.plan['actions']))
        self.assertTrue(all(a['executionApprovalRequired'] for a in self.plan['actions'] if a['effect'] != 'read_only'))
        self.assertEqual(set(a['id'] for a in self.plan['requiredApprovals']), {'verified-third-party-artifacts', 'selected-windows-host', 'new-private-instance', 'private-secrets-and-local-processes'})

    def test_binary_and_secret_commands_cannot_silently_become_an_installer(self):
        for action in self.plan['actions']:
            argv = action['argv']
            text = ' '.join(argv).lower()
            self.assertNotIn('--initialize-insecure', text)
            self.assertNotIn('--install', text)
            self.assertNotIn('--force', text)
            self.assertNotIn('mysql_pwd', text)
            self.assertNotRegex(text, r'--password(?:=|\s)|\s-p\S')
            self.assertNotRegex(text, r'\b(sc\.exe|netsh|setx|powershell|cmd\.exe|curl|wget)\b')
            if argv and argv[0].endswith('mysqld.exe'):
                self.assertTrue(argv[1].startswith('--defaults-file='))
            if argv and argv[0].endswith(('mysql.exe', 'mysqladmin.exe')):
                self.assertTrue(argv[1].startswith('--defaults-extra-file='))
        self.assertFalse(self.plan['fixedScope']['newSystemService'])
        self.assertFalse(self.plan['fixedScope']['systemPathChange'])
        self.assertFalse(self.plan['fixedScope']['firewallOrGlobalPermissionChange'])

    def test_secure_initialize_and_networkless_bootstrap_precede_normal_start(self):
        names = list(self.actions)
        self.assertLess(names.index('initialize_data'), names.index('start_bootstrap_memory_only'))
        self.assertLess(names.index('bootstrap_fixed_sql'), names.index('stop_bootstrap_mysql'))
        self.assertLess(names.index('stop_bootstrap_mysql'), names.index('start_private_tls_mysql'))
        self.assertIn('--initialize', self.actions['initialize_data']['argv'])
        bootstrap = self.actions['start_bootstrap_memory_only']
        self.assertIn('--skip-networking', bootstrap['argv'])
        self.assertIn('--shared-memory', bootstrap['argv'])
        sql = self.actions['bootstrap_fixed_sql']
        self.assertIn('--protocol=MEMORY', sql['argv'])
        self.assertIn('--connect-expired-password', sql['argv'])
        self.assertEqual(sql['stdinTemplate'], 'bootstrap.sql.template')
        self.assertFalse(bootstrap['actualWindowsBehaviorVerified'])

    def test_secure_initialize_uses_separate_nonlistening_config_without_future_tls_paths(self):
        self.assertEqual(self.plan['rendering']['initializeServerTemplate'], 'initialize.ini.template')
        action = self.actions['initialize_data']
        self.assertEqual(action['argv'][1], '--defaults-file={{PRIVATE_RENDERED_INITIALIZE_INI}}')
        self.assertEqual(self.initial['workbench-template-not-rendered'], 'REFUSE_UNAPPROVED_TEMPLATE')
        self.assertEqual(self.initial['skip-networking'], 'ON')
        for key in ['mysqlx', 'named-pipe', 'shared-memory']:
            self.assertEqual(self.initial[key], 'OFF')
        self.assertFalse(any(key.startswith(('ssl-', 'tls-')) for key in self.initial))
        for key in ['basedir', 'datadir', 'tmpdir', 'log-error']:
            self.assertRegex(self.initial[key], r'^"\{\{[A-Z_]+_MYSQL_PATH\}\}"$')
        self.assertIn('INITIALIZATION_LOG', self.initial['log-error'])

    def test_normal_tls_config_is_gated_after_initialization_tls_file_checks(self):
        names = list(self.actions)
        self.assertLess(names.index('initialize_data'), names.index('verify_tls_material'))
        self.assertLess(names.index('verify_tls_material'), names.index('start_bootstrap_memory_only'))
        bootstrap = self.actions['start_bootstrap_memory_only']
        self.assertIn('private_tls_material_existence_and_ownership_verified_before_normal_config_is_loaded', bootstrap['requires'])
        self.assertEqual(bootstrap['argv'][1], '--defaults-file={{PRIVATE_RENDERED_MY_INI}}')
        self.assertFalse(self.actions['verify_tls_material']['actualWindowsBehaviorVerified'])

    def test_keytool_store_password_uses_private_file_not_a_password_argument(self):
        argv = self.actions['import_private_ca']['argv']
        self.assertEqual(argv[0], '{{JAVA_HOME}}/bin/keytool.exe')
        index = argv.index('-storepass:file')
        self.assertEqual(argv[index + 1], '{{PRIVATE_TRUST_STORE_PASSWORD_FILE}}')
        self.assertNotIn('-storepass', argv)
        self.assertIn('approved_portable_jdk_contains_keytool', self.actions['import_private_ca']['requires'])

    def test_unknown_initialization_and_shutdown_preserve_original_ownership(self):
        for name in ['initialize_data', 'start_bootstrap_memory_only', 'bootstrap_fixed_sql', 'stop_bootstrap_mysql', 'verify_scopes_and_migrate']:
            text = self.actions[name]['onUnknown'].lower()
            self.assertRegex(text, r'freeze|retain')
        self.assertIn('never repeat --initialize', self.actions['initialize_data']['onUnknown'])
        self.assertIn('do not start replacement', self.actions['stop_bootstrap_mysql']['onUnknown'])
        self.assertIn('no clean, repair', self.actions['verify_scopes_and_migrate']['onUnknown'])

    def test_windows_secret_store_has_no_plaintext_or_same_user_isolation_claim(self):
        secrets = self.plan['credentials']
        self.assertFalse(secrets['plaintextFallbackAllowed'])
        self.assertTrue(secrets['sameWindowsUserIsNotAnIsolationBoundary'])
        self.assertEqual(secrets['webReceives'], ['platform_runtime_only'])
        self.assertEqual(secrets['migrationReceives'], ['migration_scope_for_two_schemas_only'])
        self.assertIn('current-user protected', secrets['atRestRequired'])
        self.assertIn('DPAPI recovery may depend', ' '.join(self.plan['recoveryRules']))

    def test_vendor_native_runtime_and_windows_behavior_stay_unverified(self):
        text = ' '.join(self.plan['hardPreconditions'] + self.plan['notVerified'])
        self.assertIn('VC++', text)
        self.assertIn('do not copy System32', text)
        self.assertIn('shared-memory', text)
        self.assertIn('actual Windows', text)
        self.assertEqual(self.plan['userPasswordsRequiredForNewPrivateRoute'], 0)
        self.assertFalse(self.plan['userFlowIsImplemented'])


if __name__ == '__main__':
    unittest.main()
