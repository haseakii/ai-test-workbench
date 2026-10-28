"""Collect actual Surefire outcomes; a CI database skip must not look like a passed gate."""
import argparse,json,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--require-complete',action='store_true');args=p.parse_args()
cases=[]
for file in sorted(Path('backend').glob('*/target/surefire-reports/TEST-*.xml')):
    for row in ET.parse(file).getroot().findall('testcase'):
        result='fail' if row.find('failure') is not None or row.find('error') is not None else 'skip' if row.find('skipped') is not None else 'pass'
        cases.append({'class':row.get('classname'),'name':row.get('name'),'result':result})
required={'GoldenContractTest','ContractTest','ProviderWireTest','MySqlSemanticsTest','DatabasePrivilegesTest','CommandNamespaceTest','DispatchAtomicityTest','ConcurrentTransactionsTest','EventTerminalConsistencyTest','QuotaIsolationTest','QuotaPeriodTest','TimeAndDecimalTest','ApiContractTest','SyntheticBoundaryTest','MigrationReadinessTest','ConfigurationDraftTest','SdkCompatibilityTest','AcpStdioCompatibilityTest','ContextManifestTest','PreparationContextPortTest','ExecutionSpecsTest','TypedFixtureProviderTest','QueueCommitFaultTest','AdmissionRaceTest','HandoverPackageTest','HandoverServiceTest','AuthorizationMatrixTest','RuntimeProbeTest','RuntimeReadinessTest','RuntimeFilesTest','RuntimeLifecycleControlTest','RuntimeLauncherTest','SetupInspectorTest','WindowsSecretProtectionTest'}
actual={row['class'].rsplit('.',1)[-1] for row in cases}
report={'scope':'actual Java unit/wire/API, MySQL and offline SDK test outcomes; no real Provider conformance claim','total':len(cases),'passed':sum(r['result']=='pass' for r in cases),'failed':sum(r['result']=='fail' for r in cases),'skipped':sum(r['result']=='skip' for r in cases),'missingRequiredClasses':sorted(required-actual),'cases':cases}
file=Path(args.output);file.parent.mkdir(parents=True,exist_ok=True);file.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['cases','scope']}))
raise SystemExit(1 if not cases or report['failed'] or args.require_complete and (report['skipped'] or report['missingRequiredClasses']) else 0)
