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
EXPIRY='2026-10-31T18:00:00Z'
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
            with self.subTest(key=key),self.assertRaises(ValueError):d.destination(client,EXPIRY,NOW)
        client=Fake();client.repo['owner']['id']=1
        with self.assertRaises(ValueError):d.destination(client,EXPIRY,NOW)

    def test_expiry_required_bounded_and_evidence_distinguished(self):
        for expiry in [None,'tomorrow','2026-10-31T11:00:00Z','2026-11-02T00:00:00Z']:
            with self.subTest(expiry=expiry),self.assertRaises(ValueError):d.destination(Fake(),expiry,NOW)
        client=Fake();self.assertIn('owner-supplied',d.destination(client,EXPIRY,NOW)['expiry_evidence'])
        client.headers={'GitHub-Authentication-Token-Expiration':'2026-10-31 17:00:00 UTC'}
        self.assertIn('API-reported',d.destination(client,EXPIRY,NOW)['expiry_evidence'])
        client.headers={'github-authentication-token-expiration':'2026-11-03T00:00:00Z'}
        with self.assertRaises(ValueError):d.destination(client,EXPIRY,NOW)

    def test_repository_id_cannot_change(self):
        with self.assertRaises(ValueError):d.destination(Fake(),EXPIRY,NOW,expected_id=456)

    def test_redirects_token_shape_and_path_allowlist_fail_closed(self):
        for token in [None,'ghp_fixture','github_pat_bad\nvalue']:
            with self.assertRaises(ValueError):d.Client(token)
        client=d.Client('github_pat_fixture_only')
        for suffix in ['/user','/contents/private.key','https://other.invalid']:
            with self.assertRaises(ValueError):client.request(suffix)
        with self.assertRaises(ValueError):d.NoRedirect().redirect_request(None,None,302,'',{},'https://other.invalid')

    def transfer(self,client):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name in d.NAMES:(root/name).write_bytes(b'opaque-encrypted-fixture' if name.endswith('zip') else b'public-cert-fixture')
            proof={'repository':d.TARGET,'repository_id':123,'private':True,**d.context(SHA,'1','1')}
            with patch.object(d,'validate_ready'),patch.object(e,'verify_export_output'),patch.object(d,'destination',side_effect=lambda c,*a,**k: {'repository_id':123} if c.repo['private'] else (_ for _ in ()).throw(ValueError('changed privacy'))):
                return d.deliver(client,EXPIRY,proof,root,root,{},SHA,'1','1')

    def test_exact_two_files_only_and_readback(self):
        client=Fake();receipt=self.transfer(client)
        self.assertEqual(len(client.puts),2);self.assertEqual({x['name'] for x in receipt['files']},set(d.NAMES))
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
