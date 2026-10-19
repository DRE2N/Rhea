#!/usr/bin/env node
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const options = {};
for (let i = 2; i < process.argv.length; i++) {
  const arg = process.argv[i];
  if (arg === '--help') {
    console.log('Usage: rhea [--source UI_DIR] [--output BUILD_DIR]');
    process.exit(0);
  }
  if (!['--source', '--output'].includes(arg) || !process.argv[i + 1] || process.argv[i + 1].startsWith('--')) {
    throw new Error(`Expected --source or --output followed by a path; got ${arg}`);
  }
  options[arg.slice(2)] = process.argv[++i];
}

const source = path.resolve(options.source || '.');
const output = path.resolve(options.output || 'build/rhea');
const directory = path.dirname(fileURLToPath(import.meta.url));
for (const [script, args] of [
  ['compile.mjs', ['--source', source, '--output', output]],
  ['verify.mjs', [output]]
]) {
  const result = spawnSync(process.execPath, [path.join(directory, script), ...args], {stdio: 'inherit'});
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
