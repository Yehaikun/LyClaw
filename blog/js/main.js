// ===== DOM 引用 =====
const $ = (s, ctx = document) => ctx.querySelector(s);
const $$ = (s, ctx = document) => [...ctx.querySelectorAll(s)];

const body = document.body;
const navbar = $('.navbar');
const themeToggle = $('#themeToggle');
const menuToggle = $('#menuToggle');
const navLinks = $('.nav-links');
const backToTop = $('#backToTop');
const articlesGrid = $('#articlesGrid');
const categoriesGrid = $('#categoriesGrid');

// ===== 主题切换 =====
const savedTheme = localStorage.getItem('blog-theme') || 'dark';
document.documentElement.setAttribute('data-theme', savedTheme);
updateThemeIcon(savedTheme);

themeToggle.addEventListener('click', () => {
    const current = document.documentElement.getAttribute('data-theme');
    const next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('blog-theme', next);
    updateThemeIcon(next);
});

function updateThemeIcon(theme) {
    const icon = themeToggle.querySelector('i');
    icon.className = theme === 'dark' ? 'fas fa-moon' : 'fas fa-sun';
}

// ===== 移动端菜单 =====
menuToggle.addEventListener('click', () => {
    navLinks.classList.toggle('open');
});

$$('.nav-links a').forEach(link => {
    link.addEventListener('click', () => {
        navLinks.classList.remove('open');
        $$('.nav-links a').forEach(l => l.classList.remove('active'));
        link.classList.add('active');
    });
});

// ===== 导航栏滚动效果 =====
let lastScroll = 0;
window.addEventListener('scroll', () => {
    const scrollY = window.scrollY;
    
    // 导航栏样式
    navbar.classList.toggle('scrolled', scrollY > 50);
    
    // 回到顶部按钮
    backToTop.classList.toggle('visible', scrollY > 500);
    
    // 活跃导航链接
    const sections = $$('section[id]');
    sections.forEach(section => {
        const top = section.offsetTop - 200;
        const bottom = top + section.offsetHeight;
        const id = section.getAttribute('id');
        if (scrollY >= top && scrollY < bottom) {
            $$('.nav-links a').forEach(l => {
                l.classList.toggle('active', l.getAttribute('href') === `#${id}`);
            });
        }
    });
    
    lastScroll = scrollY;
});

// ===== 回到顶部 =====
backToTop.addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
});

// ===== 打字机效果 =====
function typewriter(element) {
    const text = element.dataset.text;
    if (!text) return;
    let index = 0;
    element.textContent = '';
    element.classList.add('typewriter');
    
    function type() {
        if (index < text.length) {
            element.textContent = text.slice(0, index + 1);
            index++;
            setTimeout(type, 60 + Math.random() * 40);
        }
    }
    type();
}

const typewriterEl = $('.typewriter');
if (typewriterEl) {
    setTimeout(() => typewriter(typewriterEl), 500);
}

// ===== 数字递增动画 =====
function animateCounters() {
    const counters = $$('.stat-num');
    counters.forEach(counter => {
        const target = parseInt(counter.dataset.target);
        const increment = target / 60;
        let current = 0;
        
        const update = () => {
            current += increment;
            if (current < target) {
                counter.textContent = Math.ceil(current);
                requestAnimationFrame(update);
            } else {
                counter.textContent = target;
            }
        };
        update();
    });
}

// 触发计数器动画（当进入视口时）
const heroObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
        if (entry.isIntersecting) {
            animateCounters();
            heroObserver.disconnect();
        }
    });
}, { threshold: 0.5 });

const heroStats = $('.hero-stats');
if (heroStats) heroObserver.observe(heroStats);

// ===== 文章数据 =====
const articles = [
    {
        title: 'Spring Boot 3.x 新特性深度解析',
        category: 'Java',
        desc: '全面解读 Spring Boot 3.x 带来的革命性变化，包括 GraalVM 支持、虚拟线程等核心特性。',
        date: '2025-05-20',
        readTime: '8 min',
        icon: '🍃'
    },
    {
        title: '从零搭建 Kubernetes 开发环境',
        category: '云原生',
        desc: '手把手教你使用 Minikube 搭建本地 K8s 集群，并部署你的第一个微服务应用。',
        date: '2025-05-15',
        readTime: '12 min',
        icon: '☸️'
    },
    {
        title: 'Redis 核心数据结构与实战场景',
        category: '数据库',
        desc: '深入浅出地讲解 Redis 的 5 种核心数据结构，以及在高并发场景下的最佳实践。',
        date: '2025-05-08',
        readTime: '10 min',
        icon: '📦'
    },
    {
        title: '设计模式：策略模式在实际项目中的应用',
        category: '架构',
        desc: '通过一个真实的支付系统重构案例，带你彻底搞懂策略模式及其最佳实践。',
        date: '2025-04-28',
        readTime: '6 min',
        icon: '🎯'
    },
    {
        title: 'Docker 容器化部署最佳实践',
        category: 'DevOps',
        desc: '总结 Docker 生产环境部署的经验教训，涵盖多阶段构建、安全扫描等实用技巧。',
        date: '2025-04-20',
        readTime: '9 min',
        icon: '🐳'
    },
    {
        title: 'Java 并发编程：CompletableFuture 详解',
        category: 'Java',
        desc: '从入门到精通，系统学习 CompletableFuture 的异步编程模型和实际应用。',
        date: '2025-04-12',
        readTime: '11 min',
        icon: '⚡'
    }
];

const categories = [
    { name: 'Java', count: 12, icon: '🍃', desc: 'Java 核心技术 & 框架' },
    { name: '云原生', count: 8, icon: '☁️', desc: '容器化 & K8s & 微服务' },
    { name: '数据库', count: 7, icon: '🗄️', desc: 'MySQL & Redis 实战' },
    { name: '架构', count: 5, icon: '🏗️', desc: '系统设计与架构演进' },
    { name: 'DevOps', count: 6, icon: '🛠️', desc: 'CI/CD & 自动化运维' },
    { name: '前端', count: 4, icon: '🎨', desc: 'Vue & React & 工具链' }
];

// ===== 渲染文章卡片 =====
function renderArticles() {
    articlesGrid.innerHTML = articles.map(article => `
        <article class="article-card glass">
            <span class="card-category">${article.icon} ${article.category}</span>
            <h3>${article.title}</h3>
            <p>${article.desc}</p>
            <div class="card-footer">
                <span><i class="far fa-calendar-alt"></i> ${article.date}</span>
                <span><i class="far fa-clock"></i> ${article.readTime}</span>
                <span class="read-more">阅读更多 <i class="fas fa-arrow-right"></i></span>
            </div>
        </article>
    `).join('');
}

// ===== 渲染分类卡片 =====
function renderCategories() {
    categoriesGrid.innerHTML = categories.map(cat => `
        <div class="category-card glass">
            <span class="category-icon">${cat.icon}</span>
            <h3>${cat.name}</h3>
            <p>${cat.desc}</p>
            <p style="margin-top:12px;font-size:0.8rem;color:var(--primary-light);font-weight:600;">${cat.count} 篇文章</p>
        </div>
    `).join('');
}

// ===== 星星背景 (Canvas) =====
function createStarfield() {
    const container = $('#starfield');
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    container.appendChild(canvas);

    let stars = [];
    let w, h;

    function resize() {
        w = canvas.width = window.innerWidth;
        h = canvas.height = window.innerHeight;
    }

    function initStars(count = 150) {
        stars = [];
        for (let i = 0; i < count; i++) {
            stars.push({
                x: Math.random() * w,
                y: Math.random() * h,
                r: Math.random() * 2 + 0.5,
                dx: (Math.random() - 0.5) * 0.3,
                dy: (Math.random() - 0.5) * 0.3,
                alpha: Math.random() * 0.8 + 0.2
            });
        }
    }

    function draw() {
        ctx.clearRect(0, 0, w, h);
        
        const theme = document.documentElement.getAttribute('data-theme');
        if (theme === 'light') {
            // 亮色模式淡出星星
            return;
        }

        stars.forEach(star => {
            ctx.beginPath();
            ctx.arc(star.x, star.y, star.r, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(255, 255, 255, ${star.alpha})`;
            ctx.fill();

            star.x += star.dx;
            star.y += star.dy;

            if (star.x < 0) star.x = w;
            if (star.x > w) star.x = 0;
            if (star.y < 0) star.y = h;
            if (star.y > h) star.y = 0;
        });

        requestAnimationFrame(draw);
    }

    resize();
    initStars(200);
    draw();

    window.addEventListener('resize', () => {
        resize();
        initStars(200);
    });

    // 监听主题变化重新绘制
    const observer = new MutationObserver(() => {
        const theme = document.documentElement.getAttribute('data-theme');
        if (theme === 'light') {
            ctx.clearRect(0, 0, w, h);
        }
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
}

// ===== 滚动动画 (IntersectionObserver) =====
function initScrollAnimations() {
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, { threshold: 0.1 });

    // 为卡片添加初始动画样式
    $$('.article-card, .category-card').forEach(card => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(30px)';
        card.style.transition = 'all 0.6s cubic-bezier(0.4, 0, 0.2, 1)';
        observer.observe(card);
    });
}

// ===== 文章卡片点击跳转 =====
$$('.article-card').forEach(card => {
    card.addEventListener('click', () => {
        // 可以跳转到详情页，这里模拟
        const title = card.querySelector('h3').textContent;
        alert(`即将打开文章: ${title}\n（文章详情页开发中...）`);
    });
});

// ===== 初始化 =====
document.addEventListener('DOMContentLoaded', () => {
    renderArticles();
    renderCategories();
    createStarfield();
    
    // 延迟执行滚动动画，等卡片渲染完毕
    setTimeout(initScrollAnimations, 100);
    
    console.log('🚀 LyClaw Blog 已启动！');
    console.log('💡 主题切换: 点击右上角月亮/太阳图标');
    console.log('📱 响应式设计: 试试缩小浏览器窗口');
});
