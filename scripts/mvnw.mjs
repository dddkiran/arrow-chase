// Runs the backend's Maven wrapper with the given arguments, on any OS.
//   node scripts/mvnw.mjs spring-boot:run
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const backend = fileURLToPath(new URL('../backend/', import.meta.url));
const args = process.argv.slice(2);

checkJava();

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

/**
 * Fails fast, with one readable message, when Maven would run on a JDK older than the project needs.
 * Without this, an old JDK surfaces as a 200-line UnsupportedClassVersionError from inside Maven.
 * Uses the same JDK the Maven wrapper will use: JAVA_HOME if set, otherwise `java` on PATH.
 */
function checkJava() {
  const pom = readFileSync(join(backend, 'pom.xml'), 'utf8');
  const required = Number(pom.match(/<java\.version>(\d+)<\/java\.version>/)?.[1]);
  if (!required) return;

  const javaHome = process.env.JAVA_HOME;
  const java = javaHome ? join(javaHome, 'bin', 'java') : 'java';
  const result = spawnSync(java, ['-version'], { encoding: 'utf8' });
  const found = result.error ? null : parseMajor(`${result.stderr}${result.stdout}`);
  if (found !== null && found >= required) return;

  const source = javaHome ? `JAVA_HOME (${javaHome})` : '`java` on your PATH';
  const what = found === null ? 'no working Java' : `Java ${found}`;
  console.error(
    [
      '',
      `  The backend needs JDK ${required} or newer, but ${source} is ${what}.`,
      '',
      `  Install JDK ${required} and point JAVA_HOME at it, then open a new terminal:`,
      `    Windows:  winget install --id EclipseAdoptium.Temurin.${required}.JDK -e`,
      `              setx JAVA_HOME "C:\\Program Files\\Eclipse Adoptium\\jdk-${required}..."   (the folder it installed)`,
      `    macOS:    brew install --cask temurin@${required}  &&  export JAVA_HOME=$(/usr/libexec/java_home -v ${required})`,
      `    Linux:    sdk install java ${required}-tem   (or your package manager), then export JAVA_HOME`,
      '',
    ].join('\n'),
  );
  process.exit(1);
}

/** "1.8.0_501" -> 8, "25.0.4.1" -> 25, "17" -> 17 */
function parseMajor(versionOutput) {
  const version = versionOutput.match(/version "([^"]+)"/)?.[1];
  if (!version) return null;
  const [first, second] = version.split(/[._-]/);
  return Number(first === '1' ? second : first);
}
