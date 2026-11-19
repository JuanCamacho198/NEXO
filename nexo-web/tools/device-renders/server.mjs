import { join, normalize } from 'node:path';

const root = import.meta.dir;

Bun.serve({
  port: 4390,
  hostname: '127.0.0.1',
  async fetch(req) {
    const url = new URL(req.url);
    if (req.method === 'POST' && url.pathname === '/save') {
      const name = (url.searchParams.get('name') ?? 'out').replace(/[^a-z0-9-]/gi, '');
      const dataUrl = await req.text();
      const bytes = Buffer.from(dataUrl.split(',')[1], 'base64');
      await Bun.write(join(root, 'out', `${name}.png`), bytes);
      return new Response(`saved ${name}.png ${bytes.length}`);
    }
    const path = normalize(join(root, url.pathname === '/' ? 'render.html' : url.pathname));
    if (!path.startsWith(root)) return new Response('no', { status: 403 });
    const file = Bun.file(path);
    return (await file.exists()) ? new Response(file) : new Response('404', { status: 404 });
  },
});
console.log('render server on http://127.0.0.1:4390');
