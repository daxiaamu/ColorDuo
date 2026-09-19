import importlib.util, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('publish',Path(__file__).parents[1]/'publish_update.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class MetadataTests(unittest.TestCase):
    def fixture(self):
        return dict(schemaVersion=1,channel='beta',versionCode=14,size=123,maxForcedVersionCode=0,policyRevision=1,sha256='a'*64,publishedAt='2026-09-20T00:00:00Z',urls=['https://cdn%d.example/a'%i for i in range(5)]+['https://github.com/a'])
    def test_valid(self): p.validate(self.fixture())
    def test_no_fake_hosts(self):
        m=self.fixture();m['urls'][1]='https://cdn0.example/b'
        with self.assertRaises(ValueError):p.validate(m)
    def test_no_http(self):
        m=self.fixture();m['urls'][0]='http://cdn0.example/a'
        with self.assertRaises(ValueError):p.validate(m)
    def test_required_boundary(self):
        m=self.fixture();m['maxForcedVersionCode']=14
        with self.assertRaises(ValueError):p.validate(m)
    def test_reject_string_code(self):
        m=self.fixture();m['versionCode']='14'
        with self.assertRaises(ValueError):p.validate(m)
    def test_canonical_stable(self): self.assertEqual(p.canonical({'b':2,'a':1}),p.canonical({'a':1,'b':2}))
    def test_more_than_five_candidates(self):self.assertGreaterEqual(len(p.candidates('https://github.com/a','v1','a.apk')),5)
if __name__=='__main__':unittest.main()
