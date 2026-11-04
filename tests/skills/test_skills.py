import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('dougle', ROOT / 'skill-runtime/dougle.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.project = self.base / 'project'
        self.project.mkdir()
        self.state = self.base / 'state'
        self.store = d.Store(self.project, self.state)

    def tearDown(self):
        self.store.db.close()
        self.tmp.cleanup()

    def file(self, name='Receiving.java', text='class Receiving { void confirmInbound() {} } // 입하 확정'):
        p = self.project / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        return p

    def connect(self):
        return self.store.connect({'target': '.'})

    def search(self, query='입하 확정', **kw):
        return self.store.search({'query': query, **kw})

    def test_connect_search_source_unchanged(self):
        p = self.file(); before = p.read_bytes()
        result = self.connect()
        self.assertEqual(result['status'], 'ready')
        result = self.search()
        self.assertIn('confirmInbound', result['evidence'][0]['body'])
        self.assertEqual(p.read_bytes(), before)
        self.assertEqual(result['evidence'][0]['line'], 1)

    def test_cross_provider_actual_installed_cli(self):
        self.file()
        home = self.base / 'user'
        subprocess.run([sys.executable, str(ROOT/'scripts/install-skills.py'), '--home', str(home)], check=True, capture_output=True)
        env = {**os.environ, 'DOUGLE_HOME': str(self.state)}
        def call(provider, skill, command, payload):
            p = home/provider/'skills'/skill/'scripts/dougle.py'
            r = subprocess.run([sys.executable, str(p), command, '--workspace', str(self.project)], input=json.dumps(payload), text=True, capture_output=True, env=env)
            self.assertEqual(r.returncode, 0, r.stdout+r.stderr)
            return json.loads(r.stdout)['result']
        connected = call('.claude','dougle-connect','connect',{'target':'.'})
        evidence = call('.agents','dougle-ask','search',{'query':'입하 확정'})['evidence'][0]
        call('.claude','dougle-ask','remember',{'topic':'입하','statement':'입하 확정 구현','kind':'supported','evidence':[evidence['citation']]})
        answer = call('.agents','dougle-ask','search',{'query':'입하'})
        self.assertEqual(len(answer['memory']), 1)
        self.assertEqual(answer['sources'][0]['id'], connected['id'])

    def test_workspace_isolation(self):
        self.file(); self.connect()
        other = self.base/'other'; other.mkdir()
        s = d.Store(other, self.state)
        try:
            self.assertEqual(s.search({'query':'입하'})['evidence'], [])
            with self.assertRaises(d.Problem):
                s.forget({'source':self.store.status()['sources'][0]['id']})
        finally: s.db.close()

    def test_normalization_and_query_expansion(self):
        self.file('Strategy.java', 'interface Strategy { Hand nextHand(); }'); self.connect()
        r = self.search('전략패턴 예제좀', alternatives=['Strategy', 'next hand'])
        self.assertIn('Strategy', r['evidence'][0]['body'])

    def test_spacing(self):
        self.file(text='입하확정시 receipt 테이블 갱신'); self.connect()
        self.assertTrue(self.search('입하 확정')['evidence'])

    def test_fts_injection_is_data(self):
        self.file(); self.connect()
        self.search('" OR * NOT NEAR (입하)')
        self.assertTrue(self.search()['evidence'])

    def test_changed_deleted_source_invalidates_memory(self):
        p = self.file(); self.connect()
        citation = self.search()['evidence'][0]['citation']
        self.store.remember({'topic':'입하','statement':'입하 확정 구현','kind':'supported','evidence':[citation]})
        p.write_text('replacement only')
        r = self.search()
        self.assertEqual(r['memory'], [])
        self.assertEqual(r['evidence'], [])
        p.unlink()
        self.assertEqual(self.search('replacement')['evidence'], [])

    def test_missing_root_does_not_return_stale(self):
        p = self.file(); self.connect(); p.unlink(); self.project.rmdir()
        self.assertEqual(self.search()['evidence'], [])
        self.assertEqual(self.store.status()['sources'][0]['status'], 'unavailable')

    def test_external_pending_capture_update(self):
        src = self.store.connect({'kind':'external','target':'https://example.atlassian.net/wiki/pages/1'})
        self.assertEqual(src['status'],'pending')
        self.assertEqual(self.search()['evidence'],[])
        self.store.capture({'source':src['id'],'text':'입하 확정은 receipt 테이블','reader':'authorized.page.read'})
        r = self.search()
        self.assertEqual(r['evidence'][0]['status'],'snapshot')
        self.assertIn('snapshot_only', self.store.status()['sources'][0]['details']['freshness'])
        self.store.capture({'source':src['id'],'text':'출하 완료','reader':'authorized.page.read'})
        self.assertEqual(self.search('receipt')['evidence'],[])

    def test_capture_cannot_mutate_local_source(self):
        self.file(); src=self.connect()
        with self.assertRaises(d.Problem):
            self.store.capture({'source':src['id'],'text':'replacement','reader':'test'})

    def test_unsafe_urls(self):
        for url in ['http://example.com','https://user:password@example.com/page','https://example.com/?token=foo','file:///etc/passwd','db://user:pass@host/db']:
            with self.subTest(url=url), self.assertRaises(d.Problem):
                self.store.connect({'kind':'external','target':url})

    def test_sensitive_and_generated_exclusions(self):
        self.file()
        for name in ['.env','credentials.json','nested/build/report.txt','node_modules/a.txt','.git/config.txt','key.pem']:
            self.file(name,'DO_NOT_INDEX')
        self.file('leaked.txt', '-----BEGIN PRIVATE KEY-----')
        self.connect()
        self.assertEqual(self.search('DO_NOT_INDEX')['evidence'],[])
        self.assertEqual(self.search('PRIVATE KEY')['evidence'],[])
        self.assertTrue(self.search()['evidence'])

    def test_symlinks(self):
        outside = self.base/'secret.txt'; outside.write_text('OUTSIDE_MATERIAL')
        (self.project/'linked.txt').symlink_to(outside)
        (self.project/'loop').symlink_to(self.project, target_is_directory=True)
        self.file(); self.connect()
        self.assertEqual(self.search('OUTSIDE_MATERIAL')['evidence'],[])
        with self.assertRaises(d.Problem): self.store.connect({'target':'linked.txt'})

    def test_large_file_partial_and_chunking(self):
        self.file('large.txt','x'*(d.MAX_FILE+1))
        self.file('long.txt','입하 '*3000)
        self.assertEqual(self.connect()['status'],'partial')
        self.assertTrue(all(len(e['body'])<=4000 for e in self.search()['evidence']))

    def test_unsupported_binary_and_encoding(self):
        (self.project/'binary.txt').write_bytes(b'abc\0xyz')
        (self.project/'old.txt').write_bytes(b'\xff\xfe')
        (self.project/'scan.pdf').write_bytes(b'not parsed')
        src=self.connect()
        self.assertEqual(src['details']['indexed_documents'],0)
        self.assertEqual(src['details']['excluded']['unsupported_encoding'],1)

    def test_repeat_connect_deduplicates(self):
        self.file(); a=self.connect(); b=self.connect()
        self.assertEqual(a['id'], b['id'])
        self.assertEqual(len(self.store.status()['sources']),1)

    def test_user_correction_supersedes_without_faking_verification(self):
        self.store.remember({'topic':'입하','statement':'discard paraphrase','user_statement':'입하 방식은 A','kind':'user_correction'})
        self.store.remember({'topic':'입하','statement':'discard','user_statement':'입하 방식은 B','kind':'user_correction'})
        memory=self.search()['memory']
        self.assertEqual(len(memory),1)
        self.assertEqual(memory[0]['statement'],'입하 방식은 B')
        self.assertEqual(memory[0]['kind'],'user_correction')
        self.assertEqual(self.store.db.execute('SELECT count(*) FROM memories').fetchone()[0],2)

    def test_memory_requires_valid_citation(self):
        for refs in [[],['missing:fake']]:
            with self.assertRaises(d.Problem):
                self.store.remember({'topic':'입하','statement':'claim','kind':'supported','evidence':refs})

    def test_disconnect_leaves_original_and_invalidates_memory(self):
        p=self.file(); s=self.connect(); c=self.search()['evidence'][0]['citation']
        self.store.remember({'topic':'입하','statement':'입하','kind':'supported','evidence':[c]})
        self.store.forget({'source':s['id']})
        self.assertTrue(p.exists())
        self.assertEqual(self.search()['memory'],[])
        self.assertEqual(self.search()['evidence'],[])

    def test_sqlite_read_only(self):
        path=self.project/'business.sqlite'
        con=sqlite3.connect(path)
        con.executescript('CREATE TABLE receipts(id INTEGER, state TEXT); INSERT INTO receipts VALUES(1,"received"); CREATE VIEW v AS SELECT * FROM receipts;')
        con.commit(); con.close(); before=path.read_bytes()
        src=self.store.connect({'kind':'sqlite','target':str(path)})
        result=self.store.rows({'source':src['id'],'table':'receipts'})
        self.assertEqual(result['rows'],[[1,'received']])
        self.assertFalse(result['stored'])
        for table in ['v','receipts; DELETE FROM receipts','sqlite_master']:
            with self.assertRaises(d.Problem): self.store.rows({'source':src['id'],'table':table})
        self.assertEqual(path.read_bytes(),before)
        self.assertNotIn('received',self.store.db.execute('SELECT body FROM documents').fetchone()[0])

    def test_state_permissions(self):
        self.assertEqual(self.state.stat().st_mode & 0o777,0o700)
        self.assertEqual((self.state/'knowledge.sqlite3').stat().st_mode & 0o777,0o600)

    def test_malformed_cli_no_traceback(self):
        p=ROOT/'skill-runtime/dougle.py'
        for payload in ['[]','{bad','{"query":null}']:
            r=subprocess.run([sys.executable,str(p),'search','--workspace',str(self.project)],input=payload,text=True,capture_output=True,env={**os.environ,'DOUGLE_HOME':str(self.state)})
            self.assertEqual(r.returncode,2)
            self.assertFalse(json.loads(r.stdout)['ok'])
            self.assertNotIn('Traceback',r.stderr)

    def test_installer_preserves_modified_skill(self):
        home=self.base/'user'
        command=[sys.executable,str(ROOT/'scripts/install-skills.py'),'--home',str(home)]
        subprocess.run(command,check=True,capture_output=True)
        target=home/'.agents/skills/dougle-connect/SKILL.md'
        target.write_text('custom')
        r=subprocess.run(command,capture_output=True)
        self.assertEqual(r.returncode,2)
        self.assertEqual(target.read_text(),'custom')

    def test_install_update_uninstall_preserves_state(self):
        self.file(); self.connect()
        home=self.base/'user'; command=[sys.executable,str(ROOT/'scripts/install-skills.py'),'--home',str(home)]
        for args in [[],[],['--uninstall']]:
            subprocess.run(command+args,check=True,capture_output=True)
        self.assertFalse((home/'.agents/skills/dougle-ask').exists())
        self.assertTrue(self.search()['evidence'])

    def test_paths_are_not_commands(self):
        p=self.file('$(touch PWNED) 한글.java')
        self.connect()
        self.assertTrue(self.search()['evidence'])
        self.assertFalse((self.project/'PWNED').exists())
        self.assertTrue(p.exists())

    def test_shared_store_parallel_cli(self):
        self.file(); self.connect()
        command=[sys.executable,str(ROOT/'skill-runtime/dougle.py'),'search','--workspace',str(self.project)]
        def call(_):
            return subprocess.run(command,input='{"query":"입하"}',text=True,capture_output=True,env={**os.environ,'DOUGLE_HOME':str(self.state)})
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(call,range(8)))
        for r in results:
            self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            self.assertTrue(json.loads(r.stdout)['result']['evidence'])

    def test_changed_between_search_and_remember(self):
        p=self.file(); self.connect(); c=self.search()['evidence'][0]['citation']
        p.write_text('new content')
        with self.assertRaises(d.Problem):
            self.store.remember({'topic':'receipt','statement':'old claim','kind':'supported','evidence':[c]})

    def test_failed_capture_preserves_last_snapshot(self):
        src=self.store.connect({'kind':'external','target':'https://example.com/page'})
        self.store.capture({'source':src['id'],'text':'previous evidence','reader':'tool'})
        with self.assertRaises(d.Problem):
            self.store.capture({'source':src['id'],'text':'','reader':'tool'})
        self.assertTrue(self.search('previous')['evidence'])
        self.assertEqual(self.store.status()['sources'][0]['status'],'snapshot')

    def test_secret_capture_and_memory_rejected(self):
        src=self.store.connect({'kind':'external','target':'https://example.com/page'})
        with self.assertRaises(d.Problem):
            self.store.capture({'source':src['id'],'text':'-----BEGIN PRIVATE KEY-----','reader':'tool'})
        with self.assertRaises(d.Problem):
            self.store.remember({'topic':'key','statement':'key','user_statement':'-----BEGIN PRIVATE KEY-----','kind':'user_correction'})

    def test_cross_workspace_citation_rejected(self):
        self.file(); self.connect(); c=self.search()['evidence'][0]['citation']
        other=self.base/'other'; other.mkdir(); s=d.Store(other,self.state)
        try:
            with self.assertRaises(d.Problem):
                s.remember({'topic':'receipt','statement':'claim','kind':'supported','evidence':[c]})
        finally: s.db.close()

    def test_non_regular_file_is_skipped(self):
        os.mkfifo(self.project/'pipe.txt')
        src=self.connect()
        self.assertEqual(src['details']['excluded']['not_regular_file'],1)

    def test_state_symlink_rejected(self):
        link=self.base/'linked-state'; link.symlink_to(self.state,target_is_directory=True)
        with self.assertRaises(d.Problem): d.Store(self.project,link)

    def test_installer_unknown_existing_directory_untouched(self):
        home=self.base/'user'; existing=home/'.claude/skills/dougle-ask'
        existing.mkdir(parents=True); (existing/'SKILL.md').write_text('user skill')
        r=subprocess.run([sys.executable,str(ROOT/'scripts/install-skills.py'),'--home',str(home)],capture_output=True)
        self.assertEqual(r.returncode,2)
        self.assertFalse((home/'.agents/skills/dougle-connect').exists())
        self.assertEqual((existing/'SKILL.md').read_text(),'user skill')

    def test_command_input_limits(self):
        for payload in [{'query':'x','limit':1000},{'query':'x','alternatives':'bad'},{'query':'x','alternatives':['x']*9}]:
            with self.assertRaises(d.Problem): self.store.search(payload)

    def test_sqlite_large_cells_are_bounded(self):
        path=self.project/'large.sqlite'; con=sqlite3.connect(path)
        con.execute('CREATE TABLE data(t TEXT, b BLOB)')
        con.execute('INSERT INTO data VALUES(?,?)',('x'*100000,b'x'*100000))
        con.commit(); con.close()
        src=self.store.connect({'kind':'sqlite','target':str(path)})
        r=self.store.rows({'source':src['id'],'table':'data'})
        self.assertEqual(len(r['rows'][0][0]),4000)
        self.assertEqual(r['rows'][0][1],'[binary]')
        self.assertEqual(r['text_cell_limit'],4000)

if __name__=='__main__': unittest.main()
