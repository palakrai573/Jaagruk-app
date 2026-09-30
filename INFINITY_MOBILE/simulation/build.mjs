import { build } from 'esbuild';
import { copyFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const out = fileURLToPath(new URL('../app/src/main/assets/simulation/', import.meta.url));
await mkdir(out, { recursive: true });
await build({ entryPoints: [fileURLToPath(new URL('./main.jsx', import.meta.url))],
  outfile: out + 'scene.js', bundle: true, minify: true, format: 'iife', jsx: 'automatic',
  define: { 'process.env.NODE_ENV': '"production"' }, legalComments: 'eof' });
await copyFile(new URL('./index.html', import.meta.url), out + 'index.html');
