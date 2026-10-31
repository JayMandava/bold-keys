"""Mocked private API tests: no token, repository mutation or production export occurs."""
import base64
from datetime import datetime, timezone, timedelta
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_delivery as d
import pepk_export as e

NOW=datetime(2026,10,31,12,tzinfo=timezone.utc)
CUTOFF='2026-10-31T18:00:00Z'
SHA='a'*40


class Fake:
    def __init__(self):
        self.repo={'id':123,'full_name':d.TARGET,'private':True,'visibility':'private','default_branch':'main',
                   'owner':{'id':d.OWNER_ID,'login':'JayMandava'},'permissions':{'push':True}}
        self.headers={};self.files={};self.puts=[];self.uncertain=False;self.corrupt=False
    def request(self,suffix='',method='GET',body=None):
        if not suffix:return 200,self.repo,self.headers
        name=suffix.removesuffix('?ref=main')
        if method=='PUT':
            self.puts.append(name);self.files[name]=base64.b64decode(body['content'])
            if self.uncertain:raise ValueError('synthetic uncertain write')
            return 201,{},{}
        if name not in self.files:return 404,{},{}
        data=self.files[name];sha=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
        return 200,{'type':'file','sha':sha,'size':len(data),'encoding':'base64',
                    'content':base64.b64encode(data+(b'bad' if self.corrupt else b'')).decode()},{}


class DeliveryChecks(unittest.TestCase):
    def test_fixed_private_owner_identity_and_write_access_required(self):
        for key,value in [('private',False),('visibility','public'),('full_name','other/repo'),('id',None),
                          ('default_branch','other'),('archived',True),('disabled',True),('permissions',{})]:
            client=Fake();client.repo[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):d.destination(client,CUTOFF,NOW)
        client=Fake();client.repo['owner']['id']=1
        with self.assertRaises(ValueError):d.destination(client,CUTOFF,NOW)

    def test_cutoff_required_future_and_bounded_without_claiming_credential_expiry(self):
        for cutoff in [None,'tomorrow','2026-10-31T11:00:00Z','2026-11-02T00:00:00Z']:
            with self.subTest(cutoff=cutoff),self.assertRaises(ValueError):d.destination(Fake(),cutoff,NOW)
        state=d.destination(Fake(),CUTOFF,NOW)
        self.assertIsNone(state['credential_expires_at']);self.assertEqual(state['credential_expiry_status'],'UNKNOWN')
        self.assertEqual(state['effective_deadline_at'],d.instant(CUTOFF).isoformat())
        self.assertEqual(state['operational_cutoff_at'],d.instant(CUTOFF).isoformat())

    def test_authoritative_expiry_before_or_after_cutoff_uses_earlier_deadline(self):
        for header,deadline in [('2026-10-31 17:00:00 UTC','2026-10-31T17:00:00Z'),
                                ('2026-10-31 20:00:00 UTC',CUTOFF)]:
            client=Fake();client.headers={'GitHub-Authentication-Token-Expiration':header}
            state=d.destination(client,CUTOFF,NOW)
            self.assertEqual(state['credential_expiry_status'],'API_REPORTED')
            self.assertEqual(state['credential_expires_at'],d.instant(header).isoformat())
            self.assertEqual(state['effective_deadline_at'],d.instant(deadline).isoformat())

    def test_authoritative_expired_or_over_one_day_credentials_still_rejected(self):
        for header in ['2026-10-31T11:00:00Z','2026-11-03T00:00:00Z']:
            client=Fake();client.headers={'github-authentication-token-expiration':header}
            with self.assertRaisesRegex(ValueError,'credential expiry'):d.destination(client,CUTOFF,NOW)

    def test_repository_id_cannot_change(self):
        with self.assertRaises(ValueError):d.destination(Fake(),CUTOFF,NOW,expected_id=456)

    def test_redirects_token_shape_and_path_allowlist_fail_closed(self):
        for token in [None,'ghp_fixture','github_pat_bad\nvalue']:
            with self.assertRaises(ValueError):d.Client(token)
        client=d.Client('github_pat_fixture_only')
        for suffix in ['/user','/contents/private.key','https://other.invalid']:
            with self.assertRaises(ValueError):client.request(suffix)
        with self.assertRaises(ValueError):d.NoRedirect().redirect_request(None,None,302,'',{},'https://other.invalid')

    def transfer(self,client,clock=None,after_precheck=None):
        clock=clock or (lambda:NOW)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name in d.NAMES:(root/name).write_bytes(b'opaque-encrypted-fixture' if name.endswith('zip') else b'public-cert-fixture')
            with patch.object(d,'utc_now',side_effect=clock),patch.object(d,'validate_ready'),patch.object(e,'verify_export_output'):
                proof=d.precheck(client,CUTOFF,SHA,'1','1')
                if after_precheck:after_precheck(client)
                return d.deliver(client,CUTOFF,proof,root,root,{},SHA,'1','1')

    def test_cutoff_reached_between_writes_prevents_second_write(self):
        time=[NOW]
        class Expiring(Fake):
            def request(self,suffix='',method='GET',body=None):
                result=super().request(suffix,method,body)
                if method=='PUT':time[0]=d.instant(CUTOFF)
                return result
        client=Expiring()
        with self.assertRaisesRegex(ValueError,'cutoff'):self.transfer(client,clock=lambda:time[0])
        self.assertEqual(len(client.puts),1)

    def test_cutoff_expiring_during_metadata_get_rejects_precheck(self):
        time=[NOW]
        class Slow(Fake):
            def request(self,suffix='',method='GET',body=None):
                result=super().request(suffix,method,body)
                if suffix=='':time[0]=d.instant(CUTOFF)
                return result
        client=Slow()
        with patch.object(d,'utc_now',side_effect=lambda:time[0]),self.assertRaisesRegex(ValueError,'cutoff'):
            d.precheck(client,CUTOFF,SHA,'1','1')
        self.assertEqual(client.puts,[])

    def test_known_earlier_expiry_not_forgotten_when_header_disappears(self):
        client=Fake();client.headers={'github-authentication-token-expiration':'2026-10-31T17:00:00Z'}
        time=[NOW]
        def lose_header(c):c.headers={};time[0]=d.instant('2026-10-31T17:00:00Z')
        with self.assertRaisesRegex(ValueError,'deadline'):self.transfer(client,clock=lambda:time[0],after_precheck=lose_header)
        self.assertEqual(client.puts,[])

    def test_exact_two_files_only_and_readback(self):
        client=Fake();receipt=self.transfer(client)
        self.assertEqual(len(client.puts),2);self.assertEqual({x['name'] for x in receipt['files']},set(d.NAMES))
        self.assertEqual(receipt['credential_expiry_status'],'UNKNOWN');self.assertIsNone(receipt['credential_expires_at'])
        self.assertEqual(receipt['effective_deadline_at'],d.instant(CUTOFF).isoformat())
        self.assertTrue(all('/transfers/'+SHA+'/1-1/' in x for x in client.puts))
        self.assertFalse(any(x in str(client.puts) for x in ['SHA256SUMS','provenance','keystore']))

    def test_uncertain_write_readback_resolves_without_retry(self):
        client=Fake();client.uncertain=True;receipt=self.transfer(client)
        self.assertEqual(len(client.puts),2)
        self.assertTrue(all(x['uncertain_put_resolved_by_readback'] for x in receipt['files']))

    def test_existing_path_never_overwritten(self):
        client=Fake();client.files['/contents/transfers/'+SHA+'/1-1/'+d.NAMES[0]]=b'existing'
        with self.assertRaises(ValueError):self.transfer(client)
        self.assertEqual(client.puts,[])

    def test_readback_corruption_stops_after_first_write(self):
        client=Fake();client.corrupt=True
        with self.assertRaises(ValueError):self.transfer(client)
        self.assertEqual(len(client.puts),1)

    def test_public_destination_stops_before_write(self):
        client=Fake();client.repo['private']=False
        with self.assertRaises(ValueError):self.transfer(client)
        self.assertEqual(client.puts,[])

    def test_runtime_rejection_precedes_token_lookup_or_client_creation(self):
        class NoTokenReads:
            def get(self,name):raise AssertionError('Token must not be looked up')
        with patch.object(d,'validate_ready'),patch.object(d,'verify_runtime_identities',side_effect=ValueError('runtime drift')),patch.object(d,'Client') as client:
            with self.assertRaisesRegex(ValueError,'runtime drift'):d.checked_client({'toolchain':{}},NoTokenReads())
            client.assert_not_called()

    def test_ready_and_runtime_guards_precede_dedicated_mock_token_client(self):
        class Token:
            def get(self,name):
                if name!='BOLD_PEPK_DELIVERY_TOKEN':raise AssertionError('Wrong credential selector')
                return 'github_pat_fixture_only'
        with patch.object(d,'validate_ready') as ready,patch.object(d,'verify_runtime_identities') as runtime,patch.object(d,'Client') as client:
            d.checked_client({'toolchain':{'public':'fixture'}},Token())
            ready.assert_called_once();runtime.assert_called_once_with({'public':'fixture'})
            client.assert_called_once_with('github_pat_fixture_only')

    def test_context_validation_reads_only_public_identity_before_runtime_token_guard(self):
        class PublicContext(dict):
            def get(self,name,*args):
                if name not in ('GITHUB_EVENT_NAME','GITHUB_REPOSITORY','GITHUB_REF','GITHUB_SHA','GITHUB_WORKFLOW_SHA'):
                    raise AssertionError('Context must not read a credential')
                return super().get(name,*args)
        context=PublicContext(GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REPOSITORY='JayMandava/bold-keys',
                              GITHUB_REF='refs/heads/main',GITHUB_SHA=SHA,GITHUB_WORKFLOW_SHA=SHA)
        d.check_delivery_context(SHA,context)
        context['GITHUB_REF']='refs/heads/other'
        with self.assertRaises(ValueError):d.check_delivery_context(SHA,context)
