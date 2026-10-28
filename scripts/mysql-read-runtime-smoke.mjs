import { spawn, execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm, readdir } from 'node:fs/promises';
import { dirname, resolve, join, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { createServer, connect } from 'node:net';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const binary = process.argv[2];
if (!binary?.startsWith('/')) throw new Error('Usage: node scripts/mysql-read-runtime-smoke.mjs /absolute/path/to/mysqld');
const root = await mkdtemp('/tmp/dougle-mysql-');
const children = new Set();
const environment = { PATH: '/usr/bin:/bin', HOME: root, TMPDIR: root, LANG: 'C' };
let cleanupPromise;
function launch(command, args, input = '') {
  const child = spawn(command, args, { detached: true, env: environment, cwd: root, stdio: ['pipe', 'pipe', 'pipe'] });
  children.add(child);
  let output = '';
  child.stdout.on('data', data => { output = (output + data).slice(-16384); });
  child.stderr.on('data', data => {
    if (['java', 'javac'].includes(command.split('/').at(-1))) output = (output + data).slice(-16384);
  });
  child.stdin.on('error', () => {});
  child.stdin.end(input);
  child.done = new Promise(resolveDone => {
    child.once('error', () => { children.delete(child); resolveDone(-1); });
    child.once('close', code => { children.delete(child); resolveDone(code ?? -1); });
  });
  child.output = () => output;
  return child;
}
function signal(child, name) {
  if (children.has(child) && child.pid) {
    try { process.kill(-child.pid, name); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  }
}
async function stop(child) {
  signal(child, 'SIGTERM');
  const timer = setTimeout(() => signal(child, 'SIGKILL'), 5000);
  await child.done;
  clearTimeout(timer);
}
async function run(command, args, timeout, input) {
  const child = launch(command, args, input);
  let expired = false;
  const timer = setTimeout(() => { expired = true; void stop(child); }, timeout);
  const code = await child.done;
  clearTimeout(timer);
  if (expired || code !== 0) throw new Error(`${command.split('/').at(-1)} ${expired ? 'deadline exceeded' : `exit ${code}`} ${['java', 'javac'].includes(command.split('/').at(-1)) ? child.output() : '(server diagnostics suppressed to protect generated credentials)'}`);
  return child.output();
}
function cleanup() {
  cleanupPromise ??= (async () => {
    await Promise.all([...children].map(stop));
    await rm(root, { recursive: true, force: true });
  })();
  return cleanupPromise;
}
for (const name of ['SIGTERM', 'SIGINT']) process.once(name, () => { void cleanup().then(() => process.exit(130)); });
const overall = setTimeout(() => { void cleanup().then(() => process.exit(124)); }, 300000);
async function jars(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await jars(path));
    else if (entry.name.endsWith('.jar')) result.push(path);
  }
  return result;
}
try {
  const version = (await run(binary, ['--no-defaults', '--version'], 60000)).trim();
  console.log(version);
  const javaHome = process.env.JAVA_HOME || execFileSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8', timeout: 5000 }).trim();
  const classpath = [...await jars(join(repo, '.m2/com/mysql/mysql-connector-j')), ...await jars(join(repo, '.m2/org/springframework/spring-context'))].join(delimiter);
  await mkdir(join(root, 'classes'));
  await run(join(javaHome, 'bin/javac'), ['-cp', classpath, '-d', join(root, 'classes'),
    join(repo, 'backend/src/main/java/dev/dougle/library/application/LibraryException.java'),
    join(repo, 'backend/src/main/java/dev/dougle/sources/adapter/CollectionLimits.java'),
    join(repo, 'backend/src/main/java/dev/dougle/sources/domain/CollectedSnapshot.java'),
    join(repo, 'backend/src/main/java/dev/dougle/sources/domain/HttpValidators.java'),
    join(repo, 'backend/src/main/java/dev/dougle/plugins/adapter/MysqlClient.java'),
    join(repo, 'backend/src/main/java/dev/dougle/plugins/adapter/MysqlReadClient.java'),
    join(repo, 'scripts/fixtures/MysqlReadRuntimeSmoke.java')], 30000);
  const base = dirname(dirname(binary));
  const common = ['--no-defaults', `--basedir=${base}`, `--datadir=${root}/data`, `--socket=${root}/mysql.sock`, `--pid-file=${root}/mysql.pid`, `--log-error=${root}/server.log`, `--plugin-dir=${base}/lib/plugin`];
  console.log('Initializing private synthetic datadir (120s deadline)');
  await run(binary, [...common, '--initialize'], 120000);
  const password = randomBytes(24).toString('hex');
  const init = [
    "ALTER USER 'root'@'localhost' ACCOUNT LOCK;",
    'CREATE DATABASE fixture;',
    `CREATE USER 'fixture_reader'@'127.0.0.1' IDENTIFIED BY '${password}';`,
    'CREATE TABLE fixture.items (id INT PRIMARY KEY, label TEXT, binary_value BLOB, derived INT GENERATED ALWAYS AS (id + 1) STORED);',
    "INSERT INTO fixture.items(id,label) VALUES (1,'synthetic healthy'),(2,REPEAT('x',4096)),(3,'synthetic third');",
    'CREATE VIEW fixture.item_view AS SELECT id FROM fixture.items;',
    'CREATE TABLE fixture.large_items (id INT PRIMARY KEY, body LONGTEXT CHARACTER SET utf8mb4);',
    "INSERT INTO fixture.large_items VALUES (1,REPEAT('x',8388608)),(2,REPEAT(CONVERT(0xF09F9880 USING utf8mb4),2097152)),(3,CONCAT(REPEAT('p',512),'tail')),(4,NULL),(5,REPEAT('b',256));",
    'CREATE TABLE fixture.drift_items (id INT PRIMARY KEY, body INT);',
    'INSERT INTO fixture.drift_items VALUES (1,42);',
    'CREATE TABLE fixture.scalar_items (id INT PRIMARY KEY, amount DECIMAL(12,2), day_value DATE, time_value TIME, moment DATETIME, absent INT);',
    "INSERT INTO fixture.scalar_items VALUES (1,12345.67,'2026-10-25','12:34:56','2026-10-25 12:34:56',NULL);",
    `CREATE USER 'fixture_ddl'@'127.0.0.1' IDENTIFIED BY '${password}';`,
    "GRANT ALTER, UPDATE, SELECT ON fixture.drift_items TO 'fixture_ddl'@'127.0.0.1';",
    "GRANT SELECT ON fixture.* TO 'fixture_reader'@'127.0.0.1';",
  ].join('\n');
  await writeFile(join(root, 'init.sql'), init, { mode: 0o600 });
  const reservation = createServer();
  await new Promise((resolveListen, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', resolveListen); });
  const port = reservation.address().port;
  await new Promise(resolveClose => reservation.close(resolveClose));
  const server = launch(binary, [...common, `--port=${port}`, '--bind-address=127.0.0.1', '--mysqlx=OFF', '--skip-log-bin', '--skip-name-resolve', '--local-infile=OFF', `--init-file=${root}/init.sql`]);
  const deadline = Date.now() + 60000;
  let ready = false;
  while (!ready && Date.now() < deadline && children.has(server)) {
    ready = await new Promise(resolveReady => {
      const socket = connect({ host: '127.0.0.1', port });
      socket.setTimeout(500);
      socket.once('data', () => { socket.destroy(); resolveReady(true); });
      socket.once('error', () => resolveReady(false));
      socket.once('timeout', () => { socket.destroy(); resolveReady(false); });
    });
    if (!ready) await new Promise(resolveDelay => setTimeout(resolveDelay, 200));
  }
  if (!ready) throw new Error('Private MySQL did not become ready within 60s');
  console.log(await run(join(javaHome, 'bin/java'), ['-cp', [join(root, 'classes'), classpath].join(delimiter), 'dev.dougle.plugins.adapter.MysqlReadRuntimeSmoke'], 45000, `${port}\n${password}\n`));
} finally {
  clearTimeout(overall);
  await cleanup();
  console.log('Owned processes stopped; private datadir and generated credentials removed.');
}
