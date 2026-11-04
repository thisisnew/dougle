#!/usr/bin/env python3
"""Exercise the actual distribution in a new HOME without any model calls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile
root=Path(__file__).resolve().parents[1]
archive=root/'release/Dougle_Skills_0.2.0.zip'
checks=[]
with tempfile.TemporaryDirectory(prefix='dougle-package-') as temp:
    base=Path(temp)
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            assert not name.startswith('/') and '..' not in Path(name).parts
            assert not any(part in {'backend','frontend','desktop','.env','knowledge.sqlite3','node_modules'} for part in Path(name).parts)
        z.extractall(base)
    package=base/'Dougle_Skills_0.2.0'
    home=base/'home'; project=base/'workspace with spaces'; project.mkdir()
    source=project/'Receiving.java'; source.write_text('class Receiving { void confirmInbound() {} } // 입하 확정')
    before=source.read_bytes()
    subprocess.run([sys.executable,str(package/'scripts/install-skills.py'),'--home',str(home)],check=True,capture_output=True)
    checks.append('archive_fresh_home_install_both_hosts')
    env={**os.environ,'DOUGLE_HOME':str(home/'state')}
    def run(host,skill,command,data):
        helper=home/host/'skills'/skill/'scripts/dougle.py'
        result=subprocess.run([sys.executable,str(helper),command,'--workspace',str(project)],input=json.dumps(data),text=True,capture_output=True,env=env)
        assert result.returncode==0,result.stdout+result.stderr
        return json.loads(result.stdout)['result']
    source_id=run('.claude','dougle-connect','connect',{'target':'.'})['id']
    evidence=run('.agents','dougle-ask','search',{'query':'입하 확정'})['evidence'][0]
    checks.append('claude_path_connect_codex_path_search')
    run('.claude','dougle-ask','remember',{'topic':'입하 확정','statement':'입하 확정 구현','kind':'supported','evidence':[evidence['citation']]})
    assert run('.agents','dougle-ask','search',{'query':'입하'})['memory']
    checks.append('shared_grounded_memory_across_processes')
    assert source.read_bytes()==before
    checks.append('original_source_bytes_unchanged')
    source.write_text('new implementation')
    result=run('.claude','dougle-ask','search',{'query':'입하'})
    assert not result['memory'] and not result['evidence']
    checks.append('changed_source_invalidates_old_evidence_and_memory')
    subprocess.run([sys.executable,str(package/'scripts/install-skills.py'),'--home',str(home)],check=True,capture_output=True)
    assert run('.agents','dougle-ask','status',{})['sources'][0]['id']==source_id
    checks.append('reinstall_preserves_connections')
    subprocess.run([sys.executable,str(package/'scripts/install-skills.py'),'--home',str(home),'--uninstall'],check=True,capture_output=True)
    assert (home/'state/knowledge.sqlite3').exists()
    checks.append('uninstall_preserves_state')
report={'version':'0.2.0','status':'PASS','checks':checks,'archiveBytes':archive.stat().st_size,'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'externalModelCalls':0,'limits':['Host AI discovery and model behavior were not exercised; installed entrypoint processes were exercised.','Actual company SSO/Confluence/MySQL and Apple Silicon hardware were not exercised.']}
(root/'docs/skills/package-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
