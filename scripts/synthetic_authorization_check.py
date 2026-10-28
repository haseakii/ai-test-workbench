"""A11 HTTP ACL matrix for the task's existing local synthetic service.

No SQL, new identities, external resources or account/grant privileges are used. Every
negative object-route check requires an earlier successful request to that same route
and object. JSON replies use the repository's actual OpenAPI response validator when
--validate-api is selected; CSV and SSE are counted separately from JSON validation.
"""
import argparse
import hashlib
import json
import re
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

import synthetic_service_check as fixture


class Matrix:
    def __init__(self, args):
        parsed = urlsplit(args.base_url)
        if parsed.scheme != 'http' or parsed.hostname not in {'127.0.0.1', 'localhost', '::1'} or parsed.username or parsed.password:
            raise ValueError('Only the already authorized loopback synthetic service is supported')
        self.args = args
        self.base = args.base_url.rstrip('/')
        self.root = Path(__file__).resolve().parents[1]
        api = json.loads((self.root / 'contracts/platform.openapi.json').read_text())
        self.routes = [(method.upper(), re.compile('^' + re.sub(r'\{[^}]+\}', '[^/]+', path) + '$'), operation['operationId'])
                       for path, methods in api['paths'].items() for method, operation in methods.items()]
        self.cases = []
        self.requests = []
        self.positive_objects = set()
        self.owned = {}
        self.eligible = {}
        self.case_name = 'setup'
        self.cleanup = []
        fixture.BASE = self.base
        fixture.call = self.call
        fixture.upload = self.upload
        if args.validate_api:
            fixture.enable_api_contract_checks()

    def operation(self, method, path):
        route = '/api' + path.split('?')[0]
        matches = [operation for verb, pattern, operation in self.routes if verb == method and pattern.fullmatch(route)]
        assert len(matches) == 1, f'Expected exactly one documented route for {method} {route}'
        return matches[0]

    def record(self, subject, method, path, status, media, value=None):
        operation = self.operation(method, path)
        row = {'case': self.case_name, 'subject': subject, 'operation': operation, 'method': method,
               'path': path.split('?')[0], 'status': status, 'media': media, 'schemaValidated': False}
        self.requests.append(row)
        if media == 'json' and self.args.validate_api:
            fixture.validate_api(method, path, status, value)
            row['schemaValidated'] = True
        if status == 200:
            self.positive_objects.add((method, path.split('?')[0]))
        return row

    def request(self, subject, path, data=None, method='GET', headers=None, expected=200):
        headers = {'X-Test-Subject': subject, **(headers or {})}
        request = Request(self.base + '/api' + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=12) as response:
                status, raw, content_type = response.status, response.read(), response.headers.get('Content-Type', '')
        except HTTPError as error:
            status, raw, content_type = error.code, error.read(), error.headers.get('Content-Type', '')
        is_json = 'application/json' in content_type
        value = json.loads(raw) if is_json else raw
        self.record(subject, method, path, status, 'json' if is_json else 'csv' if 'text/csv' in content_type else 'binary', value)
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert status in allowed, (subject, method, path, status, value.get('error') if is_json else content_type)
        if status == 200 and method == 'POST' and path == '/conversations':
            self.owned[value['id']] = {'subject': subject, 'environment': json.loads(data)['environmentId']}
        return value

    def call(self, subject, path, body=None, method=None, expected=200):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        return self.request(subject, path, data, method or ('POST' if body is not None else 'GET'),
                            {'Content-Type': 'application/json'} if body is not None else {}, expected)

    def command(self, subject, path, body=None, **kwargs):
        return self.call(subject, path, {'idempotencyKey': str(uuid.uuid4()), **(body or {})}, **kwargs)

    def deny(self, subject, path, body=None, method=None, status=404, code='NOT_FOUND'):
        verb = method or ('POST' if body is not None else 'GET')
        assert (verb, path.split('?')[0]) in self.positive_objects, f'No positive route/object proof: {verb} {path}'
        value = self.call(subject, path, body, method=verb, expected=status)
        assert value['error']['code'] == code, (subject, path, value)
        return value

    def upload(self, conversation, name, amounts, subject='alice', expected=200):
        if expected != 200:
            assert ('POST', '/conversations/' + conversation + '/attachments') in self.positive_objects
        rows = ['order_id,amount,currency,status,occurred_at']
        rows += [f'O{i + 1:03},{amount},EUR,completed,2026-10-25T12:00:00Z' for i, amount in enumerate(amounts)]
        csv = ('\r\n'.join(rows) + '\r\n').encode()
        boundary = 'acl-' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="idempotencyKey"\r\n\r\n{uuid.uuid4()}\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/csv\r\n\r\n').encode() + csv + f'\r\n--{boundary}--\r\n'.encode()
        value = self.request(subject, '/conversations/' + conversation + '/attachments', data, 'POST',
                             {'Content-Type': 'multipart/form-data; boundary=' + boundary}, expected)
        if expected == 404:
            assert value['error']['code'] == 'NOT_FOUND'
        return value

    def grant(self, alias, environment, role='member'):
        return self.command('admin', '/admin/grants', {'alias': alias, 'environmentId': environment, 'role': role})

    def revoke(self, alias, environment, role='member'):
        return self.command('admin', '/admin/grants/revoke', {'alias': alias, 'environmentId': environment, 'role': role})

    def environment(self, peer=False):
        environment = fixture.environment()
        if peer:
            self.grant('bob', environment)
        return environment

    def prepared(self, environment):
        return fixture.prepared(environment)

    def completed(self, peer=False, environment=None):
        environment = environment or self.environment(peer)
        conversation, plan, detail, report = self.prepared(environment)
        fixture.approve(plan)
        snapshot = fixture.wait_state(conversation, {'awaiting_verification'})
        assert len(snapshot['artifacts']) == 1 and all(execution['closed_at'] is not None for execution in snapshot['executions'])
        return environment, conversation, plan, detail, report, snapshot['artifacts'][0]

    def content_eligibility(self, environment, enabled):
        identity = self.call('reviewer', '/session')
        result = self.command('admin', '/admin/content-reviewers', {'alias': 'reviewer', 'environmentId': environment,
            'enabled': enabled, 'expectedAuthorizationRevision': identity['authorizationRevision'],
            'reason': 'Synthetic authorization matrix messages-only review'})
        self.eligible[environment] = enabled
        return result

    def lease(self, conversation, seconds=60):
        body = {'conversationId': conversation, 'purpose': 'Inspect synthetic messages for authorization matrix', 'validForSeconds': seconds}
        return self.command('reviewer', '/admin/content-leases', body)

    def stream(self, subject, conversation, cursor):
        path = '/conversations/' + conversation + '/stream'
        response = urlopen(Request(self.base + '/api' + path,
            headers={'X-Test-Subject': subject, 'Last-Event-ID': cursor}), timeout=5)
        assert response.status == 200 and 'text/event-stream' in response.headers.get('Content-Type', '')
        self.record(subject, 'GET', path, response.status, 'event-stream')
        return response

    @staticmethod
    def frame(response):
        event, data = {}, []
        while True:
            raw = response.readline()
            if not raw:
                raise EOFError('SSE ended before a complete frame')
            line = raw.decode().rstrip('\r\n')
            if not line:
                if event or data:
                    break
                continue
            if ':' in line:
                key, value = line.split(':', 1)
                if key == 'data':
                    data.append(value.lstrip())
                else:
                    event[key] = value.strip()
        if data:
            event['data'] = json.loads('\n'.join(data))
        return event

    def export(self, conversation):
        value = self.call('alice', '/conversations/' + conversation + '/handover')
        assert value['package']['schemaVersion'] == 'fixed-csv-handover-v1'
        # This fixed projection contains safe integers and ASCII field names; canonical key
        # order matches the shared contract for these fixture values. No source bytes exported.
        canonical = json.dumps(value['package'], ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        assert hashlib.sha256(canonical.encode()).hexdigest() == value['packageSha256']
        for forbidden in ('报表与明细金额不一致', 'owner_pid', '/workspace/', 'jdbc:mysql:', 'X-Test-Subject'):
            assert forbidden not in canonical, ('Unexpected body or private runtime data in export', forbidden)
        return value

    def readonly_matrix(self, conversation, plan, artifact, peers=('bob', 'reviewer', 'admin')):
        reference = plan['plan']['contextManifestRef']
        paths = ['/conversations/' + conversation, '/conversations/' + conversation + '/events?limit=100',
                 '/contexts/' + reference, '/artifacts/' + artifact['id'] + '/download',
                 '/conversations/' + conversation + '/handover']
        for path in paths:
            value = self.call('alice', path)
            if path.endswith('/download'):
                assert hashlib.sha256(value).hexdigest() == artifact['sha256']
            for peer in peers:
                self.deny(peer, path)
        snapshot = self.call('alice', '/conversations/' + conversation)
        with self.stream('alice', conversation, snapshot['resumeCursor']) as response:
            frame = self.frame(response)
            assert frame['event'] == 'platform' and frame['data']['events'] == []
        for peer in peers:
            self.deny(peer, '/conversations/' + conversation + '/stream')

    def matrix_owner_and_same_environment(self):
        environment, conversation, plan, detail, _, artifact = self.completed(peer=True)
        self.readonly_matrix(conversation, plan, artifact)
        before = self.call('alice', '/conversations/' + conversation)
        for peer in ('bob', 'reviewer', 'admin'):
            self.upload(conversation, 'denied.csv', ['1.00'], subject=peer, expected=404)
            for suffix, body in [('/messages', {'clientMessageId': str(uuid.uuid4()), 'text': 'denied mutation', 'expectedRevision': before['revision']}),
                                 ('/understanding', {'expectedInputRevision': before['inputRevision']}),
                                 ('/prepare', {'scope': {key: plan['plan'][key] for key in ('detailAttachmentId', 'reportAttachmentId', 'from', 'to', 'timeZone', 'currency', 'status')} | {'expectedInputRevision': before['inputRevision'], 'validForSeconds': 600}})]:
                # Understanding has a fresh owner positive before the negative requests below.
                if suffix == '/understanding':
                    self.command('alice', '/conversations/' + conversation + suffix, {'expectedInputRevision': before['inputRevision']})
                self.deny(peer, '/conversations/' + conversation + suffix, {'idempotencyKey': str(uuid.uuid4()), **body})
        assert self.call('alice', '/conversations/' + conversation)['inputRevision'] == before['inputRevision']
        verify = {'resultRevision': 1, 'resolved': True, 'opinion': 'Synthetic matrix CSV independently checked'}
        receipt = self.command('alice', '/results/' + artifact['id'] + '/verification', verify)
        for peer in ('bob', 'reviewer', 'admin'):
            self.deny(peer, '/results/' + artifact['id'] + '/verification', {'idempotencyKey': str(uuid.uuid4()), **verify})
        self.call('alice', '/commands/' + receipt['commandId'])
        for peer in ('bob', 'reviewer', 'admin'):
            self.deny(peer, '/commands/' + receipt['commandId'])
        title = self.call('alice', '/conversations/' + conversation)
        title_body = {'title': 'acl-owner-' + uuid.uuid4().hex, 'expectedTitleRevision': title['titleRevision']}
        self.command('alice', '/conversations/' + conversation + '/title', title_body, method='PATCH')
        self.command('alice', '/conversations/' + conversation + '/stop')
        for peer in ('bob', 'reviewer', 'admin'):
            self.deny(peer, '/conversations/' + conversation + '/title', {'idempotencyKey': str(uuid.uuid4()), **title_body}, method='PATCH')
            self.deny(peer, '/conversations/' + conversation + '/stop', {'idempotencyKey': str(uuid.uuid4())})
        exported = self.export(conversation)
        assert exported['package']['artifacts'][0]['artifactId'] == artifact['id']
        assert exported['package']['verifications'][0]['resolved'] is True
        assert exported['package']['responsibility']['status'] == 'unassigned'
        return {'environmentId': environment, 'conversationId': conversation, 'bindingId': plan['bindingId'],
                'artifactId': artifact['id'], 'packageSha256': exported['packageSha256'], 'sameEnvironmentPeerStillDenied': True}

    def cross_environment(self):
        owner_environment = self.environment()
        peer_environment = self.environment()
        self.grant('bob', peer_environment)
        peer = self.command('bob', '/conversations', {'environmentId': peer_environment})
        assert self.call('bob', '/conversations/' + peer['id'])['environmentId'] == peer_environment
        allowed = {row['id'] for row in self.call('bob', '/workbench/options')}
        assert peer_environment in allowed and owner_environment not in allowed
        _, conversation, plan, _, _, artifact = self.completed(environment=owner_environment)
        self.readonly_matrix(conversation, plan, artifact, peers=('bob',))
        self.deny('bob', '/conversations', {'idempotencyKey': str(uuid.uuid4()), 'environmentId': owner_environment})
        return {'ownerEnvironmentId': owner_environment, 'peerEnvironmentId': peer_environment, 'validOtherEnvironmentDoesNotAuthorizeOwner': True}

    def foreign_material_association(self):
        environment = self.environment(peer=True)
        owner, plan, detail, _ = self.prepared(environment)
        for alias in ('alice', 'bob'):
            conversation = self.command(alias, '/conversations', {'environmentId': environment})['id']
            self.command(alias, '/conversations/' + conversation + '/messages', {'clientMessageId': str(uuid.uuid4()), 'text': 'Separate authorized conversation', 'expectedRevision': 1})
            report = self.upload(conversation, 'report.csv', ['1.00'], subject=alias)
            own_detail = self.upload(conversation, 'detail.csv', ['1.00'], subject=alias)
            current = self.call(alias, '/conversations/' + conversation)
            scope = {key: plan['plan'][key] for key in ('from', 'to', 'timeZone', 'currency', 'status')}
            scope.update(detailAttachmentId=own_detail['id'], reportAttachmentId=report['id'], expectedInputRevision=current['inputRevision'], validForSeconds=600)
            # Prove this exact owner/conversation route accepts its own material first.
            self.command(alias, '/conversations/' + conversation + '/prepare', {'scope': scope})
            self.command(alias, '/conversations/' + conversation + '/stop')
            until = time.monotonic() + 15
            while True:
                before = self.call(alias, '/conversations/' + conversation)
                if all(execution['closed_at'] is not None for execution in before['executions']):
                    break
                assert time.monotonic() < until, 'Own positive preparation did not close'
                time.sleep(.15)
            denied = self.deny(alias, '/conversations/' + conversation + '/prepare',
                               {'idempotencyKey': str(uuid.uuid4()), 'scope': {**scope, 'detailAttachmentId': detail['id']}})
            assert denied['error']['code'] == 'NOT_FOUND'
            after = self.call(alias, '/conversations/' + conversation)
            assert after['inputRevision'] == before['inputRevision'] and after['approvals'] == before['approvals'] and after['executions'] == before['executions']
        return {'originalConversationId': owner, 'sameOwnerForeignConversationDenied': True, 'peerForeignSourceDenied': True}

    def reviewer_lease_scope_and_withdrawal(self):
        environment, conversation, plan, _, _, artifact = self.completed(peer=True)
        self.readonly_matrix(conversation, plan, artifact)
        body_path = '/admin/records/' + conversation + '/content'
        status_path = '/admin/records/' + conversation + '/content-lease'
        denied = self.command('reviewer', '/admin/content-leases', {'conversationId': conversation, 'purpose': 'No eligibility must fail', 'validForSeconds': 60}, expected=403)
        assert denied['error']['code'] == 'CONTENT_REVIEW_NOT_GRANTED'
        self.content_eligibility(environment, True)
        lease = self.lease(conversation)
        body = self.call('reviewer', body_path)
        assert body['messages'] and set(body) == {'conversationId', 'messages', 'validUntil'}
        assert self.call('reviewer', status_path)['scope'] == 'messages_only'
        for alias in ('alice', 'bob', 'admin'):
            self.deny(alias, body_path, status=403, code='APPROVER_REQUIRED')
            self.deny(alias, status_path, status=403, code='APPROVER_REQUIRED')
            self.deny(alias, '/admin/content-leases', {'idempotencyKey': str(uuid.uuid4()), 'conversationId': conversation, 'purpose': 'Role does not grant a body lease', 'validForSeconds': 60}, status=403, code='APPROVER_REQUIRED')
        self.deny('reviewer', '/contexts/' + plan['plan']['contextManifestRef'])
        self.deny('reviewer', '/conversations/' + conversation + '/events')
        self.deny('reviewer', '/artifacts/' + artifact['id'] + '/download')
        self.deny('reviewer', '/conversations/' + conversation + '/handover')
        self.call('reviewer', '/commands/' + lease['commandId'])
        self.content_eligibility(environment, False)
        self.deny('reviewer', body_path, status=403, code='CONTENT_REVIEW_NOT_GRANTED')
        self.deny('reviewer', status_path, status=403, code='CONTENT_REVIEW_NOT_GRANTED')
        self.deny('reviewer', '/commands/' + lease['commandId'], status=403, code='CONTENT_REVIEW_NOT_GRANTED')
        return {'conversationId': conversation, 'leaseMessagesOnly': True, 'qualificationWithdrawalReauthorizesCachedReceipt': True}

    def lease_expiry(self):
        environment = self.environment()
        conversation = self.command('alice', '/conversations', {'environmentId': environment})['id']
        self.command('alice', '/conversations/' + conversation + '/messages', {'clientMessageId': str(uuid.uuid4()), 'text': 'Expiring synthetic body lease', 'expectedRevision': 1})
        self.content_eligibility(environment, True)
        self.lease(conversation, seconds=2)
        body_path = '/admin/records/' + conversation + '/content'
        status_path = '/admin/records/' + conversation + '/content-lease'
        assert self.call('reviewer', body_path)['messages']
        self.call('reviewer', status_path)
        time.sleep(2.1)
        self.deny('reviewer', body_path, status=403, code='CONTENT_LEASE_EXPIRED')
        self.deny('reviewer', status_path, status=403, code='CONTENT_LEASE_EXPIRED')
        return {'conversationId': conversation, 'expiredBodyAndLeaseStatusDenied': True}

    def owner_revocation_and_open_stream(self):
        environment, conversation, plan, _, _, artifact = self.completed()
        self.readonly_matrix(conversation, plan, artifact, peers=())
        verification = {'resultRevision': 1, 'resolved': True, 'opinion': 'Verify synthetic result before grant withdrawal'}
        verified = self.command('alice', '/results/' + artifact['id'] + '/verification', verification)
        self.call('alice', '/commands/' + verified['commandId'])
        self.content_eligibility(environment, True)
        lease = self.lease(conversation)
        body_path = '/admin/records/' + conversation + '/content'
        self.call('reviewer', body_path)
        self.call('reviewer', '/commands/' + lease['commandId'])
        self.call('alice', '/commands/' + plan['commandId'])
        snapshot = self.call('alice', '/conversations/' + conversation)
        with self.stream('alice', conversation, snapshot['resumeCursor']) as response:
            assert self.frame(response)['data']['events'] == []
            self.revoke('alice', environment)
            until = time.monotonic() + 5
            while True:
                frame = self.frame(response)
                if frame.get('event') == 'authorization-lost':
                    assert frame['data']['code'] == 'NOT_FOUND' and 'events' not in frame['data']
                    break
                assert frame['data']['events'] == [] and time.monotonic() < until
        for path in ['/conversations/' + conversation, '/conversations/' + conversation + '/events',
                     '/conversations/' + conversation + '/stream', '/contexts/' + plan['plan']['contextManifestRef'],
                     '/artifacts/' + artifact['id'] + '/download', '/commands/' + plan['commandId'],
                     '/commands/' + verified['commandId'], '/conversations/' + conversation + '/handover']:
            self.deny('alice', path)
        self.deny('alice', '/results/' + artifact['id'] + '/verification', {'idempotencyKey': str(uuid.uuid4()), **verification})
        self.upload(conversation, 'revoked.csv', ['1.00'], expected=404)
        self.deny('reviewer', body_path, status=403, code='SOURCE_AUTHORIZATION_REVOKED')
        self.deny('reviewer', '/commands/' + lease['commandId'], status=403, code='SOURCE_AUTHORIZATION_REVOKED')
        return {'conversationId': conversation, 'existingSseEndsWithoutMoreBodyEvents': True, 'revokedOwnerBlocksDerivedInterfacesAndReviewerLease': True}

    def source_and_new_input(self):
        environment, conversation, plan, detail, _, artifact = self.completed(peer=True)
        self.readonly_matrix(conversation, plan, artifact)
        verify = {'resultRevision': 1, 'resolved': True, 'opinion': 'Confirm before source revocation'}
        receipt = self.command('alice', '/results/' + artifact['id'] + '/verification', verify)
        self.call('alice', '/commands/' + receipt['commandId'])
        revoke_path = '/attachments/' + detail['id'] + '/revoke'
        self.command('alice', revoke_path)
        for peer in ('bob', 'reviewer', 'admin'):
            self.deny(peer, revoke_path, {'idempotencyKey': str(uuid.uuid4())})
        self.deny('alice', '/contexts/' + plan['plan']['contextManifestRef'], status=409, code='CONTEXT_STALE')
        self.deny('alice', '/artifacts/' + artifact['id'] + '/download', status=409, code='SOURCE_REVOKED_OR_CHANGED')
        self.deny('alice', '/results/' + artifact['id'] + '/verification', {'idempotencyKey': str(uuid.uuid4()), **verify}, status=409, code='SOURCE_REVOKED_OR_CHANGED')
        self.deny('alice', '/commands/' + receipt['commandId'], status=409, code='SOURCE_REVOKED_OR_CHANGED')
        self.deny('alice', '/conversations/' + conversation + '/handover', status=409, code='SOURCE_REVOKED_OR_CHANGED')
        # A new fixed flow exercises input invalidation independently from source withdrawal.
        _, newer, new_plan, _, _, old_artifact = self.completed(environment=environment)
        original = self.export(newer)
        self.call('alice', '/contexts/' + new_plan['plan']['contextManifestRef'])
        self.call('alice', '/artifacts/' + old_artifact['id'] + '/download')
        old_verification = {'resultRevision': 1, 'resolved': True, 'opinion': 'Verify old revision before new input'}
        old_receipt = self.command('alice', '/results/' + old_artifact['id'] + '/verification', old_verification)
        self.call('alice', '/commands/' + old_receipt['commandId'])
        before = self.call('alice', '/conversations/' + newer)
        self.command('alice', '/conversations/' + newer + '/messages', {'clientMessageId': str(uuid.uuid4()), 'text': 'New input changes fixed context', 'expectedRevision': before['revision']})
        self.deny('alice', '/contexts/' + new_plan['plan']['contextManifestRef'], status=409, code='CONTEXT_STALE')
        self.deny('alice', '/artifacts/' + old_artifact['id'] + '/download', status=409, code='RESULT_STALE')
        self.deny('alice', '/results/' + old_artifact['id'] + '/verification', {'idempotencyKey': str(uuid.uuid4()), **old_verification}, status=409, code='RESULT_STALE')
        self.deny('alice', '/commands/' + old_receipt['commandId'], status=409, code='RESULT_STALE')
        stale = self.export(newer)['package']
        assert stale['context']['state'] == 'stale' and stale['context']['sha256'] == original['package']['context']['sha256']
        assert stale['artifacts'] == [] and any(item['code'] == 'HISTORIC_RESULT_NOT_EXPORTED' for item in stale['unresolved'])
        return {'sourceRevokedConversationId': conversation, 'newInputConversationId': newer, 'oldContextAndResultUnusable': True, 'handoverLabelsStaleContextAndOmitsOldResult': True}

    def cursor_subject_conversation_and_reconnect(self):
        environment = self.environment(peer=True)
        owner = self.command('alice', '/conversations', {'environmentId': environment})['id']
        peer = self.command('bob', '/conversations', {'environmentId': environment})['id']
        snapshot = self.call('alice', '/conversations/' + owner)
        peer_snapshot = self.call('bob', '/conversations/' + peer)
        cursor = snapshot['resumeCursor']
        self.call('alice', '/conversations/' + owner + '/events?cursor=' + cursor)
        self.deny('bob', '/conversations/' + owner + '/events?cursor=' + cursor)
        self.call('bob', '/conversations/' + peer + '/events')
        self.deny('bob', '/conversations/' + peer + '/events?cursor=' + cursor, status=410, code='CURSOR_INVALID')
        self.deny('alice', '/conversations/' + owner + '/events?cursor=' + peer_snapshot['resumeCursor'], status=410, code='CURSOR_INVALID')
        watermark = snapshot['eventWatermark']
        with self.stream('alice', owner, cursor) as response:
            assert self.frame(response)['data']['events'] == []
            self.command('alice', '/conversations/' + owner + '/messages', {'clientMessageId': str(uuid.uuid4()), 'text': 'First event after W', 'expectedRevision': snapshot['revision']})
            until = time.monotonic() + 5
            frame = self.frame(response)
            while not frame['data']['events']:
                assert time.monotonic() < until, 'No event arrived after saved message'
                frame = self.frame(response)
            assert frame['data']['events'][0]['sequence'] == watermark + 1
            cursor = frame['id']
        snapshot = self.call('alice', '/conversations/' + owner)
        with self.stream('alice', owner, cursor) as response:
            assert self.frame(response)['data']['events'] == []
            self.command('alice', '/conversations/' + owner + '/messages', {'clientMessageId': str(uuid.uuid4()), 'text': 'Next event after reconnect', 'expectedRevision': snapshot['revision']})
            until = time.monotonic() + 5
            frame = self.frame(response)
            while not frame['data']['events']:
                assert time.monotonic() < until, 'No event arrived after reconnect'
                frame = self.frame(response)
            assert frame['data']['events'][0]['sequence'] == watermark + 2
        self.deny('alice', '/conversations/' + owner + '/stream?cursor=' + peer_snapshot['resumeCursor'], status=410, code='CURSOR_INVALID')
        conflict = self.request('alice', '/conversations/' + owner + '/stream?cursor=' + cursor,
                                headers={'Last-Event-ID': peer_snapshot['resumeCursor']}, expected=409)
        assert conflict['error']['code'] == 'CURSOR_CONFLICT'
        for peer_alias in ('bob', 'reviewer', 'admin'):
            self.deny(peer_alias, '/conversations/' + owner + '/stream')
        return {'conversationId': owner, 'snapshotWatermark': watermark, 'resumedSequences': [watermark + 1, watermark + 2], 'foreignCursorsDenied': True}

    def metadata_is_not_body_authority(self):
        environment = self.environment(peer=True)
        conversation, plan, _, _ = self.prepared(environment)
        marker = 'acl-http-' + uuid.uuid4().hex
        snapshot = self.call('alice', '/conversations/' + conversation)
        self.command('alice', '/conversations/' + conversation + '/title', {'title': marker, 'expectedTitleRevision': snapshot['titleRevision']}, method='PATCH')
        for alias in ('admin', 'reviewer'):
            records = self.call(alias, '/admin/records?' + urlencode({'query': marker}))
            assert len(records) == 1 and records[0]['conversationId'] == conversation
            assert not {'messages', 'attachments', 'artifacts'} & records[0].keys()
        self.call('alice', '/contexts/' + plan['plan']['contextManifestRef'])
        for alias in ('admin', 'reviewer'):
            self.deny(alias, '/conversations/' + conversation)
            self.deny(alias, '/contexts/' + plan['plan']['contextManifestRef'])
        return {'conversationId': conversation, 'boundedMetadataQueryFindsOwnFixture': True, 'metadataDoesNotAuthorizeBodyOrContext': True}

    def cleanup_case(self, created):
        failures = []
        for conversation in created:
            owned = self.owned[conversation]
            try:
                snapshot = self.call(owned['subject'], '/conversations/' + conversation, expected=(200, 404))
                if 'error' in snapshot:
                    self.grant(owned['subject'], owned['environment'])
                    self.cleanup.append({'conversationId': conversation, 'action': 'restore_only_own_fixture_grant_for_cleanup'})
                    snapshot = self.call(owned['subject'], '/conversations/' + conversation)
                if any(execution['closed_at'] is None for execution in snapshot['executions']):
                    self.command(owned['subject'], '/conversations/' + conversation + '/stop')
                    until = time.monotonic() + 15
                    while True:
                        snapshot = self.call(owned['subject'], '/conversations/' + conversation)
                        if all(execution['closed_at'] is not None for execution in snapshot['executions']):
                            break
                        assert time.monotonic() < until, 'Owned binding did not close; retain data and investigate'
                        time.sleep(.15)
                self.cleanup.append({'conversationId': conversation, 'openBindings': 0, 'historyDeleted': False})
            except Exception as error:
                failures.append({'conversationId': conversation, 'error': str(error)})
        for environment, enabled in list(self.eligible.items()):
            if enabled:
                try:
                    self.content_eligibility(environment, False)
                except Exception as error:
                    failures.append({'environmentId': environment, 'error': str(error)})
        assert not failures, ('cleanup_failed', failures)

    def check(self, name, action):
        self.case_name = name
        before = set(self.owned)
        start, first = time.monotonic(), len(self.requests)
        row = {'name': name, 'result': 'pass'}
        try:
            row['evidence'] = action()
        except Exception as error:
            row.update(result='fail', error=str(error))
        finally:
            self.case_name = name + ':cleanup'
            try:
                self.cleanup_case(set(self.owned) - before)
            except Exception as error:
                row.update(result='fail', cleanupError=str(error))
        relevant = self.requests[first:]
        row['seconds'] = round(time.monotonic() - start, 3)
        row['requestCount'] = len(relevant)
        row['jsonSchemaChecks'] = sum(item['schemaValidated'] for item in relevant)
        row['operationStatusCounts'] = self.counts(relevant)
        self.cases.append(row)
        print(name + ': ' + row['result'], flush=True)

    @staticmethod
    def counts(rows):
        counts = {}
        for row in rows:
            status = counts.setdefault(row['operation'], {}).setdefault(str(row['status']), {'responses': 0, 'jsonSchemaChecks': 0})
            status['responses'] += 1
            status['jsonSchemaChecks'] += int(row['schemaValidated'])
        return counts

    def run(self):
        for name, action in [('ACL01 owner positives and same-environment object denial', self.matrix_owner_and_same_environment),
                             ('ACL02 valid other environment does not grant foreign objects', self.cross_environment),
                             ('ACL03 foreign-conversation CSV cannot enter fixed scope', self.foreign_material_association),
                             ('ACL04 reviewer lease is messages-only and withdrawal rechecks receipts', self.reviewer_lease_scope_and_withdrawal),
                             ('ACL05 expired body lease denies content and status', self.lease_expiry),
                             ('ACL06 owner grant withdrawal ends active SSE and derived access', self.owner_revocation_and_open_stream),
                             ('ACL07 source withdrawal and new input invalidate derived material', self.source_and_new_input),
                             ('ACL08 cursors bind subject and conversation and SSE resumes W+1', self.cursor_subject_conversation_and_reconnect),
                             ('ACL09 admin metadata does not grant body or context', self.metadata_is_not_body_authority)]:
            self.check(name, action)
        report = {'scope': 'Actual local HTTP synthetic identity/provider/CSV authorization matrix; no real identity or Agent claim',
            'passed': sum(row['result'] == 'pass' for row in self.cases), 'total': len(self.cases), 'cases': self.cases,
            'operationStatusCounts': self.counts(self.requests), 'requests': self.requests, 'cleanup': self.cleanup,
            'jsonSchemaChecks': sum(row['schemaValidated'] for row in self.requests),
            'nonJsonResponses': {'csv': sum(row['media'] == 'csv' for row in self.requests), 'sseConnections': sum(row['media'] == 'event-stream' for row in self.requests)},
            'fixtureQuotaAdjustments': fixture.fixture_quota_adjustments,
            'excluded': ['No original attachment download route exists; it is not counted as an ACL test.',
                         'No independent body-lease revoke route exists; real eligibility withdrawal, source grant withdrawal and lease expiry invalidate access.',
                         'No production identity, real Provider/Agent session sanitation or business resources.',
                         'SSE framing and CSV SHA checks are not counted as JSON response-schema validation.']}
        output = Path(self.args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'passed': report['passed'], 'total': report['total'], 'jsonSchemaChecks': report['jsonSchemaChecks'], 'output': str(output)}))
        return 0 if report['passed'] == report['total'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--output', required=True)
    parser.add_argument('--validate-api', action='store_true')
    return Matrix(parser.parse_args()).run()


if __name__ == '__main__':
    raise SystemExit(main())
