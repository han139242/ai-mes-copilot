import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(import.meta.url);
const repo = path.resolve(path.dirname(script), '..');
// argv: [node, script, action, service, configPath, jar, envFile]
const workerRequest = process.argv[2] === 'worker';
if (workerRequest && process.argv[6]) {
  // A supervisor is launched outside the caller's process tree, so its environment
  // arrives as a snapshot file instead of inherited variables.
  const snapshotFile = process.argv[6];
  try {
    const snapshot = JSON.parse(fs.readFileSync(snapshotFile, 'utf8').replace(/^\uFEFF/, ''));
    for (const [key, value] of Object.entries(snapshot)) {
      if (typeof value === 'string') {
        process.env[key] = value;
      }
    }
  } finally {
    fs.rmSync(snapshotFile, { force: true });
  }
}
const runtime = path.resolve(process.env.MES_RUNTIME_DIRECTORY || path.join(repo, 'tmp', 'local-runtime'));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const ps = (code) =>
  spawnSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      '[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); ' + code,
    ],
    { encoding: 'utf8', windowsHide: true },
  );
const read = (file) => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const stateFile = (name) => path.join(runtime, `${name}.json`);
const stoppedFile = (name) => path.join(runtime, `${name}.stop`);
const readState = (name) => (fs.existsSync(stateFile(name)) ? read(stateFile(name)) : null);
const writeState = (name, value) => {
  const temporary = stateFile(name) + '.' + crypto.randomUUID() + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2));
  // Windows can briefly refuse the replace while a reader or indexer holds the target.
  for (let attempt = 0; ; attempt++) {
    try {
      fs.renameSync(temporary, stateFile(name));
      return;
    } catch (error) {
      if (!['EPERM', 'EBUSY', 'EACCES'].includes(error.code) || attempt >= 20) {
        fs.rmSync(temporary, { force: true });
        throw error;
      }
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
    }
  }
};
const normalized = (value) => path.resolve(value).replaceAll('\\', '/').toLowerCase();

export function sameProcess(saved, current) {
  return Boolean(
    saved && current && saved.pid === current.pid && saved.started === current.started && saved.hash === current.hash,
  );
}

export function mayRestart(attempt, stopping, limit = 2) {
  return !stopping && attempt < limit;
}

export function instanceFingerprint(name, config) {
  return crypto
    .createHash('sha256')
    .update(
      JSON.stringify({
        service: name,
        port: config[name].port,
        database:
          name === 'frontend'
            ? null
            : {
                port: config.database.port,
                name: config.database.name,
                data: normalized(fs.realpathSync(config.database.dataDirectory)),
              },
        proxy: name === 'frontend' ? config.backend.port : null,
        cache: config.cacheNamespace || '',
        ...(name === 'backend' ? { aiEnabled: process.env.AI_ENABLED === 'true' } : {}),
      }),
    )
    .digest('hex');
}

// The state file is a claim; only live processes make it true. Reported state is derived
// from observation so a crashed service can never be shown as ready.
export function observedState(claim, seen) {
  if (seen.foreign) {
    return 'port-conflict';
  }
  if (seen.listening) {
    if (!seen.healthy) {
      return 'unresponsive';
    }
    if (!seen.supervised || !seen.registered) {
      return 'unmanaged';
    }
    return 'ready';
  }
  if (seen.supervised) {
    return claim === 'recovering' ? 'recovering' : 'starting';
  }
  if (['ready', 'starting', 'recovering'].includes(claim)) {
    return 'crashed';
  }
  return claim === 'failed' ? 'failed' : 'stopped';
}

// Windows owns this mutex: a crashed controller cannot leave a stale JSON lock.
export async function acquireControl(suffix = 'control') {
  if (!/^[a-zA-Z0-9-]+$/.test(suffix)) {
    throw Error('Invalid mutex suffix');
  }
  const key = crypto
    .createHash('sha256')
    .update(normalized(fs.realpathSync(repo)))
    .digest('hex')
    .slice(0, 20);
  const code = `$m=New-Object System.Threading.Mutex($false,'Local\\MesRuntime-${key}-${suffix}');$held=$false;try{try{$held=$m.WaitOne(0)}catch{if($_.Exception -is [System.Threading.AbandonedMutexException] -or $_.Exception.InnerException -is [System.Threading.AbandonedMutexException]){$held=$true}else{throw}};if(-not $held){[Console]::Out.WriteLine('BUSY');exit 2};[Console]::Out.WriteLine('LOCKED');[Console]::Out.Flush();[void][Console]::In.ReadLine()}finally{if($held){$m.ReleaseMutex()};$m.Dispose()}`;
  const holder = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', code], {
    windowsHide: true,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  holder.stdin.on('error', () => {});
  const exited = new Promise((resolve) => holder.once('exit', resolve));
  await new Promise((resolve, reject) => {
    let output = '';
    const timeout = setTimeout(() => {
      holder.kill();
      reject(Error('Runtime mutex initialization timed out'));
    }, 8000);
    holder.stdout.on('data', (chunk) => {
      output += chunk.toString();
      if (output.includes('LOCKED')) {
        clearTimeout(timeout);
        resolve();
      }
      if (output.includes('BUSY')) {
        clearTimeout(timeout);
        reject(Error('Another runtime operation is active; retry after it completes'));
      }
    });
    holder.once('error', () => {
      clearTimeout(timeout);
      reject(Error('Runtime mutex process could not start'));
    });
    holder.once('exit', () => {
      if (!output.includes('LOCKED')) {
        clearTimeout(timeout);
        reject(Error('Runtime control is busy or unavailable'));
      }
    });
  });
  return async () => {
    holder.stdin.end('\n');
    await exited;
  };
}

function identity(pid) {
  if (!Number.isSafeInteger(Number(pid)) || Number(pid) <= 0) {
    return null;
  }
  const result = ps(
    `$p=Get-CimInstance Win32_Process -Filter 'ProcessId=${Number(pid)}'; if($p){[pscustomobject]@{pid=$p.ProcessId;started=$p.CreationDate.ToUniversalTime().Ticks.ToString();parent=$p.ParentProcessId;command=$p.CommandLine}|ConvertTo-Json -Compress}`,
  );
  if (!result.stdout.trim()) {
    return null;
  }
  const value = JSON.parse(result.stdout.trim());
  return {
    pid: value.pid,
    started: value.started,
    parent: value.parent,
    hash: crypto
      .createHash('sha256')
      .update(value.command || '')
      .digest('hex'),
    command: value.command || '',
  };
}

function savedIdentity(value) {
  if (!value) {
    return null;
  }
  const { command, ...safe } = value;
  return safe;
}

// Every child this controller ever spawned is recorded before it is supervised. If a
// supervisor is killed, the surviving child is still provably ours, so recovery can stop
// it instead of leaving an orphan holding the port.
function ledgerFile(name) {
  return path.join(runtime, `${name}.children.jsonl`);
}

function recordOwnership(name, child) {
  if (!child) {
    return;
  }
  fs.appendFileSync(ledgerFile(name), JSON.stringify({ ...savedIdentity(child), at: new Date().toISOString() }) + '\n');
}

function ownedByThisController(name, current) {
  if (!current) {
    return false;
  }
  const file = ledgerFile(name);
  if (!fs.existsSync(file)) {
    return false;
  }
  return fs
    .readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .some((line) => {
      try {
        return sameProcess(JSON.parse(line), current);
      } catch {
        return false;
      }
    });
}

function listener(port) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw Error('Invalid service port');
  }
  const result = ps(
    `Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique`,
  );
  const pids = [...new Set(result.stdout.trim().split(/\s+/).filter(Boolean).map(Number))];
  if (pids.length > 1) {
    throw Error(`Port ${port} has multiple owners; refusing to stop any process`);
  }
  return pids[0] || null;
}

function mysql(config, sql, admin = false) {
  const user = admin ? process.env.DB_ADMIN_USERNAME : process.env.DB_USERNAME;
  const password = admin ? process.env.DB_ADMIN_PASSWORD : process.env.DB_PASSWORD;
  if (!user || password === undefined) {
    throw Error(
      admin
        ? 'Database shutdown requires DB_ADMIN_USERNAME/DB_ADMIN_PASSWORD; database was left running'
        : 'Database credentials are missing',
    );
  }
  const args = [
    '--protocol=TCP',
    '--host=127.0.0.1',
    `--port=${config.database.port}`,
    `--user=${user}`,
    '--connect-timeout=4',
    '--batch',
    '--skip-column-names',
    `--database=${config.database.name}`,
    `--execute=${sql}`,
  ];
  const result = spawnSync(config.database.client, args, {
    encoding: 'utf8',
    timeout: 10000,
    windowsHide: true,
    env: { ...process.env, MYSQL_PWD: password },
  });
  if (result.status !== 0) {
    throw Error('Database connection/query failed; check the selected instance and credentials');
  }
  return result.stdout.trim();
}

function databaseReady(config) {
  const name = config.database.name;
  if (!/^[a-zA-Z0-9_]+$/.test(name)) {
    throw Error('Invalid database name');
  }
  const required = config.database.requiredTables || ['sys_user', 'wms_inventory', 'mfg_work_order'];
  if (!Array.isArray(required) || !required.length || required.some((table) => !/^[a-zA-Z0-9_]+$/.test(table))) {
    throw Error('Invalid required database table list');
  }
  const tableList = required.map((table) => `'${table}'`).join(',');
  const result = mysql(
    config,
    `SELECT DATABASE(),@@port,@@datadir; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (${tableList});`,
  ).split(/\r?\n/);
  const [db, port, data] = result[0].split('\t');
  if (db !== name || Number(port) !== config.database.port || Number(result[1]) !== required.length) {
    throw Error('Business database identity/schema check failed');
  }
  if (normalized(fs.realpathSync(data)) !== normalized(fs.realpathSync(config.database.dataDirectory))) {
    throw Error('Database data directory differs from the configured business instance');
  }
  return true;
}

async function httpReady(name, config) {
  try {
    if (name === 'database') {
      return databaseReady(config);
    }
    if (name === 'backend') {
      databaseReady(config);
    }
    const port = config[name].port;
    const response = await fetch(`http://127.0.0.1:${port}${name === 'backend' ? '/api/auth/info' : '/'}`, {
      signal: AbortSignal.timeout(3000),
    });
    // A 401 here is liveness, not business readiness: the database is checked separately.
    return name === 'backend' ? [200, 401].includes(response.status) : response.status === 200;
  } catch {
    return false;
  }
}

function event(name, detail) {
  fs.appendFileSync(
    path.join(runtime, 'events.jsonl'),
    JSON.stringify({ time: new Date().toISOString(), service: name, ...detail }) + '\n',
  );
}

function expectedOwner(name, process, config) {
  const command = process?.command.replaceAll('\\', '/').toLowerCase() || '';
  if (name === 'database') {
    return command.includes(normalized(config.database.config));
  }
  if (name === 'backend') {
    return command.includes(normalized(path.join(runtime, 'builds'))) && command.includes('.jar');
  }
  return command.includes(normalized(path.join(repo, 'workspace/vscode/ems-forword'))) && command.includes('vite');
}

function exitReason(result) {
  if (!result) {
    return 'no exit recorded';
  }
  if (result.error) {
    return `could not start (${result.error})`;
  }
  if (result.signal) {
    return `terminated by ${result.signal}`;
  }
  return `exited with code ${result.code}`;
}

async function describe(name, config) {
  const saved = readState(name);
  const claim = saved?.status || 'unmanaged';
  const supervisor = saved?.worker ? identity(saved.worker.pid) : null;
  const supervised = sameProcess(saved?.worker, supervisor);
  const pid = listener(config[name].port);
  const owner = pid ? identity(pid) : null;
  const registered = Boolean(pid && sameProcess(saved?.child, owner));
  const healthy = pid ? await httpReady(name, config) : false;
  const foreign = Boolean(pid && owner && !expectedOwner(name, owner, config));
  const managed = supervised && registered;
  let state = observedState(claim, { listening: Boolean(pid), healthy, supervised, registered, foreign });
  // The database is a persistent instance that may be started by hand; a healthy, correctly
  // identified MySQL is usable even when this controller did not launch it.
  if (name === 'database' && pid && healthy && !foreign) {
    state = 'ready';
  }
  return {
    service: name,
    port: config[name].port,
    listeningPid: pid,
    healthy,
    state,
    managed,
    claim,
    supervisorPid: supervised ? supervisor.pid : null,
    reason: state === 'crashed' ? exitReason(saved?.lastExit) : saved?.reason || null,
    lastExit: saved?.lastExit || null,
  };
}

async function buildBackend(config) {
  databaseReady(config);
  const disk = fs.statfsSync(runtime);
  if (disk.bavail * disk.bsize < 512 * 1024 * 1024) {
    throw Error('Less than 512 MiB is available for a safe staged build; existing service was not stopped');
  }
  const build = path.join(runtime, 'builds', `${Date.now()}-${crypto.randomUUID().slice(0, 8)}`);
  fs.mkdirSync(build, { recursive: true });
  const source = path.join(repo, 'workspace/idea/ems');
  for (const name of ['src', '.mvn', 'pom.xml', 'mvnw', 'mvnw.cmd']) {
    fs.cpSync(path.join(source, name), path.join(build, name), { recursive: true });
  }
  const log = path.join(build, 'build.log');
  const fd = fs.openSync(log, 'a');
  // Packaging is isolated from the live JAR and other developers' target directory.
  const tests = config.backend.verificationTests;
  if (tests && (!Array.isArray(tests) || !tests.length || tests.some((name) => !/^[A-Za-z0-9_]+$/.test(name)))) {
    fs.closeSync(fd);
    throw Error('Invalid verification test list; existing service was not stopped');
  }
  // Default packaging does not claim test coverage; configured focused tests run before switching.
  const command = tests ? `mvnw.cmd -B -Dtest=${tests.join(',')} package` : 'mvnw.cmd -B -DskipTests package';
  const result = spawnSync('cmd.exe', ['/d', '/s', '/c', command], {
    cwd: build,
    env: process.env,
    stdio: ['ignore', fd, fd],
    windowsHide: true,
  });
  fs.closeSync(fd);
  if (result.status !== 0) {
    throw Error(`Backend build failed; existing service was not stopped. Log: ${log}`);
  }
  const jar = fs.readdirSync(path.join(build, 'target')).find((name) => name.endsWith('.jar'));
  if (!jar) {
    throw Error('Build did not produce an executable JAR; existing service was not stopped');
  }
  const artifact = path.join(build, 'target', jar);
  await smokeBackend(config, artifact, build);
  return artifact;
}

async function smokeBackend(config, jar, build) {
  const port = config.backend.smokePort || 18089;
  if (listener(port)) {
    throw Error(`Startup verification port ${port} is occupied; existing service was not stopped`);
  }
  const log = fs.openSync(path.join(build, 'startup-check.log'), 'a');
  const child = spawn(
    config.backend.java || 'java.exe',
    [
      '-jar',
      jar,
      `--server.port=${port}`,
      '--mes.notification.scheduling-enabled=false',
      '--mes.alert.scheduling-enabled=false',
      '--mes.automation.scheduling-enabled=false',
      '--mes.anomaly.scheduling-enabled=false',
      '--mes.task.scheduling-enabled=false',
    ],
    { cwd: repo, env: process.env, windowsHide: true, stdio: ['ignore', log, log] },
  );
  fs.closeSync(log);
  const exited = new Promise((resolve) => {
    child.once('exit', (code) => resolve(code));
    child.once('error', () => resolve(-1));
  });
  let ready = false;
  try {
    for (let i = 0; i < 60 && child.exitCode === null; i++) {
      if (await httpReady('backend', { ...config, backend: { ...config.backend, port } })) {
        if (listener(port) !== child.pid) {
          throw Error('Startup verification found a different port owner');
        }
        ready = true;
        break;
      }
      await sleep(1000);
    }
  } finally {
    if (child.exitCode === null && child.pid) {
      child.kill('SIGTERM');
    }
    const terminated = await Promise.race([exited.then(() => true), sleep(8000).then(() => false)]);
    if (!terminated) {
      throw Error('Startup verification process did not exit; existing service was not stopped');
    }
  }
  if (!ready) {
    throw Error(
      `Backend startup verification failed; existing service was not stopped. Log: ${path.join(build, 'startup-check.log')}`,
    );
  }
  event('backend', { kind: 'startup-check-passed', artifact: jar });
}

const quoteArg = (value) => (/[\s"]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value);
const quotePsLiteral = (value) => `'${value.replaceAll("'", "''")}'`;

// A supervisor started from this controller inherits its Windows job object and dies with
// the terminal that launched it. WMI creates the process outside that job, and since WMI
// does not inherit this process's environment, the supervisor reads a snapshot instead.
function launchDetached(name, configPath, jar) {
  const environmentFile = path.join(runtime, `${name}.worker.env.json`);
  const snapshot = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (typeof value === 'string') {
      snapshot[key] = value;
    }
  }
  snapshot.MES_RUNTIME_DIRECTORY = runtime;
  fs.writeFileSync(environmentFile, JSON.stringify(snapshot));
  const out = path.join(runtime, `${name}.supervisor.log`);
  const launcher = path.join(runtime, `${name}.launch.ps1`);
  // Start-Process takes the executable separately, so the argument list holds only its arguments.
  const argumentLine = [
    quoteArg(script),
    'worker',
    name,
    quoteArg(configPath),
    quoteArg(jar || '-'),
    quoteArg(environmentFile),
  ].join(' ');
  // Windows PowerShell 5.1 reads a script without a BOM as ANSI, which corrupts the
  // non-ASCII project path; the BOM keeps the supervisor launchable from any console.
  fs.writeFileSync(
    launcher,
    '\uFEFF' +
      [
        "$ErrorActionPreference = 'Stop'",
        `$arguments = ${quotePsLiteral(argumentLine)}`,
        `$process = Start-Process -FilePath ${quotePsLiteral(process.execPath)} -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput ${quotePsLiteral(out)} -RedirectStandardError ${quotePsLiteral(path.join(runtime, `${name}.supervisor.err.log`))}`,
        '[Console]::Out.WriteLine($process.Id)',
        '',
      ].join('\r\n'),
    'utf8',
  );
  const powershell = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  const launched = ps(
    `$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = ${quotePsLiteral(`${quoteArg(powershell)} -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ${quoteArg(launcher)}`)}; CurrentDirectory = ${quotePsLiteral(repo)} }; if ($result.ReturnValue -ne 0) { Write-Output ('LAUNCH-FAILED ' + $result.ReturnValue) } else { Write-Output ('LAUNCHED ' + $result.ProcessId) }`,
  );
  const output = launched.stdout.trim();
  if (!output.startsWith('LAUNCHED')) {
    throw Error(`${name}: detached launch failed (${output || launched.stderr.trim() || 'no output'})`);
  }
  event(name, { kind: 'launched-detached', pid: Number(output.split(/\s+/)[1]), jar: jar || null });
}

async function worker(name, config, jar) {
  let attempt = 0;
  const workerId = savedIdentity(identity(process.pid));
  const instance = instanceFingerprint(name, config);
  event(name, { kind: 'supervisor-up', pid: process.pid });
  while (!fs.existsSync(stoppedFile(name))) {
    const stdout = fs.openSync(path.join(runtime, `${name}.out.log`), 'a');
    const stderr = fs.openSync(path.join(runtime, `${name}.err.log`), 'a');
    const command =
      name === 'backend'
        ? config.backend.java || 'java.exe'
        : name === 'frontend'
          ? process.execPath
          : config.database.server;
    const args =
      name === 'backend'
        ? ['-jar', jar]
        : name === 'frontend'
          ? [
              path.join(repo, 'workspace/vscode/ems-forword/node_modules/vite/bin/vite.js'),
              '--host',
              '127.0.0.1',
              '--port',
              String(config.frontend.port),
              '--strictPort',
            ]
          : [`--defaults-file=${config.database.config}`];
    const child = spawn(command, args, {
      cwd: name === 'frontend' ? path.join(repo, 'workspace/vscode/ems-forword') : repo,
      env: process.env,
      windowsHide: true,
      stdio: ['ignore', stdout, stderr],
    });
    fs.closeSync(stdout);
    fs.closeSync(stderr);
    // Register handlers before querying identity: an early startup failure must not be missed.
    const exited = new Promise((resolve) => {
      child.once('error', (error) => resolve({ code: null, error: error.code }));
      child.once('exit', (code, signal) => resolve({ code, signal }));
    });
    const childId = child.pid ? savedIdentity(identity(child.pid)) : null;
    recordOwnership(name, childId);
    writeState(name, {
      name,
      worker: workerId,
      child: childId,
      jar,
      attempt,
      instance,
      status: 'starting',
      startedAt: new Date().toISOString(),
    });
    event(name, { kind: 'started', pid: child.pid, attempt });
    const stopCheck = setInterval(() => {
      if (fs.existsSync(stoppedFile(name)) && child.exitCode === null && child.pid) {
        child.kill('SIGTERM');
      }
    }, 250);
    let childExited = false;
    exited.then(() => {
      childExited = true;
    });
    const readiness = (async () => {
      const deadline = Date.now() + 60000;
      while (!childExited && Date.now() < deadline) {
        if (await httpReady(name, config)) {
          if (!childExited && listener(config[name].port) === child.pid) {
            const current = readState(name);
            if (sameProcess(current?.worker, workerId) && sameProcess(current?.child, childId)) {
              writeState(name, { ...current, status: 'ready', readyAt: new Date().toISOString() });
            }
          }
          break;
        }
        await sleep(1000);
      }
    })();
    const result = await exited;
    clearInterval(stopCheck);
    await readiness;
    const stopping = fs.existsSync(stoppedFile(name));
    // A deliberate stop is not a failure, so it must not be recorded as an exit code.
    const reason = stopping ? 'stopped by owner' : exitReason(result);
    event(name, { kind: 'exited', ...result, reason, attempt });
    const recovering = mayRestart(attempt, stopping, name === 'database' ? 0 : 2);
    writeState(name, {
      name,
      worker: workerId,
      child: null,
      jar,
      attempt,
      instance,
      status: stopping ? 'stopped' : recovering ? 'recovering' : 'failed',
      lastExit: { ...result, reason, at: new Date().toISOString() },
      reason,
    });
    if (!recovering) {
      break;
    }
    attempt++;
    await sleep(attempt * 3000);
    if (name === 'backend') {
      try {
        databaseReady(config);
      } catch {
        event(name, { kind: 'restart-blocked', reason: 'database-not-ready' });
        writeState(name, { ...readState(name), status: 'failed', reason: 'database-not-ready' });
        break;
      }
    }
  }
  event(name, { kind: 'supervisor-down', pid: process.pid });
}

async function start(name, config, configPath, jar) {
  if (name === 'backend') {
    databaseReady(config);
  }
  const previous = readState(name);
  const supervisorAlive = sameProcess(previous?.worker, identity(previous?.worker?.pid));
  const instance = instanceFingerprint(name, config);
  if (supervisorAlive && previous.instance !== instance) {
    throw Error(`${name}: running configuration is different or unverified; use a controlled restart`);
  }
  const pid = listener(config[name].port);
  if (pid) {
    const current = identity(pid);
    if (!expectedOwner(name, current, config)) {
      throw Error(`Port ${config[name].port} belongs to an unmanaged/different process; nothing was stopped`);
    }
    if (name !== 'database' && !supervisorAlive && ownedByThisController(name, current)) {
      // The supervisor died but its child survived. Replace the orphan with a supervised
      // process instead of reporting an unmanageable service as ready.
      event(name, { kind: 'adopting-orphan', pid });
      await terminate(name, config, current);
      return start(name, config, configPath, jar);
    }
    if (!(await httpReady(name, config))) {
      throw Error(`${name} is listening but unhealthy; nothing was stopped`);
    }
    if (name !== 'database' && !supervisorAlive) {
      throw Error(`${name}: supervisor is missing; use a controlled restart, not silent reuse`);
    }
    if (name !== 'database' && !sameProcess(previous?.child, current)) {
      throw Error(`${name}: port owner is not the registered child; nothing was adopted or stopped`);
    }
    if (!sameProcess(previous?.child, current)) {
      writeState(name, {
        ...previous,
        name,
        child: savedIdentity(current),
        status: 'ready',
        adopted: !previous?.worker,
        instance,
      });
    }
    console.log(`${name}: reused healthy process ${pid}`);
    return;
  }
  if (!supervisorAlive && name === 'backend') {
    databaseReady(config);
    jar ||= await buildBackend(config);
  }
  if (!supervisorAlive && fs.existsSync(stoppedFile(name))) {
    fs.unlinkSync(stoppedFile(name));
  }
  let supervisorPid = previous?.worker?.pid;
  if (!supervisorAlive) {
    // A supervisor that lost its registration would keep restarting its own child, so it
    // is cleared first; only processes this controller can prove it owns are touched.
    await reclaimStaleSupervisors(name);
    launchDetached(name, configPath, jar);
    // The detached supervisor registers itself; wait for that claim before health polling.
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      const registered = readState(name);
      if (registered?.worker && sameProcess(registered.worker, identity(registered.worker.pid))) {
        supervisorPid = registered.worker.pid;
        break;
      }
      await sleep(500);
    }
    if (!supervisorPid || !sameProcess(readState(name)?.worker, identity(supervisorPid))) {
      throw Error(`${name}: detached supervisor did not start; inspect tmp/local-runtime/${name}.supervisor*.log`);
    }
  }
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const registered = readState(name);
    const runningSupervisor = identity(supervisorPid);
    if (!sameProcess(registered?.worker, runningSupervisor)) {
      if (!runningSupervisor) {
        break;
      }
      await sleep(300);
      continue;
    }
    if (await httpReady(name, config)) {
      const actual = identity(listener(config[name].port));
      if (!expectedOwner(name, actual, config)) {
        throw Error(`${name}: unexpected process claimed the port; nothing was stopped`);
      }
      const currentRegistration = readState(name);
      if (
        !sameProcess(currentRegistration?.worker, runningSupervisor) ||
        !sameProcess(currentRegistration?.child, actual)
      ) {
        throw Error(`${name}: port owner is not the registered child; nothing was adopted or stopped`);
      }
      writeState(name, { ...currentRegistration, status: 'ready', readyAt: new Date().toISOString() });
      console.log(`${name}: ready on ${config[name].port}`);
      return;
    }
    const state = readState(name);
    if (state?.status === 'failed' && !identity(supervisorPid)) {
      break;
    }
    await sleep(1000);
  }
  throw Error(`${name} did not become ready; inspect tmp/local-runtime/${name}.*.log`);
}

// A supervisor whose registration was lost keeps restarting its child, so it must be
// identified from the command line rather than from the state file.
function nodeSupervisors(name) {
  const result = ps(
    `Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -like '*local-runtime.mjs*' -and $_.CommandLine -like '*worker ${name}*' } | ForEach-Object { [pscustomobject]@{pid=$_.ProcessId;started=$_.CreationDate.ToUniversalTime().Ticks.ToString();command=$_.CommandLine} } | ConvertTo-Json -Compress`,
  );
  const text = result.stdout.trim();
  if (!text) {
    return [];
  }
  const parsed = JSON.parse(text);
  return (Array.isArray(parsed) ? parsed : [parsed])
    .filter((value) => value.pid !== process.pid)
    .map((value) => ({
      pid: value.pid,
      started: value.started,
      hash: crypto.createHash('sha256').update(value.command || '').digest('hex'),
      command: value.command || '',
    }));
}

async function reclaimStaleSupervisors(name) {
  const stale = nodeSupervisors(name);
  for (const supervisor of stale) {
    event(name, { kind: 'stopping-stale-supervisor', pid: supervisor.pid });
    try {
      process.kill(supervisor.pid, 'SIGTERM');
    } catch {
      // Already gone.
    }
  }
  for (let i = 0; i < 25 && nodeSupervisors(name).length; i++) {
    await sleep(200);
  }
  if (nodeSupervisors(name).length) {
    throw Error(`${name}: an unregistered supervisor did not exit; nothing else was killed`);
  }
}

async function terminate(name, config, current) {
  if (name === 'database') {
    // Never force-terminate MySQL; no account changes or bootstrap SQL on restart.
    mysql(config, 'SHUTDOWN', true);
    console.log('database: graceful shutdown requested');
    return;
  }
  fs.writeFileSync(stoppedFile(name), new Date().toISOString());
  if (current) {
    process.kill(current.pid, 'SIGTERM');
  }
  for (let i = 0; i < 30 && listener(config[name].port); i++) {
    await sleep(200);
  }
  if (listener(config[name].port)) {
    throw Error(`${name}: shutdown did not finish; no unrelated process was killed`);
  }
}

async function stop(name, config) {
  let saved = readState(name);
  const supervisorAlive = sameProcess(saved?.worker, identity(saved?.worker?.pid));
  const pid = listener(config[name].port);
  if (!pid && !supervisorAlive) {
    // Nothing owns this service any more: record the truth instead of leaving a stale claim.
    if (saved && saved.status !== 'stopped') {
      writeState(name, {
        ...saved,
        child: null,
        worker: null,
        status: 'stopped',
        reason: saved.lastExit ? exitReason(saved.lastExit) : 'already stopped',
        stoppedAt: new Date().toISOString(),
      });
      event(name, { kind: 'cleared-stale-claim', previous: saved.status });
    }
    console.log(`${name}: already stopped`);
    return;
  }
  const current = identity(pid || saved?.child?.pid);
  if (supervisorAlive && current && !sameProcess(saved?.child, current)) {
    const latest = readState(name);
    if (sameProcess(latest?.worker, saved.worker)) {
      saved = latest;
    }
  }
  const registered = sameProcess(saved?.child, current);
  if ((!supervisorAlive || current) && (!registered || !expectedOwner(name, current, config))) {
    // An orphan whose supervisor died is still ours when the ownership ledger proves it.
    if (current && expectedOwner(name, current, config) && ownedByThisController(name, current)) {
      event(name, { kind: 'stopping-orphan', pid: current.pid });
    } else {
      throw Error(`${name}: process identity changed or is unmanaged; nothing was stopped`);
    }
  }
  await terminate(name, config, current);
  if (name === 'database') {
    return;
  }
  for (let i = 0; i < 20 && sameProcess(saved.worker, identity(saved.worker?.pid)); i++) {
    await sleep(200);
  }
  if (sameProcess(saved.worker, identity(saved.worker?.pid))) {
    throw Error(`${name}: supervisor has not stopped yet; no replacement worker was started`);
  }
  writeState(name, {
    ...readState(name),
    child: null,
    worker: null,
    status: 'stopped',
    stoppedAt: new Date().toISOString(),
  });
  event(name, { kind: 'stopped-by-owner', pid: current?.pid || null });
  console.log(`${name}: stopped owned service`);
}

export async function main(argv) {
  const [action = 'status', service = 'all', configPath = path.join(repo, 'tmp/local-runtime.json'), jar] = argv;
  if (
    !['status', 'health', 'start', 'stop', 'restart', 'build', 'worker'].includes(action) ||
    !['all', 'backend', 'frontend', 'database'].includes(service)
  ) {
    throw Error(
      'Usage: manage-local.ps1 -Action start|stop|restart|status|health|build -Service all|backend|frontend|database',
    );
  }
  fs.mkdirSync(runtime, { recursive: true });
  const config = read(configPath);
  if (action === 'worker') {
    return worker(service, config, jar === '-' ? undefined : jar);
  }
  const release = await acquireControl();
  try {
    const names = service === 'all' ? ['database', 'backend', 'frontend'] : [service];
    if (action === 'status' || action === 'health') {
      const unhealthy = [];
      for (const name of names) {
        const report = await describe(name, config);
        console.log(JSON.stringify(report));
        if (report.state === 'crashed') {
          // Correct the file so a dead service is never reported as ready again.
          const saved = readState(name) || {};
          writeState(name, {
            ...saved,
            name,
            child: null,
            worker: null,
            status: 'failed',
            reason: report.reason,
          });
        }
        if (report.state !== 'ready') {
          unhealthy.push(`${name}(${report.state})`);
        }
      }
      if (action === 'health' && unhealthy.length) {
        throw Error(`Unhealthy services: ${unhealthy.join(', ')}`);
      }
    } else if (action === 'build') {
      if (service !== 'backend') {
        throw Error('Use npm run build for frontend verification');
      }
      console.log(await buildBackend(config));
    } else if (action === 'stop') {
      // Stopping the app leaves the persistent database running unless explicitly selected.
      for (const name of [...names].reverse().filter((name) => service !== 'all' || name !== 'database')) {
        await stop(name, config);
      }
    } else if (action === 'restart') {
      if (service === 'all') {
        throw Error('Restart one service at a time; database is never implicitly restarted');
      }
      const stagedJar = service === 'backend' ? await buildBackend(config) : undefined;
      await stop(service, config);
      await start(service, config, configPath, stagedJar);
    } else {
      for (const name of names) {
        await start(name, config, configPath);
      }
    }
  } finally {
    await release();
  }
}

// Node resolves the imported module through a Windows junction; argv retains the alias.
// Compare real paths so an ASCII project alias cannot silently skip the CLI.
if (process.argv[1] && normalized(fs.realpathSync(process.argv[1])) === normalized(script)) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
