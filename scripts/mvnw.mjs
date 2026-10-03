// Runs the backend's Maven wrapper with the given arguments, on any OS.
//   node scripts/mvnw.mjs spring-boot:run
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const backend = fileURLToPath(new URL('../backend/', import.meta.url));
const args = process.argv.slice(2);

const child =
  process.platform === 'win32'
    ? // .cmd files need a shell on Windows; arguments come from our own package.json scripts. Absolute path,
      // because cmd may be configured not to search the current directory (NoDefaultCurrentDirectoryInExePath).
      spawn([`"${join(backend, 'mvnw.cmd')}"`, ...args].join(' '), { cwd: backend, stdio: 'inherit', shell: true })
    : spawn('./mvnw', args, { cwd: backend, stdio: 'inherit' });

child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal));
}
