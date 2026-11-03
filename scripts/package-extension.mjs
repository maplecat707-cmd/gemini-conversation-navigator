import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const python = process.platform === 'win32' ? 'python' : 'python3';
const result = spawnSync(python, [fileURLToPath(new URL('./package-extension.py', import.meta.url))], { stdio: 'inherit' });
if (result.error) console.error(`Python 3 is required for packaging: ${result.error.message}`);
process.exit(result.status ?? 1);
