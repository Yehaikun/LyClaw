const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 4000;
const PUBLIC_DIR = __dirname;

const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.json': 'application/json',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf',
    '.eot': 'application/vnd.ms-fontobject',
    '.txt': 'text/plain; charset=utf-8',
    '.md': 'text/markdown; charset=utf-8'
};

const server = http.createServer((req, res) => {
    let url = req.url;
    
    // 默认首页
    if (url === '/') url = '/index.html';
    
    const filePath = path.join(PUBLIC_DIR, url);
    
    // 安全检查：防止目录遍历
    if (!filePath.startsWith(PUBLIC_DIR)) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
    }
    
    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    
    fs.readFile(filePath, (err, data) => {
        if (err) {
            if (err.code === 'ENOENT') {
                // 404 - 尝试返回 index.html（SPA 路由支持）
                fs.readFile(path.join(PUBLIC_DIR, 'index.html'), (err2, data2) => {
                    if (err2) {
                        res.writeHead(500);
                        res.end('500 Internal Server Error');
                        return;
                    }
                    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                    res.end(data2);
                });
            } else {
                res.writeHead(500);
                res.end('500 Internal Server Error');
            }
            return;
        }
        res.writeHead(200, { 'Content-Type': contentType });
        res.end(data);
    });
});

server.listen(PORT, '0.0.0.0', () => {
    const address = `http://localhost:${PORT}`;
    console.log('');
    console.log('  ╔══════════════════════════════════════╗');
    console.log('  ║   ✨ LyClaw 个人博客 · 已启动 ✨    ║');
    console.log(`  ║   📍 ${address}${' '.repeat(Math.max(0, 30 - address.length))}║`);
    console.log('  ╠══════════════════════════════════════╣');
    console.log('  ║   🎨 主题切换 · 响应式设计           ║');
    console.log('  ║   ⭐ 按下 Ctrl+C 停止服务器          ║');
    console.log('  ╚══════════════════════════════════════╝');
    console.log('');
});
